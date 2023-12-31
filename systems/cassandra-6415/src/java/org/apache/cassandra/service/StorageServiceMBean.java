/*
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
package org.apache.cassandra.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.management.NotificationEmitter;

public interface StorageServiceMBean extends NotificationEmitter
{
    /**
     * Retrieve the list of live nodes in the cluster, where "liveness" is
     * determined by the failure detector of the node being queried.
     *
     * @return set of IP addresses, as Strings
     */
    public List<String> getLiveNodes();

    /**
     * Retrieve the list of unreachable nodes in the cluster, as determined
     * by this node's failure detector.
     *
     * @return set of IP addresses, as Strings
     */
    public List<String> getUnreachableNodes();

    /**
     * Retrieve the list of nodes currently bootstrapping into the ring.
     *
     * @return set of IP addresses, as Strings
     */
    public List<String> getJoiningNodes();

    /**
     * Retrieve the list of nodes currently leaving the ring.
     *
     * @return set of IP addresses, as Strings
     */
    public List<String> getLeavingNodes();

    /**
     * Retrieve the list of nodes currently moving in the ring.
     *
     * @return set of IP addresses, as Strings
     */
    public List<String> getMovingNodes();

    /**
     * Fetch string representations of the tokens for this node.
     *
     * @return a collection of tokens formatted as strings
     */
    public List<String> getTokens();

    /**
     * Fetch string representations of the tokens for a specified node.
     *
     * @param endpoint string representation of an node
     * @return a collection of tokens formatted as strings
     */
    public List<String> getTokens(String endpoint) throws UnknownHostException;

    /**
     * Fetch a string representation of the Cassandra version.
     * @return A string representation of the Cassandra version.
     */
    public String getReleaseVersion();

    /**
     * Fetch a string representation of the current Schema version.
     * @return A string representation of the Schema version.
     */
    public String getSchemaVersion();


    /**
     * Get the list of all data file locations from conf
     * @return String array of all locations
     */
    public String[] getAllDataFileLocations();

    /**
     * Get location of the commit log
     * @return a string path
     */
    public String getCommitLogLocation();

    /**
     * Get location of the saved caches dir
     * @return a string path
     */
    public String getSavedCachesLocation();

    /**
     * Retrieve a map of range to end points that describe the ring topology
     * of a Cassandra cluster.
     *
     * @return mapping of ranges to end points
     */
    public Map<List<String>, List<String>> getRangeToEndpointMap(String keyspace);

    /**
     * Retrieve a map of range to rpc addresses that describe the ring topology
     * of a Cassandra cluster.
     *
     * @return mapping of ranges to rpc addresses
     */
    public Map<List<String>, List<String>> getRangeToRpcaddressMap(String keyspace);

    /**
     * The same as {@code describeRing(String)} but converts TokenRange to the String for JMX compatibility
     *
     * @param keyspace The keyspace to fetch information about
     *
     * @return a List of TokenRange(s) converted to String for the given keyspace
     */
    public List <String> describeRingJMX(String keyspace) throws IOException;

    /**
     * Retrieve a map of pending ranges to endpoints that describe the ring topology
     * @param keyspace the keyspace to get the pending range map for.
     * @return a map of pending ranges to endpoints
     */
    public Map<List<String>, List<String>> getPendingRangeToEndpointMap(String keyspace);

    /**
     * Retrieve a map of tokens to endpoints, including the bootstrapping
     * ones.
     *
     * @return a map of tokens to endpoints in ascending order
     */
    public Map<String, String> getTokenToEndpointMap();

    /** Retrieve this hosts unique ID */
    public String getLocalHostId();

    /** Retrieve the mapping of endpoint to host ID */
    public Map<String, String> getHostIdMap();

    /**
     * Numeric load value.
     * @see org.apache.cassandra.metrics.StorageMetrics#load
     */
    @Deprecated
    public double getLoad();

    /** Human-readable load value */
    public String getLoadString();

    /** Human-readable load value.  Keys are IP addresses. */
    public Map<String, String> getLoadMap();

    /**
     * Return the generation value for this node.
     *
     * @return generation number
     */
    public int getCurrentGenerationNumber();

    /**
     * This method returns the N endpoints that are responsible for storing the
     * specified key i.e for replication.
     *
     * @param keyspaceName keyspace name
     * @param cf Column family name
     * @param key - key for which we need to find the endpoint return value -
     * the endpoint responsible for this key
     */
    public List<InetAddress> getNaturalEndpoints(String keyspaceName, String cf, String key);
    public List<InetAddress> getNaturalEndpoints(String keyspaceName, ByteBuffer key);

    /**
     * Takes the snapshot for the given keyspaces. A snapshot name must be specified.
     *
     * @param tag the tag given to the snapshot; may not be null or empty
     * @param keyspaceNames the name of the keyspaces to snapshot; empty means "all."
     */
    public void takeSnapshot(String tag, String... keyspaceNames) throws IOException;

    /**
     * Takes the snapshot of a specific column family. A snapshot name must be specified.
     *
     * @param keyspaceName the keyspace which holds the specified column family
     * @param columnFamilyName the column family to snapshot
     * @param tag the tag given to the snapshot; may not be null or empty
     */
    public void takeColumnFamilySnapshot(String keyspaceName, String columnFamilyName, String tag) throws IOException;

    /**
     * Remove the snapshot with the given name from the given keyspaces.
     * If no tag is specified we will remove all snapshots.
     */
    public void clearSnapshot(String tag, String... keyspaceNames) throws IOException;

    /**
     * Forces major compaction of a single keyspace
     */
    public void forceKeyspaceCompaction(String keyspaceName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException;

    /**
     * Trigger a cleanup of keys on a single keyspace
     */
    public void forceKeyspaceCleanup(String keyspaceName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException;

    /**
     * Scrub (deserialize + reserialize at the latest version, skipping bad rows if any) the given keyspace.
     * If columnFamilies array is empty, all CFs are scrubbed.
     *
     * Scrubbed CFs will be snapshotted first, if disableSnapshot is false
     */
    public void scrub(boolean disableSnapshot, String keyspaceName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException;

    /**
     * Rewrite all sstables to the latest version.
     * Unlike scrub, it doesn't skip bad rows and do not snapshot sstables first.
     */
    public void upgradeSSTables(String keyspaceName, boolean excludeCurrentVersion, String... columnFamilies) throws IOException, ExecutionException, InterruptedException;

    /**
     * Flush all memtables for the given column families, or all columnfamilies for the given keyspace
     * if none are explicitly listed.
     * @param keyspaceName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceKeyspaceFlush(String keyspaceName, String... columnFamilies) throws IOException, ExecutionException, InterruptedException;

    /**
     * Invoke repair asynchronously.
     * You can track repair progress by subscribing JMX notification sent from this StorageServiceMBean.
     * Notification format is:
     *   type: "repair"
     *   userObject: int array of length 2, [0]=command number, [1]=ordinal of AntiEntropyService.Status
     *
     * @return Repair command number, or 0 if nothing to repair
     * @see #forceKeyspaceRepair(String, boolean, boolean, String...)
     */
    public int forceRepairAsync(String keyspace, boolean isSequential, boolean isLocal, boolean primaryRange, String... columnFamilies);

    /**
     * Same as forceRepairAsync, but handles a specified range
     */
    public int forceRepairRangeAsync(String beginToken, String endToken, final String keyspaceName, boolean isSequential, boolean isLocal, final String... columnFamilies);

    /**
     * Triggers proactive repair for given column families, or all columnfamilies for the given keyspace
     * if none are explicitly listed.
     * @param keyspaceName
     * @param columnFamilies
     * @throws IOException
     */
    public void forceKeyspaceRepair(String keyspaceName, boolean isSequential, boolean isLocal, String... columnFamilies) throws IOException;

    /**
     * Triggers proactive repair but only for the node primary range.
     */
    public void forceKeyspaceRepairPrimaryRange(String keyspaceName, boolean isSequential, boolean isLocal, String... columnFamilies) throws IOException;

    /**
     * Perform repair of a specific range.
     *
     * This allows incremental repair to be performed by having an external controller submitting repair jobs.
     * Note that the provided range much be a subset of one of the node local range.
     */
    public void forceKeyspaceRepairRange(String beginToken, String endToken, String keyspaceName, boolean isSequential, boolean isLocal, String... columnFamilies) throws IOException;

    public void forceTerminateAllRepairSessions();

    /**
     * transfer this node's data to other machines and remove it from service.
     */
    public void decommission() throws InterruptedException;

    /**
     * @param newToken token to move this node to.
     * This node will unload its data onto its neighbors, and bootstrap to the new token.
     */
    public void move(String newToken) throws IOException;

    /**
     * @param srcTokens tokens to move to this node
     */
    public void relocate(Collection<String> srcTokens) throws IOException;

    /**
     * removeToken removes token (and all data associated with
     * enpoint that had it) from the ring
     */
    public void removeNode(String token);

    /**
     * Get the status of a token removal.
     */
    public String getRemovalStatus();

    /**
     * Force a remove operation to finish.
     */
    public void forceRemoveCompletion();

    /** set the logging level at runtime */
    public void setLog4jLevel(String classQualifier, String level);

    /** get the operational mode (leaving, joining, normal, decommissioned, client) **/
    public String getOperationMode();

    /** get the progress of a drain operation */
    public String getDrainProgress();

    /** makes node unavailable for writes, flushes memtables and replays commitlog. */
    public void drain() throws IOException, InterruptedException, ExecutionException;

    /**
     * Truncates (deletes) the given columnFamily from the provided keyspace.
     * Calling truncate results in actual deletion of all data in the cluster
     * under the given columnFamily and it will fail unless all hosts are up.
     * All data in the given column family will be deleted, but its definition
     * will not be affected.
     *
     * @param keyspace The keyspace to delete from
     * @param columnFamily The column family to delete data from.
     */
    public void truncate(String keyspace, String columnFamily)throws TimeoutException, IOException;

    /**
     * given a list of tokens (representing the nodes in the cluster), returns
     *   a mapping from "token -> %age of cluster owned by that token"
     */
    public Map<InetAddress, Float> getOwnership();

    /**
     * Effective ownership is % of the data each node owns given the keyspace
     * we calculate the percentage using replication factor.
     * If Keyspace == null, this method will try to verify if all the keyspaces
     * in the cluster have the same replication strategies and if yes then we will
     * use the first else a empty Map is returned.
     */
    public Map<InetAddress, Float> effectiveOwnership(String keyspace) throws IllegalStateException;

    public List<String> getKeyspaces();

    /**
     * Change endpointsnitch class and dynamic-ness (and dynamic attributes) at runtime
     * @param epSnitchClassName        the canonical path name for a class implementing IEndpointSnitch
     * @param dynamic                  boolean that decides whether dynamicsnitch is used or not
     * @param dynamicUpdateInterval    integer, in ms (default 100)
     * @param dynamicResetInterval     integer, in ms (default 600,000)
     * @param dynamicBadnessThreshold  double, (default 0.0)
     */
    public void updateSnitch(String epSnitchClassName, Boolean dynamic, Integer dynamicUpdateInterval, Integer dynamicResetInterval, Double dynamicBadnessThreshold) throws ClassNotFoundException;

    // allows a user to forcibly 'kill' a sick node
    public void stopGossiping();

    // allows a user to recover a forcibly 'killed' node
    public void startGossiping();

    // to determine if gossip is disabled
    public boolean isInitialized();

    // allows a user to disable thrift
    public void stopRPCServer();

    // allows a user to reenable thrift
    public void startRPCServer();

    // to determine if thrift is running
    public boolean isRPCServerRunning();

    public void stopNativeTransport();
    public void startNativeTransport();
    public boolean isNativeTransportRunning();

    // allows a node that have been started without joining the ring to join it
    public void joinRing() throws IOException;
    public boolean isJoined();

    @Deprecated
    public int getExceptionCount();

    public void setStreamThroughputMbPerSec(int value);
    public int getStreamThroughputMbPerSec();

    public int getCompactionThroughputMbPerSec();
    public void setCompactionThroughputMbPerSec(int value);

    public boolean isIncrementalBackupsEnabled();
    public void setIncrementalBackupsEnabled(boolean value);

    /**
     * Initiate a process of streaming data for which we are responsible from other nodes. It is similar to bootstrap
     * except meant to be used on a node which is already in the cluster (typically containing no data) as an
     * alternative to running repair.
     *
     * @param sourceDc Name of DC from which to select sources for streaming or null to pick any node
     */
    public void rebuild(String sourceDc);

    public void bulkLoad(String directory);

    public void rescheduleFailedDeletions();

    /**
     * Load new SSTables to the given keyspace/columnFamily
     *
     * @param ksName The parent keyspace name
     * @param cfName The ColumnFamily name where SSTables belong
     */
    public void loadNewSSTables(String ksName, String cfName);

    /**
     * Return a List of Tokens representing a sample of keys across all ColumnFamilyStores.
     *
     * Note: this should be left as an operation, not an attribute (methods starting with "get")
     * to avoid sending potentially multiple MB of data when accessing this mbean by default.  See CASSANDRA-4452.
     *
     * @return set of Tokens as Strings
     */
    public List<String> sampleKeyRange();

    /**
     * rebuild the specified indexes
     */
    public void rebuildSecondaryIndex(String ksName, String cfName, String... idxNames);

    public void resetLocalSchema() throws IOException;

    /**
     * Enables/Disables tracing for the whole system. Only thrift requests can start tracing currently.
     * 
     * @param probability
     *            ]0,1[ will enable tracing on a partial number of requests with the provided probability. 0 will
     *            disable tracing and 1 will enable tracing for all requests (which mich severely cripple the system)
     */
    public void setTraceProbability(double probability);

    /**
     * Returns the configured tracing probability.
     */
    public double getTracingProbability();

    /** Begin processing of queued range transfers. */
    public void enableScheduledRangeXfers();
    /** Disable processing of queued range transfers. */
    public void disableScheduledRangeXfers();

    void disableAutoCompaction(String ks, String ... columnFamilies) throws IOException;
    void enableAutoCompaction(String ks, String ... columnFamilies) throws IOException;

    public void deliverHints(String host) throws UnknownHostException;

    /** Returns the name of the cluster */
    public String getClusterName();
    /** Returns the cluster partitioner */
    public String getPartitionerName();

    /** Returns the threshold for warning of queries with many tombstones */
    public int getTombstoneWarnThreshold();
    /** Sets the threshold for warning queries with many tombstones */
    public void setTombstoneWarnThreshold(int tombstoneDebugThreshold);

    /** Returns the threshold for abandoning queries with many tombstones */
    public int getTombstoneFailureThreshold();
    /** Sets the threshold for abandoning queries with many tombstones */
    public void setTombstoneFailureThreshold(int tombstoneDebugThreshold);
}
