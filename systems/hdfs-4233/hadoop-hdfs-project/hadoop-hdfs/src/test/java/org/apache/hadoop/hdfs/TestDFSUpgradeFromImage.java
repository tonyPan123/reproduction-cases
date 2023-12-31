/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs;

import junit.framework.TestCase;
import java.io.*;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.zip.CRC32;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.util.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.junit.Test;

/**
 * This tests data transfer protocol handling in the Datanode. It sends
 * various forms of wrong data and verifies that Datanode handles it well.
 * 
 * This test uses the following items from src/test/.../dfs directory :
 *   1) hadoop-22-dfs-dir.tgz and other tarred pre-upgrade NN / DN 
 *      directory images
 *   2) hadoop-dfs-dir.txt : checksums that are compared in this test.
 * Please read hadoop-dfs-dir.txt for more information.  
 */
public class TestDFSUpgradeFromImage extends TestCase {
  
  private static final Log LOG = LogFactory
      .getLog(TestDFSUpgradeFromImage.class);
  private static File TEST_ROOT_DIR =
                      new File(MiniDFSCluster.getBaseDirectory());
  private static final String HADOOP14_IMAGE = "hadoop-14-dfs-dir.tgz";
  private static final String HADOOP_DFS_DIR_TXT = "hadoop-dfs-dir.txt";
  private static final String HADOOP22_IMAGE = "hadoop-22-dfs-dir.tgz";
  private static final String HADOOP1_BBW_IMAGE = "hadoop1-bbw.tgz";

  private static class ReferenceFileInfo {
    String path;
    long checksum;
  }
  
  private static final Configuration upgradeConf;
  
  static {
    upgradeConf = new HdfsConfiguration();
    upgradeConf.setInt(DFSConfigKeys.DFS_DATANODE_SCAN_PERIOD_HOURS_KEY, -1); // block scanning off
    if (System.getProperty("test.build.data") == null) { // to allow test to be run outside of Maven
      System.setProperty("test.build.data", "build/test/data");
    }
  }
  
  LinkedList<ReferenceFileInfo> refList = new LinkedList<ReferenceFileInfo>();
  Iterator<ReferenceFileInfo> refIter;
  
  boolean printChecksum = false;
  
  public void unpackStorage() throws IOException {
    unpackStorage(HADOOP14_IMAGE);
  }

  private void unpackStorage(String tarFileName)
      throws IOException {
    String tarFile = System.getProperty("test.cache.data", "build/test/cache")
        + "/" + tarFileName;
    String dataDir = System.getProperty("test.build.data", "build/test/data");
    File dfsDir = new File(dataDir, "dfs");
    if ( dfsDir.exists() && !FileUtil.fullyDelete(dfsDir) ) {
      throw new IOException("Could not delete dfs directory '" + dfsDir + "'");
    }
    LOG.info("Unpacking " + tarFile);
    FileUtil.unTar(new File(tarFile), new File(dataDir));
    //Now read the reference info
    
    BufferedReader reader = new BufferedReader(new FileReader(
        System.getProperty("test.cache.data", "build/test/cache")
            + "/" + HADOOP_DFS_DIR_TXT));
    String line;
    while ( (line = reader.readLine()) != null ) {
      
      line = line.trim();
      if (line.length() <= 0 || line.startsWith("#")) {
        continue;
      }
      String[] arr = line.split("\\s+\t\\s+");
      if (arr.length < 1) {
        continue;
      }
      if (arr[0].equals("printChecksums")) {
        printChecksum = true;
        break;
      }
      if (arr.length < 2) {
        continue;
      }
      ReferenceFileInfo info = new ReferenceFileInfo();
      info.path = arr[0];
      info.checksum = Long.parseLong(arr[1]);
      refList.add(info);
    }
    reader.close();
  }

  private void verifyChecksum(String path, long checksum) throws IOException {
    if ( refIter == null ) {
      refIter = refList.iterator();
    }
    
    if ( printChecksum ) {
      LOG.info("CRC info for reference file : " + path + " \t " + checksum);
    } else {
      if ( !refIter.hasNext() ) {
        throw new IOException("Checking checksum for " + path +
                              "Not enough elements in the refList");
      }
      ReferenceFileInfo info = refIter.next();
      // The paths are expected to be listed in the same order 
      // as they are traversed here.
      assertEquals(info.path, path);
      assertEquals("Checking checksum for " + path, info.checksum, checksum);
    }
  }
  
  /**
   * Try to open a file for reading several times.
   * 
   * If we fail because lease recovery hasn't completed, retry the open.
   */
  private static FSInputStream dfsOpenFileWithRetries(DistributedFileSystem dfs,
      String pathName) throws IOException {
    IOException exc = null;
    for (int tries = 0; tries < 10; tries++) {
      try {
        return dfs.dfs.open(pathName);
      } catch (IOException e) {
        exc = e;
      }
      if (!exc.getMessage().contains("Cannot obtain " +
          "block length for LocatedBlock")) {
        throw exc;
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {}
    }
    throw exc;
  }
  
  private void verifyDir(DistributedFileSystem dfs, Path dir,
      CRC32 overallChecksum) throws IOException {
    FileStatus[] fileArr = dfs.listStatus(dir);
    TreeMap<Path, Boolean> fileMap = new TreeMap<Path, Boolean>();
    
    for(FileStatus file : fileArr) {
      fileMap.put(file.getPath(), Boolean.valueOf(file.isDirectory()));
    }
    
    for(Iterator<Path> it = fileMap.keySet().iterator(); it.hasNext();) {
      Path path = it.next();
      boolean isDir = fileMap.get(path);
      
      String pathName = path.toUri().getPath();
      overallChecksum.update(pathName.getBytes());
      
      if ( isDir ) {
        verifyDir(dfs, path, overallChecksum);
      } else {
        // this is not a directory. Checksum the file data.
        CRC32 fileCRC = new CRC32();
        FSInputStream in = dfsOpenFileWithRetries(dfs, pathName);
        byte[] buf = new byte[4096];
        int nRead = 0;
        while ( (nRead = in.read(buf, 0, buf.length)) > 0 ) {
          fileCRC.update(buf, 0, nRead);
        }
        
        verifyChecksum(pathName, fileCRC.getValue());
      }
    }
  }
  
  private void verifyFileSystem(DistributedFileSystem dfs) throws IOException {
  
    CRC32 overallChecksum = new CRC32();
    verifyDir(dfs, new Path("/"), overallChecksum);
    
    verifyChecksum("overallCRC", overallChecksum.getValue());
    
    if ( printChecksum ) {
      throw new IOException("Checksums are written to log as requested. " +
                            "Throwing this exception to force an error " +
                            "for this test.");
    }
  }
  
  /**
   * Test that sets up a fake image from Hadoop 0.3.0 and tries to start a
   * NN, verifying that the correct error message is thrown.
   */
  public void testFailOnPreUpgradeImage() throws IOException {
    Configuration conf = new HdfsConfiguration();

    File namenodeStorage = new File(TEST_ROOT_DIR, "nnimage-0.3.0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, namenodeStorage.toString());

    // Set up a fake NN storage that looks like an ancient Hadoop dir circa 0.3.0
    FileUtil.fullyDelete(namenodeStorage);
    assertTrue("Make " + namenodeStorage, namenodeStorage.mkdirs());
    File imageDir = new File(namenodeStorage, "image");
    assertTrue("Make " + imageDir, imageDir.mkdirs());

    // Hex dump of a formatted image from Hadoop 0.3.0
    File imageFile = new File(imageDir, "fsimage");
    byte[] imageBytes = StringUtils.hexStringToByte(
      "fffffffee17c0d2700000000");
    FileOutputStream fos = new FileOutputStream(imageFile);
    try {
      fos.write(imageBytes);
    } finally {
      fos.close();
    }

    // Now try to start an NN from it

    try {
      new MiniDFSCluster.Builder(conf).numDataNodes(0)
        .format(false)
        .manageDataDfsDirs(false)
        .manageNameDfsDirs(false)
        .startupOption(StartupOption.REGULAR)
        .build();
      fail("Was able to start NN from 0.3.0 image");
    } catch (IOException ioe) {
      if (!ioe.toString().contains("Old layout version is 'too old'")) {
        throw ioe;
      }
    }
  }
  
  /**
   * Test upgrade from 0.22 image
   */
  public void testUpgradeFromRel22Image() throws IOException {
    unpackStorage(HADOOP22_IMAGE);
    upgradeAndVerify(new MiniDFSCluster.Builder(upgradeConf).
        numDataNodes(4));
  }
  
  /**
   * Test upgrade from 0.22 image with corrupt md5, make sure it
   * fails to upgrade
   */
  public void testUpgradeFromCorruptRel22Image() throws IOException {
    unpackStorage(HADOOP22_IMAGE);
    
    // Overwrite the md5 stored in the VERSION files
    File baseDir = new File(MiniDFSCluster.getBaseDirectory());
    FSImageTestUtil.corruptVersionFile(
        new File(baseDir, "name1/current/VERSION"),
        "imageMD5Digest", "22222222222222222222222222222222");
    FSImageTestUtil.corruptVersionFile(
        new File(baseDir, "name2/current/VERSION"),
        "imageMD5Digest", "22222222222222222222222222222222");
    
    // Upgrade should now fail
    try {
      upgradeAndVerify(new MiniDFSCluster.Builder(upgradeConf).
          numDataNodes(4));
      fail("Upgrade did not fail with bad MD5");
    } catch (IOException ioe) {
      String msg = StringUtils.stringifyException(ioe);
      if (!msg.contains("is corrupt with MD5 checksum")) {
        throw ioe;
      }
    }
  }
    
  static void recoverAllLeases(DFSClient dfs, 
      Path path) throws IOException {
    String pathStr = path.toString();
    HdfsFileStatus status = dfs.getFileInfo(pathStr);
    if (!status.isDir()) {
      dfs.recoverLease(pathStr);
      return;
    }
    byte prev[] = HdfsFileStatus.EMPTY_NAME;
    DirectoryListing dirList;
    do {
      dirList = dfs.listPaths(pathStr, prev);
      HdfsFileStatus files[] = dirList.getPartialListing();
      for (HdfsFileStatus f : files) {
        recoverAllLeases(dfs, f.getFullPath(path));
      }
      prev = dirList.getLastName();
    } while (dirList.hasMore());
  }
  
  private void upgradeAndVerify(MiniDFSCluster.Builder bld)
      throws IOException {
    MiniDFSCluster cluster = null;
    try {
      bld.format(false).startupOption(StartupOption.UPGRADE)
        .clusterId("testClusterId");
      cluster = bld.build();

      cluster.waitActive();
      DistributedFileSystem dfs = (DistributedFileSystem)cluster.getFileSystem();
      DFSClient dfsClient = dfs.dfs;
      //Safemode will be off only after upgrade is complete. Wait for it.
      while ( dfsClient.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_GET) ) {
        LOG.info("Waiting for SafeMode to be OFF.");
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
      }
      recoverAllLeases(dfsClient, new Path("/"));
      verifyFileSystem(dfs);
    } finally {
      if (cluster != null) { cluster.shutdown(); }
    } 
  }

  /**
   * Test upgrade from a 1.x image with some blocksBeingWritten
   */
  @Test
  public void testUpgradeFromRel1BBWImage() throws IOException {
    unpackStorage(HADOOP1_BBW_IMAGE);
    Configuration conf = new Configuration(upgradeConf);
    conf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, 
        System.getProperty("test.build.data") + File.separator + 
        "dfs" + File.separator + 
        "data" + File.separator + 
        "data1");
    upgradeAndVerify(new MiniDFSCluster.Builder(conf).
          numDataNodes(1).enableManagedDfsDirsRedundancy(false).
          manageDataDfsDirs(false));
  }
}
