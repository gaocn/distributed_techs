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

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.Logging
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode

/**
 * An Schedulable entity that represent collection of Pools or TaskSetManagers
 * Pool实例为树状结构，其中Pool为分支或根节点，TaskSetManager为叶子节点
 * （1）在FIFO调度模式下，Pool下面就是TaskSetManager，即具体要执行的任务并产生具体的任务调度；
 * （2）在FAIR调度模式下，Pool下面继续是Pool，而在Pool下面的Pool里面才是具体的TaskSetManager
 * 从调度模型的角度看，可以解释为什么FIFO模式下可以获取尽可能多的资源，FAIR模式下的资源调度情况！
 */

private[spark] class Pool( //树结构
    val poolName: String,
    val schedulingMode: SchedulingMode, //Pool调度模式：FAIR、FIFO
    initMinShare: Int, //最小CPU CORE个数,例如：在FAIR模式下需要首先满足每个任务的最小CORE数的情况下，再分配其他剩下的COREs
initWeight: Int) //权重，默认为1意思是：和其他Pool获取的资源是均等的。在FAIR模式下，若为2则可以获取其他资源的2倍，权重越大则其获取的资源越多越能够先运行！
  extends Schedulable
  with Logging {

  val schedulableQueue = new ConcurrentLinkedQueue[Schedulable] //线程安全的队列
  val schedulableNameToSchedulable = new ConcurrentHashMap[String, Schedulable]
  var weight = initWeight
  var minShare = initMinShare
  var runningTasks = 0
  var priority = 0 //优先级

  // A pool's stage id is used to break the tie in scheduling.
  var stageId = -1
  var name = poolName
  var parent: Pool = null

  //调度算法
  var taskSetSchedulingAlgorithm: SchedulingAlgorithm = {
    schedulingMode match {
      case SchedulingMode.FAIR =>
        new FairSchedulingAlgorithm()
      case SchedulingMode.FIFO =>
        new FIFOSchedulingAlgorithm()
    }
  }

  override def addSchedulable(schedulable: Schedulable) {
    require(schedulable != null)
    schedulableQueue.add(schedulable)
    schedulableNameToSchedulable.put(schedulable.name, schedulable)
    schedulable.parent = this
  }

  override def removeSchedulable(schedulable: Schedulable) {
    schedulableQueue.remove(schedulable)
    schedulableNameToSchedulable.remove(schedulable.name)
  }

  override def getSchedulableByName(schedulableName: String): Schedulable = {
    if (schedulableNameToSchedulable.containsKey(schedulableName)) {
      return schedulableNameToSchedulable.get(schedulableName)
    }
    for (schedulable <- schedulableQueue.asScala) {
      val sched = schedulable.getSchedulableByName(schedulableName)
      if (sched != null) {
        return sched
      }
    }
    null
  }
  //树的遍历
  override def executorLost(executorId: String, host: String, reason: ExecutorLossReason) {
    schedulableQueue.asScala.foreach(_.executorLost(executorId, host, reason))
  }

  override def checkSpeculatableTasks(): Boolean = {
    var shouldRevive = false
    for (schedulable <- schedulableQueue.asScala) {
      shouldRevive |= schedulable.checkSpeculatableTasks()
    }
    shouldRevive
  }

  /**
    * 以当前Pool为树根，对Pool里面的所有TaskSetManager进行排序
    */
  override def getSortedTaskSetQueue: ArrayBuffer[TaskSetManager] = {
    var sortedTaskSetQueue = new ArrayBuffer[TaskSetManager]
    val sortedSchedulableQueue =
      schedulableQueue.asScala.toSeq.sortWith(taskSetSchedulingAlgorithm.comparator)
    for (schedulable <- sortedSchedulableQueue) {
      sortedTaskSetQueue ++= schedulable.getSortedTaskSetQueue //遍历Pool获取对应叶节点TaskSetManager
    }
    //返回排序后的TaskSetManager，最后调度也是基于TaskSetManager进行的
    sortedTaskSetQueue
  }

  def increaseRunningTasks(taskNum: Int) {
    runningTasks += taskNum
    if (parent != null) {
      parent.increaseRunningTasks(taskNum)
    }
  }

  def decreaseRunningTasks(taskNum: Int) {
    runningTasks -= taskNum
    if (parent != null) {
      parent.decreaseRunningTasks(taskNum)
    }
  }
}
