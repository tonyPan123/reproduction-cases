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
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.server.common.Util.now;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Options.Rename;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.FSLimitException;
import org.apache.hadoop.hdfs.protocol.FSLimitException.MaxDirectoryItemsExceededException;
import org.apache.hadoop.hdfs.protocol.FSLimitException.PathComponentTooLongException;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.HdfsLocatedFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.util.ByteArray;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/*************************************************
 * FSDirectory stores the filesystem directory state.
 * It handles writing/loading values to disk, and logging
 * changes as we go.
 *
 * It keeps the filename->blockset mapping always-current
 * and logged to disk.
 * 
 *************************************************/
public class FSDirectory implements Closeable {

  INodeDirectoryWithQuota rootDir;
  FSImage fsImage;  
  private volatile boolean ready = false;
  private static final long UNKNOWN_DISK_SPACE = -1;
  private final int maxComponentLength;
  private final int maxDirItems;
  private final int lsLimit;  // max list limit

  // lock to protect the directory and BlockMap
  private ReentrantReadWriteLock dirLock;
  private Condition cond;

  // utility methods to acquire and release read lock and write lock
  void readLock() {
    this.dirLock.readLock().lock();
  }

  void readUnlock() {
    this.dirLock.readLock().unlock();
  }

  void writeLock() {
    this.dirLock.writeLock().lock();
  }

  void writeUnlock() {
    this.dirLock.writeLock().unlock();
  }

  boolean hasWriteLock() {
    return this.dirLock.isWriteLockedByCurrentThread();
  }

  boolean hasReadLock() {
    return this.dirLock.getReadHoldCount() > 0;
  }

  /**
   * Caches frequently used file names used in {@link INode} to reuse 
   * byte[] objects and reduce heap usage.
   */
  private final NameCache<ByteArray> nameCache;

  /** Access an existing dfs name directory. */
  FSDirectory(FSNamesystem ns, Configuration conf) throws IOException {
    this(new FSImage(conf), ns, conf);
  }

  FSDirectory(FSImage fsImage, FSNamesystem ns, Configuration conf) {
    this.dirLock = new ReentrantReadWriteLock(true); // fair
    this.cond = dirLock.writeLock().newCondition();
    fsImage.setFSNamesystem(ns);
    rootDir = new INodeDirectoryWithQuota(INodeDirectory.ROOT_NAME,
        ns.createFsOwnerPermissions(new FsPermission((short)0755)),
        Integer.MAX_VALUE, UNKNOWN_DISK_SPACE);
    this.fsImage = fsImage;
    int configuredLimit = conf.getInt(
        DFSConfigKeys.DFS_LIST_LIMIT, DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT);
    this.lsLimit = configuredLimit>0 ?
        configuredLimit : DFSConfigKeys.DFS_LIST_LIMIT_DEFAULT;
    
    // filesystem limits
    this.maxComponentLength = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_COMPONENT_LENGTH_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_COMPONENT_LENGTH_DEFAULT);
    this.maxDirItems = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAX_DIRECTORY_ITEMS_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAX_DIRECTORY_ITEMS_DEFAULT);

    int threshold = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_KEY,
        DFSConfigKeys.DFS_NAMENODE_NAME_CACHE_THRESHOLD_DEFAULT);
    NameNode.LOG.info("Caching file names occuring more than " + threshold
        + " times ");
    nameCache = new NameCache<ByteArray>(threshold);
  }
    
  private FSNamesystem getFSNamesystem() {
    return fsImage.getFSNamesystem();
  }

  private BlockManager getBlockManager() {
    return getFSNamesystem().getBlockManager();
  }

  /**
   * Load the filesystem image into memory.
   *
   * @param startOpt Startup type as specified by the user.
   * @throws IOException If image or editlog cannot be read.
   */
  void loadFSImage(StartupOption startOpt) 
      throws IOException {
    // format before starting up if requested
    if (startOpt == StartupOption.FORMAT) {
      fsImage.format(fsImage.getStorage().determineClusterId());// reuse current id

      startOpt = StartupOption.REGULAR;
    }
    boolean success = false;
    try {
      if (fsImage.recoverTransitionRead(startOpt)) {
        fsImage.saveNamespace();
      }
      fsImage.openEditLog();
      
      fsImage.setCheckpointDirectories(null, null);
      success = true;
    } finally {
      if (!success) {
        fsImage.close();
      }
    }
    setReady();
  }
  

  /**
   * Notify that loading of this FSDirectory is complete, and
   * it is ready for use 
   */
  void imageLoadComplete() {
    Preconditions.checkState(!ready, "FSDirectory already loaded");
    setReady();
  }

  void setReady() {
    if(ready) return;
    writeLock();
    try {
      setReady(true);
      this.nameCache.initialized();
      cond.signalAll();
    } finally {
      writeUnlock();
    }
  }
  
  //This is for testing purposes only
  @VisibleForTesting
  boolean isReady() {
    return ready;
  }

  // exposed for unit tests
  protected void setReady(boolean flag) {
    ready = flag;
  }

  private void incrDeletedFileCount(int count) {
    if (getFSNamesystem() != null)
      NameNode.getNameNodeMetrics().incrFilesDeleted(count);
  }
    
  /**
   * Shutdown the filestore
   */
  public void close() throws IOException {
    fsImage.close();
  }

  /**
   * Block until the object is ready to be used.
   */
  void waitForReady() {
    if (!ready) {
      writeLock();
      try {
        while (!ready) {
          try {
            cond.await(5000, TimeUnit.MILLISECONDS);
          } catch (InterruptedException ie) {
          }
        }
      } finally {
        writeUnlock();
      }
    }
  }

  /**
   * Add the given filename to the fs.
   * @throws QuotaExceededException 
   * @throws FileAlreadyExistsException 
   */
  INodeFileUnderConstruction addFile(String path, 
                PermissionStatus permissions,
                short replication,
                long preferredBlockSize,
                String clientName,
                String clientMachine,
                DatanodeDescriptor clientNode,
                long generationStamp) 
    throws FileAlreadyExistsException, QuotaExceededException,
      UnresolvedLinkException {
    waitForReady();

    // Always do an implicit mkdirs for parent directory tree.
    long modTime = now();
    
    Path parent = new Path(path).getParent();
    if (parent == null) {
      // Trying to add "/" as a file - this path has no
      // parent -- avoids an NPE below.
      return null;
    }
    
    if (!mkdirs(parent.toString(), permissions, true, modTime)) {
      return null;
    }
    INodeFileUnderConstruction newNode = new INodeFileUnderConstruction(
                                 permissions,replication,
                                 preferredBlockSize, modTime, clientName, 
                                 clientMachine, clientNode);
    writeLock();
    try {
      newNode = addNode(path, newNode, UNKNOWN_DISK_SPACE);
    } finally {
      writeUnlock();
    }
    if (newNode == null) {
      NameNode.stateChangeLog.info("DIR* FSDirectory.addFile: "
                                   +"failed to add "+path
                                   +" to the file system");
      return null;
    }

    if(NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.addFile: "
          +path+" is added to the file system");
    }
    return newNode;
  }

  /**
   */
  INode unprotectedAddFile( String path, 
                            PermissionStatus permissions,
                            BlockInfo[] blocks, 
                            short replication,
                            long modificationTime,
                            long atime,
                            long preferredBlockSize,
                            String clientName,
                            String clientMachine)
      throws UnresolvedLinkException {
    INode newNode;
    assert hasWriteLock();
    if (blocks == null)
      newNode = new INodeDirectory(permissions, modificationTime);
    else if(blocks.length == 0 || blocks[blocks.length-1].getBlockUCState()
        == BlockUCState.UNDER_CONSTRUCTION) {
      newNode = new INodeFileUnderConstruction(
          permissions, blocks.length, replication,
          preferredBlockSize, modificationTime, clientName, 
          clientMachine, null);
    } else {
      newNode = new INodeFile(permissions, blocks.length, replication,
                              modificationTime, atime, preferredBlockSize);
    }
    writeLock();
    try {
      try {
        newNode = addNode(path, newNode, UNKNOWN_DISK_SPACE);
        if(newNode != null && blocks != null) {
          int nrBlocks = blocks.length;
          // Add file->block mapping
          INodeFile newF = (INodeFile)newNode;
          for (int i = 0; i < nrBlocks; i++) {
            newF.setBlock(i, getBlockManager().addINode(blocks[i], newF));
          }
        }
      } catch (IOException e) {
        return null;
      }
      return newNode;
    } finally {
      writeUnlock();
    }

  }

  /**
   * Update files in-memory data structures with new block information.
   * @throws IOException 
   */
  void updateFile(INodeFile file,
                  String path,
                  BlockInfo[] blocks, 
                  long mtime,
                  long atime) throws IOException {

    // Update the salient file attributes.
    file.setAccessTime(atime);
    file.setModificationTimeForce(mtime);

    // Update its block list
    BlockInfo[] oldBlocks = file.getBlocks();

    // Are we only updating the last block's gen stamp.
    boolean isGenStampUpdate = oldBlocks.length == blocks.length;

    // First, update blocks in common
    BlockInfo oldBlock = null;
    for (int i = 0; i < oldBlocks.length && i < blocks.length; i++) {
      oldBlock = oldBlocks[i];
      Block newBlock = blocks[i];

      boolean isLastBlock = i == oldBlocks.length - 1;
      if (oldBlock.getBlockId() != newBlock.getBlockId() ||
          (oldBlock.getGenerationStamp() != newBlock.getGenerationStamp() && 
              !(isGenStampUpdate && isLastBlock))) {
        throw new IOException("Mismatched block IDs or generation stamps, " + 
            "attempting to replace block " + oldBlock + " with " + newBlock +
            " as block # " + i + "/" + blocks.length + " of " + path);
      }

      oldBlock.setNumBytes(newBlock.getNumBytes());
      oldBlock.setGenerationStamp(newBlock.getGenerationStamp());
    }

    if (blocks.length < oldBlocks.length) {
      // We're removing a block from the file, e.g. abandonBlock(...)
      if (!file.isUnderConstruction()) {
        throw new IOException("Trying to remove a block from file " +
            path + " which is not under construction.");
      }
      if (blocks.length != oldBlocks.length - 1) {
        throw new IOException("Trying to remove more than one block from file "
            + path);
      }
      unprotectedRemoveBlock(path,
          (INodeFileUnderConstruction)file, oldBlocks[oldBlocks.length - 1]);
    } else if (blocks.length > oldBlocks.length) {
      // We're adding blocks
      // First complete last old Block
      getBlockManager().completeBlock(file, oldBlocks.length-1, true);
      // Add the new blocks
      for (int i = oldBlocks.length; i < blocks.length; i++) {
        // addBlock();
        BlockInfo newBI = blocks[i];
        getBlockManager().addINode(newBI, file);
        file.addBlock(newBI);
      }
    }
  }

  INodeDirectory addToParent(byte[] src, INodeDirectory parentINode,
      INode newNode, boolean propagateModTime) throws UnresolvedLinkException {
    // NOTE: This does not update space counts for parents
    INodeDirectory newParent = null;
    writeLock();
    try {
      try {
        newParent = rootDir.addToParent(src, newNode, parentINode,
                                        propagateModTime);
        cacheName(newNode);
      } catch (FileNotFoundException e) {
        return null;
      }
      if(newParent == null)
        return null;
      if(!newNode.isDirectory() && !newNode.isLink()) {
        // Add file->block mapping
        INodeFile newF = (INodeFile)newNode;
        BlockInfo[] blocks = newF.getBlocks();
        for (int i = 0; i < blocks.length; i++) {
          newF.setBlock(i, getBlockManager().addINode(blocks[i], newF));
        }
      }
    } finally {
      writeUnlock();
    }
    return newParent;
  }

  /**
   * Add a block to the file. Returns a reference to the added block.
   */
  BlockInfo addBlock(String path,
                     INode[] inodes,
                     Block block,
                     DatanodeDescriptor targets[]
  ) throws QuotaExceededException {
    waitForReady();

    writeLock();
    try {
      assert inodes[inodes.length-1].isUnderConstruction() :
        "INode should correspond to a file under construction";
      INodeFileUnderConstruction fileINode = 
        (INodeFileUnderConstruction)inodes[inodes.length-1];

      // check quota limits and updated space consumed
      updateCount(inodes, inodes.length-1, 0,
          fileINode.getPreferredBlockSize()*fileINode.getReplication(), true);

      // associate new last block for the file
      BlockInfoUnderConstruction blockInfo =
        new BlockInfoUnderConstruction(
            block,
            fileINode.getReplication(),
            BlockUCState.UNDER_CONSTRUCTION,
            targets);
      getBlockManager().addINode(blockInfo, fileINode);
      fileINode.addBlock(blockInfo);

      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.addBlock: "
            + path + " with " + block
            + " block is added to the in-memory "
            + "file system");
      }
      return blockInfo;
    } finally {
      writeUnlock();
    }
  }

  /**
   * Persist the block list for the inode.
   */
  void persistBlocks(String path, INodeFileUnderConstruction file) {
    waitForReady();

    writeLock();
    try {
      fsImage.getEditLog().logOpenFile(path, file);
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.persistBlocks: "
            +path+" with "+ file.getBlocks().length 
            +" blocks is persisted to the file system");
      }
    } finally {
      writeUnlock();
    }
  }

  /**
   * Close file.
   */
  void closeFile(String path, INodeFile file) {
    waitForReady();
    long now = now();
    writeLock();
    try {
      // file is closed
      file.setModificationTimeForce(now);
      fsImage.getEditLog().logCloseFile(path, file);
      if (NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.closeFile: "
            +path+" with "+ file.getBlocks().length 
            +" blocks is persisted to the file system");
      }
    } finally {
      writeUnlock();
    }
  }

  /**
   * Remove a block to the file.
   */
  boolean removeBlock(String path, INodeFileUnderConstruction fileNode, 
                      Block block) throws IOException {
    waitForReady();

    writeLock();
    try {
      unprotectedRemoveBlock(path, fileNode, block);
      // write modified block locations to log
      fsImage.getEditLog().logOpenFile(path, fileNode);
    } finally {
      writeUnlock();
    }
    return true;
  }

  void unprotectedRemoveBlock(String path, INodeFileUnderConstruction fileNode, 
      Block block) throws IOException {
    // modify file-> block and blocksMap
    fileNode.removeLastBlock(block);
    getBlockManager().removeBlockFromMap(block);

    if(NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.removeBlock: "
          +path+" with "+block
          +" block is removed from the file system");
    }

    // update space consumed
    INode[] pathINodes = getExistingPathINodes(path);
    updateCount(pathINodes, pathINodes.length-1, 0,
        -fileNode.getPreferredBlockSize()*fileNode.getReplication(), true);
  }

  /**
   * @see #unprotectedRenameTo(String, String, long)
   * @deprecated Use {@link #renameTo(String, String, Rename...)} instead.
   */
  @Deprecated
  boolean renameTo(String src, String dst) 
      throws QuotaExceededException, UnresolvedLinkException, 
      FileAlreadyExistsException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.renameTo: "
          +src+" to "+dst);
    }
    waitForReady();
    long now = now();
    writeLock();
    try {
      if (!unprotectedRenameTo(src, dst, now))
        return false;
    } finally {
      writeUnlock();
    }
    fsImage.getEditLog().logRename(src, dst, now);
    return true;
  }

  /**
   * @see #unprotectedRenameTo(String, String, long, Options.Rename...)
   */
  void renameTo(String src, String dst, Options.Rename... options)
      throws FileAlreadyExistsException, FileNotFoundException,
      ParentNotDirectoryException, QuotaExceededException,
      UnresolvedLinkException, IOException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.renameTo: " + src
          + " to " + dst);
    }
    waitForReady();
    long now = now();
    writeLock();
    try {
      if (unprotectedRenameTo(src, dst, now, options)) {
        incrDeletedFileCount(1);
      }
    } finally {
      writeUnlock();
    }
    fsImage.getEditLog().logRename(src, dst, now, options);
  }

  /**
   * Change a path name
   * 
   * @param src source path
   * @param dst destination path
   * @return true if rename succeeds; false otherwise
   * @throws QuotaExceededException if the operation violates any quota limit
   * @throws FileAlreadyExistsException if the src is a symlink that points to dst
   * @deprecated See {@link #renameTo(String, String)}
   */
  @Deprecated
  boolean unprotectedRenameTo(String src, String dst, long timestamp)
    throws QuotaExceededException, UnresolvedLinkException, 
    FileAlreadyExistsException {
    assert hasWriteLock();
    INode[] srcInodes = rootDir.getExistingPathINodes(src, false);
    INode srcInode = srcInodes[srcInodes.length-1];
    
    // check the validation of the source
    if (srcInode == null) {
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + "failed to rename " + src + " to " + dst
          + " because source does not exist");
      return false;
    } 
    if (srcInodes.length == 1) {
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          +"failed to rename "+src+" to "+dst+ " because source is the root");
      return false;
    }
    if (isDir(dst)) {
      dst += Path.SEPARATOR + new Path(src).getName();
    }
    
    // check the validity of the destination
    if (dst.equals(src)) {
      return true;
    }
    if (srcInode.isLink() && 
        dst.equals(((INodeSymlink)srcInode).getLinkValue())) {
      throw new FileAlreadyExistsException(
          "Cannot rename symlink "+src+" to its target "+dst);
    }
    
    // dst cannot be directory or a file under src
    if (dst.startsWith(src) && 
        dst.charAt(src.length()) == Path.SEPARATOR_CHAR) {
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + "failed to rename " + src + " to " + dst
          + " because destination starts with src");
      return false;
    }
    
    byte[][] dstComponents = INode.getPathComponents(dst);
    INode[] dstInodes = new INode[dstComponents.length];
    rootDir.getExistingPathINodes(dstComponents, dstInodes, false);
    if (dstInodes[dstInodes.length-1] != null) {
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
                                   +"failed to rename "+src+" to "+dst+ 
                                   " because destination exists");
      return false;
    }
    if (dstInodes[dstInodes.length-2] == null) {
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          +"failed to rename "+src+" to "+dst+ 
          " because destination's parent does not exist");
      return false;
    }
    
    // Ensure dst has quota to accommodate rename
    verifyQuotaForRename(srcInodes,dstInodes);
    
    INode dstChild = null;
    INode srcChild = null;
    String srcChildName = null;
    try {
      // remove src
      srcChild = removeChild(srcInodes, srcInodes.length-1);
      if (srcChild == null) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + "failed to rename " + src + " to " + dst
            + " because the source can not be removed");
        return false;
      }
      srcChildName = srcChild.getLocalName();
      srcChild.setLocalName(dstComponents[dstInodes.length-1]);
      
      // add src to the destination
      dstChild = addChildNoQuotaCheck(dstInodes, dstInodes.length - 1,
          srcChild, UNKNOWN_DISK_SPACE);
      if (dstChild != null) {
        srcChild = null;
        if (NameNode.stateChangeLog.isDebugEnabled()) {
          NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedRenameTo: " 
              + src + " is renamed to " + dst);
        }
        // update modification time of dst and the parent of src
        srcInodes[srcInodes.length-2].setModificationTime(timestamp);
        dstInodes[dstInodes.length-2].setModificationTime(timestamp);
        return true;
      }
    } finally {
      if (dstChild == null && srcChild != null) {
        // put it back
        srcChild.setLocalName(srcChildName);
        addChildNoQuotaCheck(srcInodes, srcInodes.length - 1, srcChild, 
            UNKNOWN_DISK_SPACE);
      }
    }
    NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
        +"failed to rename "+src+" to "+dst);
    return false;
  }

  /**
   * Rename src to dst.
   * See {@link DistributedFileSystem#rename(Path, Path, Options.Rename...)}
   * for details related to rename semantics and exceptions.
   * 
   * @param src source path
   * @param dst destination path
   * @param timestamp modification time
   * @param options Rename options
   */
  boolean unprotectedRenameTo(String src, String dst, long timestamp,
      Options.Rename... options) throws FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException,
      QuotaExceededException, UnresolvedLinkException, IOException {
    assert hasWriteLock();
    boolean overwrite = false;
    if (null != options) {
      for (Rename option : options) {
        if (option == Rename.OVERWRITE) {
          overwrite = true;
        }
      }
    }
    String error = null;
    final INode[] srcInodes = rootDir.getExistingPathINodes(src, false);
    final INode srcInode = srcInodes[srcInodes.length - 1];
    // validate source
    if (srcInode == null) {
      error = "rename source " + src + " is not found.";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new FileNotFoundException(error);
    }
    if (srcInodes.length == 1) {
      error = "rename source cannot be the root";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new IOException(error);
    }

    // validate the destination
    if (dst.equals(src)) {
      throw new FileAlreadyExistsException(
          "The source "+src+" and destination "+dst+" are the same");
    }
    if (srcInode.isLink() && 
        dst.equals(((INodeSymlink)srcInode).getLinkValue())) {
      throw new FileAlreadyExistsException(
          "Cannot rename symlink "+src+" to its target "+dst);
    }
    // dst cannot be a directory or a file under src
    if (dst.startsWith(src) && 
        dst.charAt(src.length()) == Path.SEPARATOR_CHAR) {
      error = "Rename destination " + dst
          + " is a directory or file under source " + src;
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new IOException(error);
    }
    final byte[][] dstComponents = INode.getPathComponents(dst);
    final INode[] dstInodes = new INode[dstComponents.length];
    rootDir.getExistingPathINodes(dstComponents, dstInodes, false);
    INode dstInode = dstInodes[dstInodes.length - 1];
    if (dstInodes.length == 1) {
      error = "rename destination cannot be the root";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new IOException(error);
    }
    if (dstInode != null) { // Destination exists
      // It's OK to rename a file to a symlink and vice versa
      if (dstInode.isDirectory() != srcInode.isDirectory()) {
        error = "Source " + src + " and destination " + dst
            + " must both be directories";
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + error);
        throw new IOException(error);
      }
      if (!overwrite) { // If destination exists, overwrite flag must be true
        error = "rename destination " + dst + " already exists";
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + error);
        throw new FileAlreadyExistsException(error);
      }
      List<INode> children = dstInode.isDirectory() ? 
          ((INodeDirectory) dstInode).getChildrenRaw() : null;
      if (children != null && children.size() != 0) {
        error = "rename cannot overwrite non empty destination directory "
            + dst;
        NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
            + error);
        throw new IOException(error);
      }
    }
    if (dstInodes[dstInodes.length - 2] == null) {
      error = "rename destination parent " + dst + " not found.";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new FileNotFoundException(error);
    }
    if (!dstInodes[dstInodes.length - 2].isDirectory()) {
      error = "rename destination parent " + dst + " is a file.";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new ParentNotDirectoryException(error);
    }

    // Ensure dst has quota to accommodate rename
    verifyQuotaForRename(srcInodes, dstInodes);
    INode removedSrc = removeChild(srcInodes, srcInodes.length - 1);
    if (removedSrc == null) {
      error = "Failed to rename " + src + " to " + dst
          + " because the source can not be removed";
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
          + error);
      throw new IOException(error);
    }
    final String srcChildName = removedSrc.getLocalName();
    String dstChildName = null;
    INode removedDst = null;
    try {
      if (dstInode != null) { // dst exists remove it
        removedDst = removeChild(dstInodes, dstInodes.length - 1);
        dstChildName = removedDst.getLocalName();
      }

      INode dstChild = null;
      removedSrc.setLocalName(dstComponents[dstInodes.length - 1]);
      // add src as dst to complete rename
      dstChild = addChildNoQuotaCheck(dstInodes, dstInodes.length - 1,
          removedSrc, UNKNOWN_DISK_SPACE);

      int filesDeleted = 0;
      if (dstChild != null) {
        removedSrc = null;
        if (NameNode.stateChangeLog.isDebugEnabled()) {
          NameNode.stateChangeLog.debug(
              "DIR* FSDirectory.unprotectedRenameTo: " + src
              + " is renamed to " + dst);
        }
        srcInodes[srcInodes.length - 2].setModificationTime(timestamp);
        dstInodes[dstInodes.length - 2].setModificationTime(timestamp);

        // Collect the blocks and remove the lease for previous dst
        if (removedDst != null) {
          INode rmdst = removedDst;
          removedDst = null;
          List<Block> collectedBlocks = new ArrayList<Block>();
          filesDeleted = rmdst.collectSubtreeBlocksAndClear(collectedBlocks);
          getFSNamesystem().removePathAndBlocks(src, collectedBlocks);
        }
        return filesDeleted >0;
      }
    } finally {
      if (removedSrc != null) {
        // Rename failed - restore src
        removedSrc.setLocalName(srcChildName);
        addChildNoQuotaCheck(srcInodes, srcInodes.length - 1, removedSrc, 
            UNKNOWN_DISK_SPACE);
      }
      if (removedDst != null) {
        // Rename failed - restore dst
        removedDst.setLocalName(dstChildName);
        addChildNoQuotaCheck(dstInodes, dstInodes.length - 1, removedDst, 
            UNKNOWN_DISK_SPACE);
      }
    }
    NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedRenameTo: "
        + "failed to rename " + src + " to " + dst);
    throw new IOException("rename from " + src + " to " + dst + " failed.");
  }

  /**
   * Set file replication
   * 
   * @param src file name
   * @param replication new replication
   * @param oldReplication old replication - output parameter
   * @return array of file blocks
   * @throws QuotaExceededException
   */
  Block[] setReplication(String src, short replication, short[] oldReplication)
      throws QuotaExceededException, UnresolvedLinkException {
    waitForReady();
    Block[] fileBlocks = null;
    writeLock();
    try {
      fileBlocks = unprotectedSetReplication(src, replication, oldReplication);
      if (fileBlocks != null)  // log replication change
        fsImage.getEditLog().logSetReplication(src, replication);
      return fileBlocks;
    } finally {
      writeUnlock();
    }
  }

  Block[] unprotectedSetReplication(String src, 
                                    short replication,
                                    short[] oldReplication
                                    ) throws QuotaExceededException, 
                                    UnresolvedLinkException {
    assert hasWriteLock();

    INode[] inodes = rootDir.getExistingPathINodes(src, true);
    INode inode = inodes[inodes.length - 1];
    if (inode == null) {
      return null;
    }
    assert !inode.isLink();
    if (inode.isDirectory()) {
      return null;
    }
    INodeFile fileNode = (INodeFile)inode;
    final short oldRepl = fileNode.getReplication();

    // check disk quota
    long dsDelta = (replication - oldRepl) * (fileNode.diskspaceConsumed()/oldRepl);
    updateCount(inodes, inodes.length-1, 0, dsDelta, true);

    fileNode.setReplication(replication);

    if (oldReplication != null) {
      oldReplication[0] = oldRepl;
    }
    return fileNode.getBlocks();
  }

  /**
   * Get the blocksize of a file
   * @param filename the filename
   * @return the number of bytes 
   */
  long getPreferredBlockSize(String filename) throws UnresolvedLinkException,
      FileNotFoundException, IOException {
    readLock();
    try {
      INode inode = rootDir.getNode(filename, false);
      if (inode == null) {
        throw new FileNotFoundException("File does not exist: " + filename);
      }
      if (inode.isDirectory() || inode.isLink()) {
        throw new IOException("Getting block size of non-file: "+ filename); 
      }
      return ((INodeFile)inode).getPreferredBlockSize();
    } finally {
      readUnlock();
    }
  }

  boolean exists(String src) throws UnresolvedLinkException {
    src = normalizePath(src);
    readLock();
    try {
      INode inode = rootDir.getNode(src, false);
      if (inode == null) {
         return false;
      }
      return inode.isDirectory() || inode.isLink() 
        ? true 
        : ((INodeFile)inode).getBlocks() != null;
    } finally {
      readUnlock();
    }
  }

  void setPermission(String src, FsPermission permission)
      throws FileNotFoundException, UnresolvedLinkException {
    writeLock();
    try {
      unprotectedSetPermission(src, permission);
    } finally {
      writeUnlock();
    }
    fsImage.getEditLog().logSetPermissions(src, permission);
  }

  void unprotectedSetPermission(String src, FsPermission permissions) 
      throws FileNotFoundException, UnresolvedLinkException {
    assert hasWriteLock();
    INode inode = rootDir.getNode(src, true);
    if (inode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    inode.setPermission(permissions);
  }

  void setOwner(String src, String username, String groupname)
      throws FileNotFoundException, UnresolvedLinkException {
    writeLock();
    try {
      unprotectedSetOwner(src, username, groupname);
    } finally {
      writeUnlock();
    }
    fsImage.getEditLog().logSetOwner(src, username, groupname);
  }

  void unprotectedSetOwner(String src, String username, String groupname) 
      throws FileNotFoundException, UnresolvedLinkException {
    assert hasWriteLock();
    INode inode = rootDir.getNode(src, true);
    if (inode == null) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    if (username != null) {
      inode.setUser(username);
    }
    if (groupname != null) {
      inode.setGroup(groupname);
    }
  }

  /**
   * Concat all the blocks from srcs to trg and delete the srcs files
   */
  public void concat(String target, String [] srcs) 
      throws UnresolvedLinkException {
    writeLock();
    try {
      // actual move
      waitForReady();
      long timestamp = now();
      unprotectedConcat(target, srcs, timestamp);
      // do the commit
      fsImage.getEditLog().logConcat(target, srcs, timestamp);
    } finally {
      writeUnlock();
    }
  }
  

  
  /**
   * Concat all the blocks from srcs to trg and delete the srcs files
   * @param target target file to move the blocks to
   * @param srcs list of file to move the blocks from
   * Must be public because also called from EditLogs
   * NOTE: - it does not update quota (not needed for concat)
   */
  public void unprotectedConcat(String target, String [] srcs, long timestamp) 
      throws UnresolvedLinkException {
    assert hasWriteLock();
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSNamesystem.concat to "+target);
    }
    // do the move
    
    INode [] trgINodes =  getExistingPathINodes(target);
    INodeFile trgInode = (INodeFile) trgINodes[trgINodes.length-1];
    INodeDirectory trgParent = (INodeDirectory)trgINodes[trgINodes.length-2];
    
    INodeFile [] allSrcInodes = new INodeFile[srcs.length];
    int i = 0;
    int totalBlocks = 0;
    for(String src : srcs) {
      INodeFile srcInode = getFileINode(src);
      allSrcInodes[i++] = srcInode;
      totalBlocks += srcInode.blocks.length;  
    }
    trgInode.appendBlocks(allSrcInodes, totalBlocks); // copy the blocks
    
    // since we are in the same dir - we can use same parent to remove files
    int count = 0;
    for(INodeFile nodeToRemove: allSrcInodes) {
      if(nodeToRemove == null) continue;
      
      nodeToRemove.blocks = null;
      trgParent.removeChild(nodeToRemove);
      count++;
    }
    
    trgInode.setModificationTimeForce(timestamp);
    trgParent.setModificationTime(timestamp);
    // update quota on the parent directory ('count' files removed, 0 space)
    unprotectedUpdateCount(trgINodes, trgINodes.length-1, - count, 0);
  }

  /**
   * Delete the target directory and collect the blocks under it
   * 
   * @param src Path of a directory to delete
   * @param collectedBlocks Blocks under the deleted directory
   * @return true on successful deletion; else false
   */
  boolean delete(String src, List<Block>collectedBlocks) 
    throws UnresolvedLinkException {
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.delete: " + src);
    }
    waitForReady();
    long now = now();
    int filesRemoved;
    writeLock();
    try {
      filesRemoved = unprotectedDelete(src, collectedBlocks, now);
    } finally {
      writeUnlock();
    }
    if (filesRemoved <= 0) {
      return false;
    }
    incrDeletedFileCount(filesRemoved);
    // Blocks will be deleted later by the caller of this method
    getFSNamesystem().removePathAndBlocks(src, null);
    fsImage.getEditLog().logDelete(src, now);
    return true;
  }
  
  /** Return if a directory is empty or not **/
  boolean isDirEmpty(String src) throws UnresolvedLinkException {
    boolean dirNotEmpty = true;
    if (!isDir(src)) {
      return true;
    }
    readLock();
    try {
      INode targetNode = rootDir.getNode(src, false);
      assert targetNode != null : "should be taken care in isDir() above";
      if (((INodeDirectory)targetNode).getChildren().size() != 0) {
        dirNotEmpty = false;
      }
    } finally {
      readUnlock();
    }
    return dirNotEmpty;
  }

  boolean isEmpty() {
    try {
      return isDirEmpty("/");
    } catch (UnresolvedLinkException e) {
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("/ cannot be a symlink");
      }
      assert false : "/ cannot be a symlink";
      return true;
    }
  }

  /**
   * Delete a path from the name space
   * Update the count at each ancestor directory with quota
   * <br>
   * Note: This is to be used by {@link FSEditLog} only.
   * <br>
   * @param src a string representation of a path to an inode
   * @param mtime the time the inode is removed
   */ 
  void unprotectedDelete(String src, long mtime) 
    throws UnresolvedLinkException {
    assert hasWriteLock();
    List<Block> collectedBlocks = new ArrayList<Block>();
    int filesRemoved = unprotectedDelete(src, collectedBlocks, mtime);
    if (filesRemoved > 0) {
      getFSNamesystem().removePathAndBlocks(src, collectedBlocks);
    }
  }
  
  /**
   * Delete a path from the name space
   * Update the count at each ancestor directory with quota
   * @param src a string representation of a path to an inode
   * @param collectedBlocks blocks collected from the deleted path
   * @param mtime the time the inode is removed
   * @return the number of inodes deleted; 0 if no inodes are deleted.
   */ 
  int unprotectedDelete(String src, List<Block> collectedBlocks, 
      long mtime) throws UnresolvedLinkException {
    assert hasWriteLock();
    src = normalizePath(src);

    INode[] inodes =  rootDir.getExistingPathINodes(src, false);
    INode targetNode = inodes[inodes.length-1];

    if (targetNode == null) { // non-existent src
      if(NameNode.stateChangeLog.isDebugEnabled()) {
        NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
            +"failed to remove "+src+" because it does not exist");
      }
      return 0;
    }
    if (inodes.length == 1) { // src is the root
      NameNode.stateChangeLog.warn("DIR* FSDirectory.unprotectedDelete: " +
          "failed to remove " + src +
          " because the root is not allowed to be deleted");
      return 0;
    }
    int pos = inodes.length - 1;
    // Remove the node from the namespace
    targetNode = removeChild(inodes, pos);
    if (targetNode == null) {
      return 0;
    }
    // set the parent's modification time
    inodes[pos-1].setModificationTime(mtime);
    int filesRemoved = targetNode.collectSubtreeBlocksAndClear(collectedBlocks);
    if (NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.unprotectedDelete: "
          +src+" is removed");
    }
    return filesRemoved;
  }

  /**
   * Replaces the specified inode with the specified one.
   */
  public void replaceNode(String path, INodeFile oldnode, INodeFile newnode)
      throws IOException, UnresolvedLinkException {    
    writeLock();
    try {
      //
      // Remove the node from the namespace 
      //
      if (!oldnode.removeNode()) {
        NameNode.stateChangeLog.warn("DIR* FSDirectory.replaceNode: " +
                                     "failed to remove " + path);
        throw new IOException("FSDirectory.replaceNode: " +
                              "failed to remove " + path);
      } 
      
      /* Currently oldnode and newnode are assumed to contain the same
       * blocks. Otherwise, blocks need to be removed from the blocksMap.
       */
      rootDir.addNode(path, newnode); 

      int index = 0;
      for (BlockInfo b : newnode.getBlocks()) {
        BlockInfo info = getBlockManager().addINode(b, newnode);
        newnode.setBlock(index, info); // inode refers to the block in BlocksMap
        index++;
      }
    } finally {
      writeUnlock();
    }
  }

  /**
   * Get a partial listing of the indicated directory
   *
   * @param src the directory name
   * @param startAfter the name to start listing after
   * @param needLocation if block locations are returned
   * @return a partial listing starting after startAfter
   */
  DirectoryListing getListing(String src, byte[] startAfter,
      boolean needLocation) throws UnresolvedLinkException, IOException {
    String srcs = normalizePath(src);

    readLock();
    try {
      INode targetNode = rootDir.getNode(srcs, true);
      if (targetNode == null)
        return null;
      
      if (!targetNode.isDirectory()) {
        return new DirectoryListing(
            new HdfsFileStatus[]{createFileStatus(HdfsFileStatus.EMPTY_NAME,
                targetNode, needLocation)}, 0);
      }
      INodeDirectory dirInode = (INodeDirectory)targetNode;
      List<INode> contents = dirInode.getChildren();
      int startChild = dirInode.nextChild(startAfter);
      int totalNumChildren = contents.size();
      int numOfListing = Math.min(totalNumChildren-startChild, this.lsLimit);
      HdfsFileStatus listing[] = new HdfsFileStatus[numOfListing];
      for (int i=0; i<numOfListing; i++) {
        INode cur = contents.get(startChild+i);
        listing[i] = createFileStatus(cur.name, cur, needLocation);
      }
      return new DirectoryListing(
          listing, totalNumChildren-startChild-numOfListing);
    } finally {
      readUnlock();
    }
  }

  /** Get the file info for a specific file.
   * @param src The string representation of the path to the file
   * @param resolveLink whether to throw UnresolvedLinkException 
   * @return object containing information regarding the file
   *         or null if file not found
   */
  HdfsFileStatus getFileInfo(String src, boolean resolveLink) 
      throws UnresolvedLinkException {
    String srcs = normalizePath(src);
    readLock();
    try {
      INode targetNode = rootDir.getNode(srcs, resolveLink);
      if (targetNode == null) {
        return null;
      }
      else {
        return createFileStatus(HdfsFileStatus.EMPTY_NAME, targetNode);
      }
    } finally {
      readUnlock();
    }
  }

  /**
   * Get the blocks associated with the file.
   */
  Block[] getFileBlocks(String src) throws UnresolvedLinkException {
    waitForReady();
    readLock();
    try {
      INode targetNode = rootDir.getNode(src, false);
      if (targetNode == null)
        return null;
      if (targetNode.isDirectory())
        return null;
      if (targetNode.isLink()) 
        return null;
      return ((INodeFile)targetNode).getBlocks();
    } finally {
      readUnlock();
    }
  }

  /**
   * Get {@link INode} associated with the file.
   */
  INodeFile getFileINode(String src) throws UnresolvedLinkException {
    INode inode = getINode(src);
    if (inode == null || inode.isDirectory())
      return null;
    assert !inode.isLink();
    return (INodeFile) inode;
  }
  
  /**
   * Get {@link INode} associated with the file / directory.
   */
  INode getINode(String src) throws UnresolvedLinkException {
    readLock();
    try {
      INode iNode = rootDir.getNode(src, true);
      return iNode;
    } finally {
      readUnlock();
    }
  }

  /**
   * Retrieve the existing INodes along the given path.
   * 
   * @param path the path to explore
   * @return INodes array containing the existing INodes in the order they
   *         appear when following the path from the root INode to the
   *         deepest INodes. The array size will be the number of expected
   *         components in the path, and non existing components will be
   *         filled with null
   *         
   * @see INodeDirectory#getExistingPathINodes(byte[][], INode[])
   */
  INode[] getExistingPathINodes(String path) 
    throws UnresolvedLinkException {
    readLock();
    try {
      return rootDir.getExistingPathINodes(path, true);
    } finally {
      readUnlock();
    }
  }
  
  /**
   * Get the parent node of path.
   * 
   * @param path the path to explore
   * @return its parent node
   */
  INodeDirectory getParent(byte[][] path) 
    throws FileNotFoundException, UnresolvedLinkException {
    readLock();
    try {
      return rootDir.getParent(path);
    } finally {
      readUnlock();
    }
  }
  
  /** 
   * Check whether the filepath could be created
   */
  boolean isValidToCreate(String src) throws UnresolvedLinkException {
    String srcs = normalizePath(src);
    readLock();
    try {
      if (srcs.startsWith("/") && 
          !srcs.endsWith("/") && 
          rootDir.getNode(srcs, false) == null) {
        return true;
      } else {
        return false;
      }
    } finally {
      readUnlock();
    }
  }

  /**
   * Check whether the path specifies a directory
   */
  boolean isDir(String src) throws UnresolvedLinkException {
    src = normalizePath(src);
    readLock();
    try {
      INode node = rootDir.getNode(src, false);
      return node != null && node.isDirectory();
    } finally {
      readUnlock();
    }
  }

  /** Updates namespace and diskspace consumed for all
   * directories until the parent directory of file represented by path.
   * 
   * @param path path for the file.
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @throws QuotaExceededException if the new count violates any quota limit
   * @throws FileNotFound if path does not exist.
   */
  void updateSpaceConsumed(String path, long nsDelta, long dsDelta)
                                         throws QuotaExceededException,
                                                FileNotFoundException,
                                                UnresolvedLinkException {
    writeLock();
    try {
      INode[] inodes = rootDir.getExistingPathINodes(path, false);
      int len = inodes.length;
      if (inodes[len - 1] == null) {
        throw new FileNotFoundException(path + 
                                        " does not exist under rootDir.");
      }
      updateCount(inodes, len-1, nsDelta, dsDelta, true);
    } finally {
      writeUnlock();
    }
  }
  
  /** update count of each inode with quota
   * 
   * @param inodes an array of inodes on a path
   * @param numOfINodes the number of inodes to update starting from index 0
   * @param nsDelta the delta change of namespace
   * @param dsDelta the delta change of diskspace
   * @param checkQuota if true then check if quota is exceeded
   * @throws QuotaExceededException if the new count violates any quota limit
   */
  private void updateCount(INode[] inodes, int numOfINodes, 
                           long nsDelta, long dsDelta, boolean checkQuota)
                           throws QuotaExceededException {
    assert hasWriteLock();
    if (!ready) {
      //still initializing. do not check or update quotas.
      return;
    }
    if (numOfINodes>inodes.length) {
      numOfINodes = inodes.length;
    }
    if (checkQuota) {
      verifyQuota(inodes, numOfINodes, nsDelta, dsDelta, null);
    }
    for(int i = 0; i < numOfINodes; i++) {
      if (inodes[i].isQuotaSet()) { // a directory with quota
        INodeDirectoryWithQuota node =(INodeDirectoryWithQuota)inodes[i]; 
        node.updateNumItemsInTree(nsDelta, dsDelta);
      }
    }
  }
  
  /** 
   * update quota of each inode and check to see if quota is exceeded. 
   * See {@link #updateCount(INode[], int, long, long, boolean)}
   */ 
  private void updateCountNoQuotaCheck(INode[] inodes, int numOfINodes, 
                           long nsDelta, long dsDelta) {
    assert hasWriteLock();
    try {
      updateCount(inodes, numOfINodes, nsDelta, dsDelta, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.warn("FSDirectory.updateCountNoQuotaCheck - unexpected ", e);
    }
  }
  
  /**
   * updates quota without verification
   * callers responsibility is to make sure quota is not exceeded
   * @param inodes
   * @param numOfINodes
   * @param nsDelta
   * @param dsDelta
   */
   void unprotectedUpdateCount(INode[] inodes, int numOfINodes, 
                                      long nsDelta, long dsDelta) {
     assert hasWriteLock();
    for(int i=0; i < numOfINodes; i++) {
      if (inodes[i].isQuotaSet()) { // a directory with quota
        INodeDirectoryWithQuota node =(INodeDirectoryWithQuota)inodes[i]; 
        node.unprotectedUpdateNumItemsInTree(nsDelta, dsDelta);
      }
    }
  }
  
  /** Return the name of the path represented by inodes at [0, pos] */
  private static String getFullPathName(INode[] inodes, int pos) {
    StringBuilder fullPathName = new StringBuilder();
    if (inodes[0].isRoot()) {
      if (pos == 0) return Path.SEPARATOR;
    } else {
      fullPathName.append(inodes[0].getLocalName());
    }
    
    for (int i=1; i<=pos; i++) {
      fullPathName.append(Path.SEPARATOR_CHAR).append(inodes[i].getLocalName());
    }
    return fullPathName.toString();
  }

  /** Return the full path name of the specified inode */
  static String getFullPathName(INode inode) {
    // calculate the depth of this inode from root
    int depth = 0;
    for (INode i = inode; i != null; i = i.parent) {
      depth++;
    }
    INode[] inodes = new INode[depth];

    // fill up the inodes in the path from this inode to root
    for (int i = 0; i < depth; i++) {
      inodes[depth-i-1] = inode;
      inode = inode.parent;
    }
    return getFullPathName(inodes, depth-1);
  }
  
  /**
   * Create a directory 
   * If ancestor directories do not exist, automatically create them.

   * @param src string representation of the path to the directory
   * @param permissions the permission of the directory
   * @param isAutocreate if the permission of the directory should inherit
   *                          from its parent or not. u+wx is implicitly added to
   *                          the automatically created directories, and to the
   *                          given directory if inheritPermission is true
   * @param now creation time
   * @return true if the operation succeeds false otherwise
   * @throws FileNotFoundException if an ancestor or itself is a file
   * @throws QuotaExceededException if directory creation violates 
   *                                any quota limit
   * @throws UnresolvedLinkException if a symlink is encountered in src.                      
   */
  boolean mkdirs(String src, PermissionStatus permissions,
      boolean inheritPermission, long now)
      throws FileAlreadyExistsException, QuotaExceededException, 
             UnresolvedLinkException {
    src = normalizePath(src);
    String[] names = INode.getPathNames(src);
    byte[][] components = INode.getPathComponents(names);
    INode[] inodes = new INode[components.length];
    final int lastInodeIndex = inodes.length - 1;

    writeLock();
    try {
      rootDir.getExistingPathINodes(components, inodes, false);

      // find the index of the first null in inodes[]
      StringBuilder pathbuilder = new StringBuilder();
      int i = 1;
      for(; i < inodes.length && inodes[i] != null; i++) {
        pathbuilder.append(Path.SEPARATOR + names[i]);
        if (!inodes[i].isDirectory()) {
          throw new FileAlreadyExistsException("Parent path is not a directory: "
              + pathbuilder+ " "+inodes[i].getLocalName());
        }
      }

      // default to creating parent dirs with the given perms
      PermissionStatus parentPermissions = permissions;

      // if not inheriting and it's the last inode, there's no use in
      // computing perms that won't be used
      if (inheritPermission || (i < lastInodeIndex)) {
        // if inheriting (ie. creating a file or symlink), use the parent dir,
        // else the supplied permissions
        // NOTE: the permissions of the auto-created directories violate posix
        FsPermission parentFsPerm = inheritPermission
            ? inodes[i-1].getFsPermission() : permissions.getPermission();
        
        // ensure that the permissions allow user write+execute
        if (!parentFsPerm.getUserAction().implies(FsAction.WRITE_EXECUTE)) {
          parentFsPerm = new FsPermission(
              parentFsPerm.getUserAction().or(FsAction.WRITE_EXECUTE),
              parentFsPerm.getGroupAction(),
              parentFsPerm.getOtherAction()
          );
        }
        
        if (!parentPermissions.getPermission().equals(parentFsPerm)) {
          parentPermissions = new PermissionStatus(
              parentPermissions.getUserName(),
              parentPermissions.getGroupName(),
              parentFsPerm
          );
          // when inheriting, use same perms for entire path
          if (inheritPermission) permissions = parentPermissions;
        }
      }
      
      // create directories beginning from the first null index
      for(; i < inodes.length; i++) {
        pathbuilder.append(Path.SEPARATOR + names[i]);
        String cur = pathbuilder.toString();
        unprotectedMkdir(inodes, i, components[i],
            (i < lastInodeIndex) ? parentPermissions : permissions, now);
        if (inodes[i] == null) {
          return false;
        }
        // Directory creation also count towards FilesCreated
        // to match count of FilesDeleted metric.
        if (getFSNamesystem() != null)
          NameNode.getNameNodeMetrics().incrFilesCreated();
        fsImage.getEditLog().logMkDir(cur, inodes[i]);
        if(NameNode.stateChangeLog.isDebugEnabled()) {
          NameNode.stateChangeLog.debug(
              "DIR* FSDirectory.mkdirs: created directory " + cur);
        }
      }
    } finally {
      writeUnlock();
    }
    return true;
  }

  /**
   */
  INode unprotectedMkdir(String src, PermissionStatus permissions,
                          long timestamp) throws QuotaExceededException,
                          UnresolvedLinkException {
    assert hasWriteLock();
    byte[][] components = INode.getPathComponents(src);
    INode[] inodes = new INode[components.length];

    rootDir.getExistingPathINodes(components, inodes, false);
    unprotectedMkdir(inodes, inodes.length-1, components[inodes.length-1],
        permissions, timestamp);
    return inodes[inodes.length-1];
  }

  /** create a directory at index pos.
   * The parent path to the directory is at [0, pos-1].
   * All ancestors exist. Newly created one stored at index pos.
   */
  private void unprotectedMkdir(INode[] inodes, int pos,
      byte[] name, PermissionStatus permission,
      long timestamp) throws QuotaExceededException {
    assert hasWriteLock();
    inodes[pos] = addChild(inodes, pos, 
        new INodeDirectory(name, permission, timestamp),
        -1);
  }
  
  /** Add a node child to the namespace. The full path name of the node is src.
   * childDiskspace should be -1, if unknown. 
   * QuotaExceededException is thrown if it violates quota limit */
  private <T extends INode> T addNode(String src, T child, 
        long childDiskspace) 
  throws QuotaExceededException, UnresolvedLinkException {
    byte[][] components = INode.getPathComponents(src);
    byte[] path = components[components.length-1];
    child.setLocalName(path);
    cacheName(child);
    INode[] inodes = new INode[components.length];
    writeLock();
    try {
      rootDir.getExistingPathINodes(components, inodes, false);
      return addChild(inodes, inodes.length-1, child, childDiskspace);
    } finally {
      writeUnlock();
    }
  }

  /**
   * Verify quota for adding or moving a new INode with required 
   * namespace and diskspace to a given position.
   *  
   * @param inodes INodes corresponding to a path
   * @param pos position where a new INode will be added
   * @param nsDelta needed namespace
   * @param dsDelta needed diskspace
   * @param commonAncestor Last node in inodes array that is a common ancestor
   *          for a INode that is being moved from one location to the other.
   *          Pass null if a node is not being moved.
   * @throws QuotaExceededException if quota limit is exceeded.
   */
  private void verifyQuota(INode[] inodes, int pos, long nsDelta, long dsDelta,
      INode commonAncestor) throws QuotaExceededException {
    if (!ready) {
      // Do not check quota if edits log is still being processed
      return;
    }
    if (nsDelta <= 0 && dsDelta <= 0) {
      // if quota is being freed or not being consumed
      return;
    }
    if (pos>inodes.length) {
      pos = inodes.length;
    }
    int i = pos - 1;
    try {
      // check existing components in the path  
      for(; i >= 0; i--) {
        if (commonAncestor == inodes[i]) {
          // Moving an existing node. Stop checking for quota when common
          // ancestor is reached
          return;
        }
        if (inodes[i].isQuotaSet()) { // a directory with quota
          INodeDirectoryWithQuota node =(INodeDirectoryWithQuota)inodes[i]; 
          node.verifyQuota(nsDelta, dsDelta);
        }
      }
    } catch (QuotaExceededException e) {
      e.setPathName(getFullPathName(inodes, i));
      throw e;
    }
  }
  
  /**
   * Verify quota for rename operation where srcInodes[srcInodes.length-1] moves
   * dstInodes[dstInodes.length-1]
   * 
   * @param srcInodes directory from where node is being moved.
   * @param dstInodes directory to where node is moved to.
   * @throws QuotaExceededException if quota limit is exceeded.
   */
  private void verifyQuotaForRename(INode[] srcInodes, INode[]dstInodes)
      throws QuotaExceededException {
    if (!ready) {
      // Do not check quota if edits log is still being processed
      return;
    }
    INode srcInode = srcInodes[srcInodes.length - 1];
    INode commonAncestor = null;
    for(int i =0;srcInodes[i] == dstInodes[i]; i++) {
      commonAncestor = srcInodes[i];
    }
    INode.DirCounts srcCounts = new INode.DirCounts();
    srcInode.spaceConsumedInTree(srcCounts);
    long nsDelta = srcCounts.getNsCount();
    long dsDelta = srcCounts.getDsCount();
    
    // Reduce the required quota by dst that is being removed
    INode dstInode = dstInodes[dstInodes.length - 1];
    if (dstInode != null) {
      INode.DirCounts dstCounts = new INode.DirCounts();
      dstInode.spaceConsumedInTree(dstCounts);
      nsDelta -= dstCounts.getNsCount();
      dsDelta -= dstCounts.getDsCount();
    }
    verifyQuota(dstInodes, dstInodes.length - 1, nsDelta, dsDelta,
        commonAncestor);
  }
  
  /**
   * Verify that filesystem limit constraints are not violated
   * @throws PathComponentTooLongException child's name is too long
   * @throws MaxDirectoryItemsExceededException items per directory is exceeded
   */
  protected <T extends INode> void verifyFsLimits(INode[] pathComponents,
      int pos, T child) throws FSLimitException {
    boolean includeChildName = false;
    try {
      if (maxComponentLength != 0) {
        int length = child.getLocalName().length();
        if (length > maxComponentLength) {
          includeChildName = true;
          throw new PathComponentTooLongException(maxComponentLength, length);
        }
      }
      if (maxDirItems != 0) {
        INodeDirectory parent = (INodeDirectory)pathComponents[pos-1];
        int count = parent.getChildren().size();
        if (count >= maxDirItems) {
          throw new MaxDirectoryItemsExceededException(maxDirItems, count);
        }
      }
    } catch (FSLimitException e) {
      String badPath = getFullPathName(pathComponents, pos-1);
      if (includeChildName) {
        badPath += Path.SEPARATOR + child.getLocalName();
      }
      e.setPathName(badPath);
      // Do not throw if edits log is still being processed
      if (ready) throw(e);
      // log pre-existing paths that exceed limits
      NameNode.LOG.error("FSDirectory.verifyFsLimits - " + e.getLocalizedMessage());
    }
  }
  
  /** Add a node child to the inodes at index pos. 
   * Its ancestors are stored at [0, pos-1]. 
   * QuotaExceededException is thrown if it violates quota limit */
  private <T extends INode> T addChild(INode[] pathComponents, int pos,
      T child, long childDiskspace,
      boolean checkQuota) throws QuotaExceededException {
	// The filesystem limits are not really quotas, so this check may appear
	// odd.  It's because a rename operation deletes the src, tries to add
	// to the dest, if that fails, re-adds the src from whence it came.
	// The rename code disables the quota when it's restoring to the
	// original location becase a quota violation would cause the the item
	// to go "poof".  The fs limits must be bypassed for the same reason.
    if (checkQuota) {
      verifyFsLimits(pathComponents, pos, child);
    }
    
    INode.DirCounts counts = new INode.DirCounts();
    child.spaceConsumedInTree(counts);
    if (childDiskspace < 0) {
      childDiskspace = counts.getDsCount();
    }
    updateCount(pathComponents, pos, counts.getNsCount(), childDiskspace,
        checkQuota);
    if (pathComponents[pos-1] == null) {
      throw new NullPointerException("Panic: parent does not exist");
    }
    T addedNode = ((INodeDirectory)pathComponents[pos-1]).addChild(
        child, true);
    if (addedNode == null) {
      updateCount(pathComponents, pos, -counts.getNsCount(), 
          -childDiskspace, true);
    }
    return addedNode;
  }

  private <T extends INode> T addChild(INode[] pathComponents, int pos,
      T child, long childDiskspace)
      throws QuotaExceededException {
    return addChild(pathComponents, pos, child, childDiskspace, true);
  }
  
  private <T extends INode> T addChildNoQuotaCheck(INode[] pathComponents,
      int pos, T child, long childDiskspace) {
    T inode = null;
    try {
      inode = addChild(pathComponents, pos, child, childDiskspace, false);
    } catch (QuotaExceededException e) {
      NameNode.LOG.warn("FSDirectory.addChildNoQuotaCheck - unexpected", e); 
    }
    return inode;
  }
  
  /** Remove an inode at index pos from the namespace.
   * Its ancestors are stored at [0, pos-1].
   * Count of each ancestor with quota is also updated.
   * Return the removed node; null if the removal fails.
   */
  private INode removeChild(INode[] pathComponents, int pos) {
    INode removedNode = 
      ((INodeDirectory)pathComponents[pos-1]).removeChild(pathComponents[pos]);
    if (removedNode != null) {
      INode.DirCounts counts = new INode.DirCounts();
      removedNode.spaceConsumedInTree(counts);
      updateCountNoQuotaCheck(pathComponents, pos,
                  -counts.getNsCount(), -counts.getDsCount());
    }
    return removedNode;
  }
  
  /**
   */
  String normalizePath(String src) {
    if (src.length() > 1 && src.endsWith("/")) {
      src = src.substring(0, src.length() - 1);
    }
    return src;
  }

  ContentSummary getContentSummary(String src) 
    throws FileNotFoundException, UnresolvedLinkException {
    String srcs = normalizePath(src);
    readLock();
    try {
      INode targetNode = rootDir.getNode(srcs, false);
      if (targetNode == null) {
        throw new FileNotFoundException("File does not exist: " + srcs);
      }
      else {
        return targetNode.computeContentSummary();
      }
    } finally {
      readUnlock();
    }
  }

  /** Update the count of each directory with quota in the namespace
   * A directory's count is defined as the total number inodes in the tree
   * rooted at the directory.
   * 
   * This is an update of existing state of the filesystem and does not
   * throw QuotaExceededException.
   */
  void updateCountForINodeWithQuota() {
    updateCountForINodeWithQuota(rootDir, new INode.DirCounts(), 
                                 new ArrayList<INode>(50));
  }
  
  /** 
   * Update the count of the directory if it has a quota and return the count
   * 
   * This does not throw a QuotaExceededException. This is just an update
   * of of existing state and throwing QuotaExceededException does not help
   * with fixing the state, if there is a problem.
   * 
   * @param dir the root of the tree that represents the directory
   * @param counters counters for name space and disk space
   * @param nodesInPath INodes for the each of components in the path.
   */
  private static void updateCountForINodeWithQuota(INodeDirectory dir, 
                                               INode.DirCounts counts,
                                               ArrayList<INode> nodesInPath) {
    long parentNamespace = counts.nsCount;
    long parentDiskspace = counts.dsCount;
    
    counts.nsCount = 1L;//for self. should not call node.spaceConsumedInTree()
    counts.dsCount = 0L;
    
    /* We don't need nodesInPath if we could use 'parent' field in 
     * INode. using 'parent' is not currently recommended. */
    nodesInPath.add(dir);

    for (INode child : dir.getChildren()) {
      if (child.isDirectory()) {
        updateCountForINodeWithQuota((INodeDirectory)child, 
                                     counts, nodesInPath);
      } else if (child.isLink()) {
        counts.nsCount += 1;
      } else { // reduce recursive calls
        counts.nsCount += 1;
        counts.dsCount += ((INodeFile)child).diskspaceConsumed();
      }
    }
      
    if (dir.isQuotaSet()) {
      ((INodeDirectoryWithQuota)dir).setSpaceConsumed(counts.nsCount,
                                                      counts.dsCount);

      // check if quota is violated for some reason.
      if ((dir.getNsQuota() >= 0 && counts.nsCount > dir.getNsQuota()) ||
          (dir.getDsQuota() >= 0 && counts.dsCount > dir.getDsQuota())) {

        // can only happen because of a software bug. the bug should be fixed.
        StringBuilder path = new StringBuilder(512);
        for (INode n : nodesInPath) {
          path.append('/');
          path.append(n.getLocalName());
        }
        
        NameNode.LOG.warn("Quota violation in image for " + path + 
                          " (Namespace quota : " + dir.getNsQuota() +
                          " consumed : " + counts.nsCount + ")" +
                          " (Diskspace quota : " + dir.getDsQuota() +
                          " consumed : " + counts.dsCount + ").");
      }            
    }
      
    // pop 
    nodesInPath.remove(nodesInPath.size()-1);
    
    counts.nsCount += parentNamespace;
    counts.dsCount += parentDiskspace;
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the contract.
   * Sets quota for for a directory.
   * @returns INodeDirectory if any of the quotas have changed. null other wise.
   * @throws FileNotFoundException if the path does not exist or is a file
   * @throws QuotaExceededException if the directory tree size is 
   *                                greater than the given quota
   * @throws UnresolvedLinkException if a symlink is encountered in src.
   */
  INodeDirectory unprotectedSetQuota(String src, long nsQuota, long dsQuota)
    throws FileNotFoundException, QuotaExceededException, 
      UnresolvedLinkException {
    assert hasWriteLock();
    // sanity check
    if ((nsQuota < 0 && nsQuota != HdfsConstants.QUOTA_DONT_SET && 
         nsQuota < HdfsConstants.QUOTA_RESET) || 
        (dsQuota < 0 && dsQuota != HdfsConstants.QUOTA_DONT_SET && 
          dsQuota < HdfsConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Illegal value for nsQuota or " +
                                         "dsQuota : " + nsQuota + " and " +
                                         dsQuota);
    }
    
    String srcs = normalizePath(src);

    INode[] inodes = rootDir.getExistingPathINodes(src, true);
    INode targetNode = inodes[inodes.length-1];
    if (targetNode == null) {
      throw new FileNotFoundException("Directory does not exist: " + srcs);
    } else if (!targetNode.isDirectory()) {
      throw new FileNotFoundException("Cannot set quota on a file: " + srcs);  
    } else if (targetNode.isRoot() && nsQuota == HdfsConstants.QUOTA_RESET) {
      throw new IllegalArgumentException("Cannot clear namespace quota on root.");
    } else { // a directory inode
      INodeDirectory dirNode = (INodeDirectory)targetNode;
      long oldNsQuota = dirNode.getNsQuota();
      long oldDsQuota = dirNode.getDsQuota();
      if (nsQuota == HdfsConstants.QUOTA_DONT_SET) {
        nsQuota = oldNsQuota;
      }
      if (dsQuota == HdfsConstants.QUOTA_DONT_SET) {
        dsQuota = oldDsQuota;
      }        

      if (dirNode instanceof INodeDirectoryWithQuota) { 
        // a directory with quota; so set the quota to the new value
        ((INodeDirectoryWithQuota)dirNode).setQuota(nsQuota, dsQuota);
        if (!dirNode.isQuotaSet()) {
          // will not come here for root because root's nsQuota is always set
          INodeDirectory newNode = new INodeDirectory(dirNode);
          INodeDirectory parent = (INodeDirectory)inodes[inodes.length-2];
          dirNode = newNode;
          parent.replaceChild(newNode);
        }
      } else {
        // a non-quota directory; so replace it with a directory with quota
        INodeDirectoryWithQuota newNode = 
          new INodeDirectoryWithQuota(nsQuota, dsQuota, dirNode);
        // non-root directory node; parent != null
        INodeDirectory parent = (INodeDirectory)inodes[inodes.length-2];
        dirNode = newNode;
        parent.replaceChild(newNode);
      }
      return (oldNsQuota != nsQuota || oldDsQuota != dsQuota) ? dirNode : null;
    }
  }
  
  /**
   * See {@link ClientProtocol#setQuota(String, long, long)} for the 
   * contract.
   * @see #unprotectedSetQuota(String, long, long)
   */
  void setQuota(String src, long nsQuota, long dsQuota) 
    throws FileNotFoundException, QuotaExceededException,
    UnresolvedLinkException { 
    writeLock();
    try {
      INodeDirectory dir = unprotectedSetQuota(src, nsQuota, dsQuota);
      if (dir != null) {
        fsImage.getEditLog().logSetQuota(src, dir.getNsQuota(), 
                                         dir.getDsQuota());
      }
    } finally {
      writeUnlock();
    }
  }
  
  long totalInodes() {
    readLock();
    try {
      return rootDir.numItemsInTree();
    } finally {
      readUnlock();
    }
  }

  /**
   * Sets the access time on the file/directory. Logs it in the transaction log.
   */
  void setTimes(String src, INode inode, long mtime, long atime, boolean force) {
    boolean status = false;
    writeLock();
    try {
      status = unprotectedSetTimes(src, inode, mtime, atime, force);
    } finally {
      writeUnlock();
    }
    if (status) {
      fsImage.getEditLog().logTimes(src, mtime, atime);
    }
  }

  boolean unprotectedSetTimes(String src, long mtime, long atime, boolean force) 
      throws UnresolvedLinkException {
    assert hasWriteLock();
    INode inode = getINode(src);
    return unprotectedSetTimes(src, inode, mtime, atime, force);
  }

  private boolean unprotectedSetTimes(String src, INode inode, long mtime,
                                      long atime, boolean force) {
    assert hasWriteLock();
    boolean status = false;
    if (mtime != -1) {
      inode.setModificationTimeForce(mtime);
      status = true;
    }
    if (atime != -1) {
      long inodeTime = inode.getAccessTime();

      // if the last access time update was within the last precision interval, then
      // no need to store access time
      if (atime <= inodeTime + getFSNamesystem().getAccessTimePrecision() && !force) {
        status =  false;
      } else {
        inode.setAccessTime(atime);
        status = true;
      }
    } 
    return status;
  }

  /**
   * Reset the entire namespace tree.
   */
  void reset() {
    writeLock();
    try {
      setReady(false);
      rootDir = new INodeDirectoryWithQuota(INodeDirectory.ROOT_NAME,
          getFSNamesystem().createFsOwnerPermissions(new FsPermission((short)0755)),
          Integer.MAX_VALUE, -1);
      nameCache.reset();
    } finally {
      writeUnlock();
    }
  }

  /**
   * create an hdfs file status from an inode
   * 
   * @param path the local name
   * @param node inode
   * @param needLocation if block locations need to be included or not
   * @return a file status
   * @throws IOException if any error occurs
   */
  private HdfsFileStatus createFileStatus(byte[] path, INode node,
      boolean needLocation) throws IOException {
    if (needLocation) {
      return createLocatedFileStatus(path, node);
    } else {
      return createFileStatus(path, node);
    }
  }
  /**
   * Create FileStatus by file INode 
   */
   private HdfsFileStatus createFileStatus(byte[] path, INode node) {
     long size = 0;     // length is zero for directories
     short replication = 0;
     long blocksize = 0;
     if (node instanceof INodeFile) {
       INodeFile fileNode = (INodeFile)node;
       size = fileNode.computeFileSize(true);
       replication = fileNode.getReplication();
       blocksize = fileNode.getPreferredBlockSize();
     }
     return new HdfsFileStatus(
        size, 
        node.isDirectory(), 
        replication, 
        blocksize,
        node.getModificationTime(),
        node.getAccessTime(),
        node.getFsPermission(),
        node.getUserName(),
        node.getGroupName(),
        node.isLink() ? ((INodeSymlink)node).getSymlink() : null,
        path);
  }

   /**
    * Create FileStatus with location info by file INode 
    */
    private HdfsLocatedFileStatus createLocatedFileStatus(
        byte[] path, INode node) throws IOException {
      assert hasReadLock();
      long size = 0;     // length is zero for directories
      short replication = 0;
      long blocksize = 0;
      LocatedBlocks loc = null;
      if (node instanceof INodeFile) {
        INodeFile fileNode = (INodeFile)node;
        size = fileNode.computeFileSize(true);
        replication = fileNode.getReplication();
        blocksize = fileNode.getPreferredBlockSize();
        loc = getFSNamesystem().getBlockManager().createLocatedBlocks(
            fileNode.getBlocks(), fileNode.computeFileSize(false),
            fileNode.isUnderConstruction(), 0L, size, false);
        if (loc==null) {
          loc = new LocatedBlocks();
        }
      }
      return new HdfsLocatedFileStatus(
          size, 
          node.isDirectory(), 
          replication, 
          blocksize,
          node.getModificationTime(),
          node.getAccessTime(),
          node.getFsPermission(),
          node.getUserName(),
          node.getGroupName(),
          node.isLink() ? ((INodeSymlink)node).getSymlink() : null,
          path,
          loc);
      }

    
  /**
   * Add the given symbolic link to the fs. Record it in the edits log.
   */
  INodeSymlink addSymlink(String path, String target,
      PermissionStatus dirPerms, boolean createParent)
      throws UnresolvedLinkException, FileAlreadyExistsException,
      QuotaExceededException, IOException {
    waitForReady();

    final long modTime = now();
    if (createParent) {
      final String parent = new Path(path).getParent().toString();
      if (!mkdirs(parent, dirPerms, true, modTime)) {
        return null;
      }
    }
    final String userName = dirPerms.getUserName();
    INodeSymlink newNode  = null;
    writeLock();
    try {
      newNode = unprotectedSymlink(path, target, modTime, modTime,
          new PermissionStatus(userName, null, FsPermission.getDefault()));
    } finally {
      writeUnlock();
    }
    if (newNode == null) {
      NameNode.stateChangeLog.info("DIR* FSDirectory.addSymlink: "
                                   +"failed to add "+path
                                   +" to the file system");
      return null;
    }
    fsImage.getEditLog().logSymlink(path, target, modTime, modTime, newNode);
    
    if(NameNode.stateChangeLog.isDebugEnabled()) {
      NameNode.stateChangeLog.debug("DIR* FSDirectory.addSymlink: "
          +path+" is added to the file system");
    }
    return newNode;
  }

  /**
   * Add the specified path into the namespace. Invoked from edit log processing.
   */
  INodeSymlink unprotectedSymlink(String path, String target, long modTime, 
                                  long atime, PermissionStatus perm) 
      throws UnresolvedLinkException {
    assert hasWriteLock();
    INodeSymlink newNode = new INodeSymlink(target, modTime, atime, perm);
    try {
      newNode = addNode(path, newNode, UNKNOWN_DISK_SPACE);
    } catch (UnresolvedLinkException e) {
      /* All UnresolvedLinkExceptions should have been resolved by now, but we
       * should re-throw them in case that changes so they are not swallowed 
       * by catching IOException below.
       */
      throw e;
    } catch (IOException e) {
      return null;
    }
    return newNode;
  }
  
  /**
   * Caches frequently used file names to reuse file name objects and
   * reduce heap size.
   */
  void cacheName(INode inode) {
    // Name is cached only for files
    if (inode.isDirectory() || inode.isLink()) {
      return;
    }
    ByteArray name = new ByteArray(inode.getLocalNameBytes());
    name = nameCache.put(name);
    if (name != null) {
      inode.setLocalName(name.getBytes());
    }
  }

}
