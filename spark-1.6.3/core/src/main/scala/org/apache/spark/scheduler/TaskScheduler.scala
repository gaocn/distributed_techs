/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.storage.BlockManagerId

/**
 * Low-level task scheduler interface, currently implemented exclusively by
 * [[org.apache.spark.scheduler.TaskSchedulerImpl]].
 * This interface allows plugging in different task schedulers. Each TaskScheduler schedules tasks
 * for a single SparkContext(TaskScheduler肯定知道上下文是什么). These schedulers get sets of tasks submitted to them from the
 * DAGScheduler for each stage（每个Stage就是一个Mapper/Reducer）, and are responsible for sending the tasks to the cluster, running
 * them, retrying if there are failures, and mitigating stragglers（慢任务，则在其他节点上启动该任务看谁先执行完）. They return events to the
 * DAGScheduler（任务执行完成后向DAGScheduler汇报）.
 *
 * 每一个具体的TaskScheduler负责一个具体的Mapper或Reducer中的任务调度！
 *1、TaskScheduler是在SparkContext中被创建；
 */
private[spark] trait TaskScheduler {

  private val appId = "spark-application-" + System.currentTimeMillis

  /**任务调度的队列
    * Pool实例为树状结构，其中Pool为分支或根节点，TaskSetManager为叶子节点
    * （1）在FIFO调度模式下，Pool下面就是TaskSetManager，即具体要执行的任务并产生具体的任务调度；
    * （2）在FAIR调度模式下，Pool下面继续是Pool，而在Pool下面的Pool里面才是具体的TaskSetManager
    * 从调度模型的角度看，可以解释为什么FIFO模式下可以获取尽可能多的资源，FAIR模式下的资源调度情况！
    */
  def rootPool: Pool
  //调度模式涉及资源是如何分配！
  def schedulingMode: SchedulingMode

  def start(): Unit

  // Invoked after system has successfully initialized (typically in spark context).
  // Yarn uses this to bootstrap allocation of resources based on preferred locations,
  // wait for slave registrations, etc.
  def postStartHook() { }

  // Disconnect from the cluster.
  def stop(): Unit

  // Submit a sequence of tasks to run.
  def submitTasks(taskSet: TaskSet): Unit

  // Cancel a stage.
  def cancelTasks(stageId: Int, interruptThread: Boolean)

  // Set the DAG scheduler for upcalls. This is guaranteed to be set before submitTasks is called.
  def setDAGScheduler(dagScheduler: DAGScheduler): Unit

  // Get the default level of parallelism to use in the cluster, as a hint for sizing jobs.
  def defaultParallelism(): Int

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  def executorHeartbeatReceived(execId: String, taskMetrics: Array[(Long, TaskMetrics)],
    blockManagerId: BlockManagerId): Boolean

  /**
   * Get an application ID associated with the job.
   *
   * @return An application ID
   */
  def applicationId(): String = appId

  /**
   * Process a lost executor
   */
  def executorLost(executorId: String, reason: ExecutorLossReason): Unit

  /**
   * Get an application's attempt ID associated with the job.
   *
   * @return An application's Attempt ID
   */
  def applicationAttemptId(): Option[String]

}
