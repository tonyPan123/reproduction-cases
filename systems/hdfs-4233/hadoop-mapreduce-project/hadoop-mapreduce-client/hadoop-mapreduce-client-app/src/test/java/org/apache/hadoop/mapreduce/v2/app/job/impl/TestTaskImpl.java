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
package org.apache.hadoop.mapreduce.v2.app.job.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.TaskUmbilicalProtocol;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryParser.TaskInfo;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitMetaInfo;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app.job.TaskStateInternal;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskTAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.metrics.MRAppMetrics;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.Clock;
import org.apache.hadoop.yarn.SystemClock;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.resourcemanager.resourcetracker.InlineDispatcher;
import org.apache.hadoop.yarn.util.Records;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("rawtypes")
public class TestTaskImpl {

  private static final Log LOG = LogFactory.getLog(TestTaskImpl.class);    
  
  private JobConf conf;
  private TaskAttemptListener taskAttemptListener;
  private OutputCommitter committer;
  private Token<JobTokenIdentifier> jobToken;
  private JobId jobId;
  private Path remoteJobConfFile;
  private Credentials credentials;
  private Clock clock;
  private Map<TaskId, TaskInfo> completedTasksFromPreviousRun;
  private MRAppMetrics metrics;
  private TaskImpl mockTask;
  private ApplicationId appId;
  private TaskSplitMetaInfo taskSplitMetaInfo;  
  private String[] dataLocations = new String[0]; 
  private final TaskType taskType = TaskType.MAP;
  private AppContext appContext;
  
  private int startCount = 0;
  private int taskCounter = 0;
  private final int partition = 1;
  
  private InlineDispatcher dispatcher;   
  private List<MockTaskAttemptImpl> taskAttempts;
  
  private class MockTaskImpl extends TaskImpl {
        
    private int taskAttemptCounter = 0;

    public MockTaskImpl(JobId jobId, int partition,
        EventHandler eventHandler, Path remoteJobConfFile, JobConf conf,
        TaskAttemptListener taskAttemptListener, OutputCommitter committer,
        Token<JobTokenIdentifier> jobToken,
        Credentials credentials, Clock clock,
        Map<TaskId, TaskInfo> completedTasksFromPreviousRun, int startCount,
        MRAppMetrics metrics, AppContext appContext) {
      super(jobId, taskType , partition, eventHandler,
          remoteJobConfFile, conf, taskAttemptListener, committer, 
          jobToken, credentials, clock,
          completedTasksFromPreviousRun, startCount, metrics, appContext);
    }

    @Override
    public TaskType getType() {
      return taskType;
    }

    @Override
    protected TaskAttemptImpl createAttempt() {
      MockTaskAttemptImpl attempt = new MockTaskAttemptImpl(getID(), ++taskAttemptCounter, 
          eventHandler, taskAttemptListener, remoteJobConfFile, partition,
          conf, committer, jobToken, credentials, clock, appContext);
      taskAttempts.add(attempt);
      return attempt;
    }

    @Override
    protected int getMaxAttempts() {
      return 100;
    }

    @Override
    protected void internalError(TaskEventType type) {
      super.internalError(type);
      fail("Internal error: " + type);
    }
  }
  
  private class MockTaskAttemptImpl extends TaskAttemptImpl {

    private float progress = 0;
    private TaskAttemptState state = TaskAttemptState.NEW;

    public MockTaskAttemptImpl(TaskId taskId, int id, EventHandler eventHandler,
        TaskAttemptListener taskAttemptListener, Path jobFile, int partition,
        JobConf conf, OutputCommitter committer,
        Token<JobTokenIdentifier> jobToken,
        Credentials credentials, Clock clock,
        AppContext appContext) {
      super(taskId, id, eventHandler, taskAttemptListener, jobFile, partition, conf,
          dataLocations, committer, jobToken, credentials, clock, appContext);
    }

    public TaskAttemptId getAttemptId() {
      return getID();
    }
    
    @Override
    protected Task createRemoteTask() {
      return new MockTask();
    }    
    
    public float getProgress() {
      return progress ;
    }
    
    public void setProgress(float progress) {
      this.progress = progress;
    }
    
    public void setState(TaskAttemptState state) {
      this.state = state;
    }
    
    public TaskAttemptState getState() {
      return state;
    }
    
  }
  
  private class MockTask extends Task {

    @Override
    public void run(JobConf job, TaskUmbilicalProtocol umbilical)
        throws IOException, ClassNotFoundException, InterruptedException {
      return;
    }

    @Override
    public boolean isMapTask() {
      return true;
    }    
    
  }
  
  @Before 
  @SuppressWarnings("unchecked")
  public void setup() {
     dispatcher = new InlineDispatcher();
    
    ++startCount;
    
    conf = new JobConf();
    taskAttemptListener = mock(TaskAttemptListener.class);
    committer = mock(OutputCommitter.class);
    jobToken = (Token<JobTokenIdentifier>) mock(Token.class);
    remoteJobConfFile = mock(Path.class);
    credentials = null;
    clock = new SystemClock();
    metrics = mock(MRAppMetrics.class);  
    dataLocations = new String[1];
    
    appId = Records.newRecord(ApplicationId.class);
    appId.setClusterTimestamp(System.currentTimeMillis());
    appId.setId(1);

    jobId = Records.newRecord(JobId.class);
    jobId.setId(1);
    jobId.setAppId(appId);
    appContext = mock(AppContext.class);

    taskSplitMetaInfo = mock(TaskSplitMetaInfo.class);
    when(taskSplitMetaInfo.getLocations()).thenReturn(dataLocations); 
    
    taskAttempts = new ArrayList<MockTaskAttemptImpl>();
    
    mockTask = new MockTaskImpl(jobId, partition, dispatcher.getEventHandler(),
        remoteJobConfFile, conf, taskAttemptListener, committer, jobToken,
        credentials, clock,
        completedTasksFromPreviousRun, startCount,
        metrics, appContext);        
    
  }

  @After 
  public void teardown() {
    taskAttempts.clear();
  }
  
  private TaskId getNewTaskID() {
    TaskId taskId = Records.newRecord(TaskId.class);
    taskId.setId(++taskCounter);
    taskId.setJobId(jobId);
    taskId.setTaskType(mockTask.getType());    
    return taskId;
  }
  
  private void scheduleTaskAttempt(TaskId taskId) {
    mockTask.handle(new TaskEvent(taskId, 
        TaskEventType.T_SCHEDULE));
    assertTaskScheduledState();
  }
  
  private void killTask(TaskId taskId) {
    mockTask.handle(new TaskEvent(taskId, 
        TaskEventType.T_KILL));
    assertTaskKillWaitState();
  }
  
  private void killScheduledTaskAttempt(TaskAttemptId attemptId) {
    mockTask.handle(new TaskTAttemptEvent(attemptId, 
        TaskEventType.T_ATTEMPT_KILLED));
    assertTaskScheduledState();
  }

  private void launchTaskAttempt(TaskAttemptId attemptId) {
    mockTask.handle(new TaskTAttemptEvent(attemptId, 
        TaskEventType.T_ATTEMPT_LAUNCHED));
    assertTaskRunningState();    
  }
  
  private void commitTaskAttempt(TaskAttemptId attemptId) {
    mockTask.handle(new TaskTAttemptEvent(attemptId, 
        TaskEventType.T_ATTEMPT_COMMIT_PENDING));
    assertTaskRunningState();    
  }
  
  private MockTaskAttemptImpl getLastAttempt() {
    return taskAttempts.get(taskAttempts.size()-1);
  }
  
  private void updateLastAttemptProgress(float p) {    
    getLastAttempt().setProgress(p);
  }

  private void updateLastAttemptState(TaskAttemptState s) {
    getLastAttempt().setState(s);
  }
  
  private void killRunningTaskAttempt(TaskAttemptId attemptId) {
    mockTask.handle(new TaskTAttemptEvent(attemptId, 
        TaskEventType.T_ATTEMPT_KILLED));
    assertTaskRunningState();  
  }
  
  private void failRunningTaskAttempt(TaskAttemptId attemptId) {
    mockTask.handle(new TaskTAttemptEvent(attemptId, 
        TaskEventType.T_ATTEMPT_FAILED));
    assertTaskRunningState();
  }
  
  /**
   * {@link TaskState#NEW}
   */
  private void assertTaskNewState() {
    assertEquals(TaskState.NEW, mockTask.getState());
  }
  
  /**
   * {@link TaskState#SCHEDULED}
   */
  private void assertTaskScheduledState() {
    assertEquals(TaskState.SCHEDULED, mockTask.getState());
  }

  /**
   * {@link TaskState#RUNNING}
   */
  private void assertTaskRunningState() {
    assertEquals(TaskState.RUNNING, mockTask.getState());
  }
    
  /**
   * {@link TaskState#KILL_WAIT}
   */
  private void assertTaskKillWaitState() {
    assertEquals(TaskStateInternal.KILL_WAIT, mockTask.getInternalState());
  }
  
  /**
   * {@link TaskState#SUCCEEDED}
   */
  private void assertTaskSucceededState() {
    assertEquals(TaskState.SUCCEEDED, mockTask.getState());
  }
  
  @Test
  public void testInit() {
    LOG.info("--- START: testInit ---");
    assertTaskNewState();
    assert(taskAttempts.size() == 0);
  }

  @Test
  /**
   * {@link TaskState#NEW}->{@link TaskState#SCHEDULED}
   */
  public void testScheduleTask() {
    LOG.info("--- START: testScheduleTask ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
  }
  
  @Test 
  /**
   * {@link TaskState#SCHEDULED}->{@link TaskState#KILL_WAIT}
   */
  public void testKillScheduledTask() {
    LOG.info("--- START: testKillScheduledTask ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    killTask(taskId);
  }
  
  @Test 
  /**
   * Kill attempt
   * {@link TaskState#SCHEDULED}->{@link TaskState#SCHEDULED}
   */
  public void testKillScheduledTaskAttempt() {
    LOG.info("--- START: testKillScheduledTaskAttempt ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    killScheduledTaskAttempt(getLastAttempt().getAttemptId());
  }
  
  @Test 
  /**
   * Launch attempt
   * {@link TaskState#SCHEDULED}->{@link TaskState#RUNNING}
   */
  public void testLaunchTaskAttempt() {
    LOG.info("--- START: testLaunchTaskAttempt ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    launchTaskAttempt(getLastAttempt().getAttemptId());
  }

  @Test
  /**
   * Kill running attempt
   * {@link TaskState#RUNNING}->{@link TaskState#RUNNING} 
   */
  public void testKillRunningTaskAttempt() {
    LOG.info("--- START: testKillRunningTaskAttempt ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    launchTaskAttempt(getLastAttempt().getAttemptId());
    killRunningTaskAttempt(getLastAttempt().getAttemptId());    
  }

  @Test
  public void testKillSuccessfulTask() {
    LOG.info("--- START: testKillSuccesfulTask ---");
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    launchTaskAttempt(getLastAttempt().getAttemptId());
    commitTaskAttempt(getLastAttempt().getAttemptId());
    mockTask.handle(new TaskTAttemptEvent(getLastAttempt().getAttemptId(),
        TaskEventType.T_ATTEMPT_SUCCEEDED));
    assertTaskSucceededState();
    mockTask.handle(new TaskEvent(taskId, TaskEventType.T_KILL));
    assertTaskSucceededState();
  }

  @Test 
  public void testTaskProgress() {
    LOG.info("--- START: testTaskProgress ---");
        
    // launch task
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    float progress = 0f;
    assert(mockTask.getProgress() == progress);
    launchTaskAttempt(getLastAttempt().getAttemptId());    
    
    // update attempt1 
    progress = 50f;
    updateLastAttemptProgress(progress);
    assert(mockTask.getProgress() == progress);
    progress = 100f;
    updateLastAttemptProgress(progress);
    assert(mockTask.getProgress() == progress);
    
    progress = 0f;
    // mark first attempt as killed
    updateLastAttemptState(TaskAttemptState.KILLED);
    assert(mockTask.getProgress() == progress);

    // kill first attempt 
    // should trigger a new attempt
    // as no successful attempts 
    killRunningTaskAttempt(getLastAttempt().getAttemptId());
    assert(taskAttempts.size() == 2);
    
    assert(mockTask.getProgress() == 0f);
    launchTaskAttempt(getLastAttempt().getAttemptId());
    progress = 50f;
    updateLastAttemptProgress(progress);
    assert(mockTask.getProgress() == progress);
        
  }
  
  @Test
  public void testFailureDuringTaskAttemptCommit() {
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    launchTaskAttempt(getLastAttempt().getAttemptId());
    updateLastAttemptState(TaskAttemptState.COMMIT_PENDING);
    commitTaskAttempt(getLastAttempt().getAttemptId());

    // During the task attempt commit there is an exception which causes
    // the attempt to fail
    updateLastAttemptState(TaskAttemptState.FAILED);
    failRunningTaskAttempt(getLastAttempt().getAttemptId());

    assertEquals(2, taskAttempts.size());
    updateLastAttemptState(TaskAttemptState.SUCCEEDED);
    commitTaskAttempt(getLastAttempt().getAttemptId());
    mockTask.handle(new TaskTAttemptEvent(getLastAttempt().getAttemptId(), 
        TaskEventType.T_ATTEMPT_SUCCEEDED));
    
    assertFalse("First attempt should not commit",
        mockTask.canCommit(taskAttempts.get(0).getAttemptId()));
    assertTrue("Second attempt should commit",
        mockTask.canCommit(getLastAttempt().getAttemptId()));

    assertTaskSucceededState();
  }
  
  private void runSpeculativeTaskAttemptSucceeds(
      TaskEventType firstAttemptFinishEvent) {
    TaskId taskId = getNewTaskID();
    scheduleTaskAttempt(taskId);
    launchTaskAttempt(getLastAttempt().getAttemptId());
    updateLastAttemptState(TaskAttemptState.RUNNING);

    // Add a speculative task attempt that succeeds
    mockTask.handle(new TaskTAttemptEvent(getLastAttempt().getAttemptId(), 
        TaskEventType.T_ADD_SPEC_ATTEMPT));
    launchTaskAttempt(getLastAttempt().getAttemptId());
    commitTaskAttempt(getLastAttempt().getAttemptId());
    mockTask.handle(new TaskTAttemptEvent(getLastAttempt().getAttemptId(), 
        TaskEventType.T_ATTEMPT_SUCCEEDED));
    
    // The task should now have succeeded
    assertTaskSucceededState();
    
    // Now complete the first task attempt, after the second has succeeded
    mockTask.handle(new TaskTAttemptEvent(taskAttempts.get(0).getAttemptId(), 
        firstAttemptFinishEvent));
    
    // The task should still be in the succeeded state
    assertTaskSucceededState();
  }

  @Test
  public void testSpeculativeTaskAttemptSucceedsEvenIfFirstFails() {
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_FAILED);
  }

  @Test
  public void testMultipleTaskAttemptsSucceed() {
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_SUCCEEDED);
  }

  @Test
  public void testCommitAfterSucceeds() {
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_COMMIT_PENDING);
  }

  @Test
  public void testSpeculativeMapFetchFailure() {
    // Setup a scenario where speculative task wins, first attempt killed
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_KILLED);
    assertEquals(2, taskAttempts.size());

    // speculative attempt retroactively fails from fetch failures
    mockTask.handle(new TaskTAttemptEvent(taskAttempts.get(1).getAttemptId(),
        TaskEventType.T_ATTEMPT_FAILED));

    assertTaskScheduledState();
    assertEquals(3, taskAttempts.size());
  }

  @Test
  public void testSpeculativeMapMultipleSucceedFetchFailure() {
    // Setup a scenario where speculative task wins, first attempt succeeds
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_SUCCEEDED);
    assertEquals(2, taskAttempts.size());

    // speculative attempt retroactively fails from fetch failures
    mockTask.handle(new TaskTAttemptEvent(taskAttempts.get(1).getAttemptId(),
        TaskEventType.T_ATTEMPT_FAILED));

    assertTaskScheduledState();
    assertEquals(3, taskAttempts.size());
  }

  @Test
  public void testSpeculativeMapFailedFetchFailure() {
    // Setup a scenario where speculative task wins, first attempt succeeds
    runSpeculativeTaskAttemptSucceeds(TaskEventType.T_ATTEMPT_FAILED);
    assertEquals(2, taskAttempts.size());

    // speculative attempt retroactively fails from fetch failures
    mockTask.handle(new TaskTAttemptEvent(taskAttempts.get(1).getAttemptId(),
        TaskEventType.T_ATTEMPT_FAILED));

    assertTaskScheduledState();
    assertEquals(3, taskAttempts.size());
  }
}
