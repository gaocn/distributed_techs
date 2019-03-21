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

package org.apache.spark.streaming.scheduler

import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable.HashMap
import scala.concurrent.{Future, ExecutionContext}
import scala.language.existentials
import scala.util.{Failure, Success}

import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.rpc._
import org.apache.spark.scheduler.{TaskLocation, ExecutorCacheTaskLocation}
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.receiver._
import org.apache.spark.streaming.util.WriteAheadLogUtils
import org.apache.spark.util.{SerializableConfiguration, ThreadUtils, Utils}


/** Enumeration to identify current state of a Receiver */
private[streaming] object ReceiverState extends Enumeration {
  type ReceiverState = Value
  val INACTIVE, SCHEDULED, ACTIVE = Value
}

/**
 * Messages used by the NetworkReceiver and the ReceiverTracker to communicate
 * with each other.
 *
 * sealed表明该文件中有ReceiverTrackerMessage的所有子类!
 */
private[streaming] sealed trait ReceiverTrackerMessage
private[streaming] case class RegisterReceiver(
    streamId: Int,
    typ: String,
    host: String,
    executorId: String,
    receiverEndpoint: RpcEndpointRef
  ) extends ReceiverTrackerMessage
private[streaming] case class AddBlock(receivedBlockInfo: ReceivedBlockInfo)
  extends ReceiverTrackerMessage
private[streaming] case class ReportError(streamId: Int, message: String, error: String)
private[streaming] case class DeregisterReceiver(streamId: Int, msg: String, error: String)
  extends ReceiverTrackerMessage

/**
 * Messages used by the driver and ReceiverTrackerEndpoint to communicate locally.
 */
private[streaming] sealed trait ReceiverTrackerLocalMessage

/**
 * This message will trigger ReceiverTrackerEndpoint to restart a Spark job for the receiver.
 */
private[streaming] case class RestartReceiver(receiver: Receiver[_])
  extends ReceiverTrackerLocalMessage

/**
 * This message is sent to ReceiverTrackerEndpoint when we start to launch Spark jobs for receivers
 * at the first time.
 */
private[streaming] case class StartAllReceivers(receiver: Seq[Receiver[_]])
  extends ReceiverTrackerLocalMessage

/**
 * This message will trigger ReceiverTrackerEndpoint to send stop signals to all registered
 * receivers.
 */
private[streaming] case object StopAllReceivers extends ReceiverTrackerLocalMessage

/**
 * A message used by ReceiverTracker to ask all receiver's ids still stored in
 * ReceiverTrackerEndpoint.
 */
private[streaming] case object AllReceiverIds extends ReceiverTrackerLocalMessage

private[streaming] case class UpdateReceiverRateLimit(streamUID: Int, newRate: Long)
  extends ReceiverTrackerLocalMessage

/**
 * This class manages the execution of the receivers of ReceiverInputDStreams. Instance of
 * this class must be created after all input streams have been added and StreamingContext.start()
 * has been called because it needs the final set of input streams at the time of instantiation.
 *
 * ReceiverTracker负责Receiver的管理：启动、回收、执行过程中接收数据的管
 * 理、容错(重启)。
 *
 * ReceiverTacker的初始化在JobScheduler.start中完成
 *
 * @param skipReceiverLaunch Do not launch the receiver. This is useful for testing.
 */
private[streaming]
class ReceiverTracker(ssc: StreamingContext, skipReceiverLaunch: Boolean = false) extends Logging {

	//所有的InputDStream都会首先交给DStreamGraph
  private val receiverInputStreams = ssc.graph.getReceiverInputStreams()
  private val receiverInputStreamIds = receiverInputStreams.map { _.id }
  //
  private val receivedBlockTracker = new ReceivedBlockTracker(
    ssc.sparkContext.conf,
    ssc.sparkContext.hadoopConfiguration,
    receiverInputStreamIds,
    ssc.scheduler.clock,
    ssc.isCheckpointPresent,
    Option(ssc.checkpointDir)
  )
  private val listenerBus = ssc.scheduler.listenerBus

  /** Enumeration to identify current state of the ReceiverTracker */
  object TrackerState extends Enumeration {
    type TrackerState = Value
    val Initialized, Started, Stopping, Stopped = Value
  }
  import TrackerState._

  /** State of the tracker. Protected by "trackerStateLock" */
  @volatile private var trackerState = Initialized

  // endpoint is created when generator starts.
  // This not being null means the tracker has been started and not stopped
  private var endpoint: RpcEndpointRef = null

  //Receiver调度策略
  private val schedulingPolicy = new ReceiverSchedulingPolicy()

  // Track the active receiver job number. When a receiver job exits ultimately, countDown will
  // be called.
  private val receiverJobExitLatch = new CountDownLatch(receiverInputStreams.size)

  /**
   * Track all receivers' information. The key is the receiver id, the value is the receiver info.
   * It's only accessed in ReceiverTrackerEndpoint.
   */
  private val receiverTrackingInfos = new HashMap[Int, ReceiverTrackingInfo]

  /**
   * Store all preferred locations for all receivers. We need this information to schedule
   * receivers. It's only accessed in ReceiverTrackerEndpoint.
   */
  private val receiverPreferredLocations = new HashMap[Int, Option[String]]

  /**
    * Start the endpoint and receiver execution thread.
    * 启动消息通信体同时启动Receiver执行的线程
    *
    * 为什么启动RPC消息通信体？因为ReceiverTracker负责监控集群中的所有Receiver，
    * 集群中的所有Receiver反过来需要像ReceiverTracker汇报状态和接收数据的
    * 信息，同时由ReceiverTracker负责管理Receiver的生命周期。
    *
    * */

  def start(): Unit = synchronized {
    if (isTrackerStarted) {
      throw new SparkException("ReceiverTracker already started")
    }

    /**
      * Receiver的启动是基于输入数据流，若没有InputDStreams则不会启动Receivers，
      * 因此若不需要启动Receiver，将InputDStreams设置为空即可。
      *
      */
    if (!receiverInputStreams.isEmpty) {
      //消息通信体
      endpoint = ssc.env.rpcEnv.setupEndpoint(
        "ReceiverTracker", new ReceiverTrackerEndpoint(ssc.env.rpcEnv))
      //一般为false，测试时使用
      if (!skipReceiverLaunch) launchReceivers()
      logInfo("ReceiverTracker started")
      trackerState = Started
    }
  }

  /** Stop the receiver execution thread. */
  def stop(graceful: Boolean): Unit = synchronized {
    if (isTrackerStarted) {
      // First, stop the receivers
      trackerState = Stopping
      if (!skipReceiverLaunch) {
        // Send the stop signal to all the receivers
        endpoint.askWithRetry[Boolean](StopAllReceivers)

        // Wait for the Spark job that runs the receivers to be over
        // That is, for the receivers to quit gracefully.
        receiverJobExitLatch.await(10, TimeUnit.SECONDS)

        if (graceful) {
          logInfo("Waiting for receiver job to terminate gracefully")
          receiverJobExitLatch.await()
          logInfo("Waited for receiver job to terminate gracefully")
        }

        // Check if all the receivers have been deregistered or not
        val receivers = endpoint.askWithRetry[Seq[Int]](AllReceiverIds)
        if (receivers.nonEmpty) {
          logWarning("Not all of the receivers have deregistered, " + receivers)
        } else {
          logInfo("All of the receivers have deregistered successfully")
        }
      }

      // Finally, stop the endpoint
      ssc.env.rpcEnv.stop(endpoint)
      endpoint = null
      receivedBlockTracker.stop()
      logInfo("ReceiverTracker stopped")
      trackerState = Stopped
    }
  }

  /** Allocate all unallocated blocks to the given batch. */
  def allocateBlocksToBatch(batchTime: Time): Unit = {
    if (receiverInputStreams.nonEmpty) {
      receivedBlockTracker.allocateBlocksToBatch(batchTime)
    }
  }

  /** Get the blocks for the given batch and all input streams. */
  def getBlocksOfBatch(batchTime: Time): Map[Int, Seq[ReceivedBlockInfo]] = {
    receivedBlockTracker.getBlocksOfBatch(batchTime)
  }

  /** Get the blocks allocated to the given batch and stream. */
  def getBlocksOfBatchAndStream(batchTime: Time, streamId: Int): Seq[ReceivedBlockInfo] = {
    receivedBlockTracker.getBlocksOfBatchAndStream(batchTime, streamId)
  }

  /**
   * Clean up the data and metadata of blocks and batches that are strictly
   * older than the threshold time. Note that this does not
   */
  def cleanupOldBlocksAndBatches(cleanupThreshTime: Time) {
    // Clean up old block and batch metadata
    receivedBlockTracker.cleanupOldBatches(cleanupThreshTime, waitForCompletion = false)

    // Signal the receivers to delete old block data
    if (WriteAheadLogUtils.enableReceiverLog(ssc.conf)) {
      logInfo(s"Cleanup old received batch data: $cleanupThreshTime")
      synchronized {
        if (isTrackerStarted) {
          endpoint.send(CleanupOldBlocks(cleanupThreshTime))
        }
      }
    }
  }

  /** Register a receiver */
  private def registerReceiver(
      streamId: Int,
      typ: String,
      host: String,
      executorId: String,
      receiverEndpoint: RpcEndpointRef,
      senderAddress: RpcAddress
    ): Boolean = {
    if (!receiverInputStreamIds.contains(streamId)) {
      throw new SparkException("Register received for unexpected id " + streamId)
    }

    if (isTrackerStopping || isTrackerStopped) {
      return false
    }

    val scheduledLocations = receiverTrackingInfos(streamId).scheduledLocations
    val acceptableExecutors = if (scheduledLocations.nonEmpty) {
        // This receiver is registering and it's scheduled by
        // ReceiverSchedulingPolicy.scheduleReceivers. So use "scheduledLocations" to check it.
        scheduledLocations.get
      } else {
        // This receiver is scheduled by "ReceiverSchedulingPolicy.rescheduleReceiver", so calling
        // "ReceiverSchedulingPolicy.rescheduleReceiver" again to check it.
        scheduleReceiver(streamId)
      }

    def isAcceptable: Boolean = acceptableExecutors.exists {
      case loc: ExecutorCacheTaskLocation => loc.executorId == executorId
      case loc: TaskLocation => loc.host == host
    }

    if (!isAcceptable) {
      // Refuse it since it's scheduled to a wrong executor
      false
    } else {
      val name = s"${typ}-${streamId}"
      val receiverTrackingInfo = ReceiverTrackingInfo(
        streamId,
        ReceiverState.ACTIVE,
        scheduledLocations = None,
        runningExecutor = Some(ExecutorCacheTaskLocation(host, executorId)),
        name = Some(name),
        endpoint = Some(receiverEndpoint))
      receiverTrackingInfos.put(streamId, receiverTrackingInfo)
      listenerBus.post(StreamingListenerReceiverStarted(receiverTrackingInfo.toReceiverInfo))
      logInfo("Registered receiver for stream " + streamId + " from " + senderAddress)
      true
    }
  }

  /** Deregister a receiver */
  private def deregisterReceiver(streamId: Int, message: String, error: String) {
    val lastErrorTime =
      if (error == null || error == "") -1 else ssc.scheduler.clock.getTimeMillis()
    val errorInfo = ReceiverErrorInfo(
      lastErrorMessage = message, lastError = error, lastErrorTime = lastErrorTime)
    val newReceiverTrackingInfo = receiverTrackingInfos.get(streamId) match {
      case Some(oldInfo) =>
        oldInfo.copy(state = ReceiverState.INACTIVE, errorInfo = Some(errorInfo))
      case None =>
        logWarning("No prior receiver info")
        ReceiverTrackingInfo(
          streamId, ReceiverState.INACTIVE, None, None, None, None, Some(errorInfo))
    }
    receiverTrackingInfos(streamId) = newReceiverTrackingInfo
    listenerBus.post(StreamingListenerReceiverStopped(newReceiverTrackingInfo.toReceiverInfo))
    val messageWithError = if (error != null && !error.isEmpty) {
      s"$message - $error"
    } else {
      s"$message"
    }
    logError(s"Deregistered receiver for stream $streamId: $messageWithError")
  }

  /** Update a receiver's maximum ingestion rate */
  def sendRateUpdate(streamUID: Int, newRate: Long): Unit = synchronized {
    if (isTrackerStarted) {
      endpoint.send(UpdateReceiverRateLimit(streamUID, newRate))
    }
  }

  /** Add new blocks for the given stream */
  private def addBlock(receivedBlockInfo: ReceivedBlockInfo): Boolean = {
    receivedBlockTracker.addBlock(receivedBlockInfo)
  }

  /** Report error sent by a receiver */
  private def reportError(streamId: Int, message: String, error: String) {
    val newReceiverTrackingInfo = receiverTrackingInfos.get(streamId) match {
      case Some(oldInfo) =>
        val errorInfo = ReceiverErrorInfo(lastErrorMessage = message, lastError = error,
          lastErrorTime = oldInfo.errorInfo.map(_.lastErrorTime).getOrElse(-1L))
        oldInfo.copy(errorInfo = Some(errorInfo))
      case None =>
        logWarning("No prior receiver info")
        val errorInfo = ReceiverErrorInfo(lastErrorMessage = message, lastError = error,
          lastErrorTime = ssc.scheduler.clock.getTimeMillis())
        ReceiverTrackingInfo(
          streamId, ReceiverState.INACTIVE, None, None, None, None, Some(errorInfo))
    }

    receiverTrackingInfos(streamId) = newReceiverTrackingInfo
    listenerBus.post(StreamingListenerReceiverError(newReceiverTrackingInfo.toReceiverInfo))
    val messageWithError = if (error != null && !error.isEmpty) {
      s"$message - $error"
    } else {
      s"$message"
    }
    logWarning(s"Error reported by receiver for stream $streamId: $messageWithError")
  }

  private def scheduleReceiver(receiverId: Int): Seq[TaskLocation] = {
    val preferredLocation = receiverPreferredLocations.getOrElse(receiverId, None)
    val scheduledLocations = schedulingPolicy.rescheduleReceiver(
      receiverId, preferredLocation, receiverTrackingInfos, getExecutors)
    updateReceiverScheduledExecutors(receiverId, scheduledLocations)
    scheduledLocations
  }

  private def updateReceiverScheduledExecutors(
      receiverId: Int, scheduledLocations: Seq[TaskLocation]): Unit = {
    val newReceiverTrackingInfo = receiverTrackingInfos.get(receiverId) match {
      case Some(oldInfo) =>
        oldInfo.copy(state = ReceiverState.SCHEDULED,
          scheduledLocations = Some(scheduledLocations))
      case None =>
        ReceiverTrackingInfo(
          receiverId,
          ReceiverState.SCHEDULED,
          Some(scheduledLocations),
          runningExecutor = None)
    }
    receiverTrackingInfos.put(receiverId, newReceiverTrackingInfo)
  }

  /** Check if any blocks are left to be processed */
  def hasUnallocatedBlocks: Boolean = {
    receivedBlockTracker.hasUnallocatedReceivedBlocks
  }

  /**
   * Get the list of executors excluding driver
   */
  private def getExecutors: Seq[ExecutorCacheTaskLocation] = {
    if (ssc.sc.isLocal) {
      val blockManagerId = ssc.sparkContext.env.blockManager.blockManagerId
      Seq(ExecutorCacheTaskLocation(blockManagerId.host, blockManagerId.executorId))
    } else {
      ssc.sparkContext.env.blockManager.master.getMemoryStatus.filter { case (blockManagerId, _) =>
        blockManagerId.executorId != SparkContext.DRIVER_IDENTIFIER // Ignore the driver location
      }.map { case (blockManagerId, _) =>
        ExecutorCacheTaskLocation(blockManagerId.host, blockManagerId.executorId)
      }.toSeq
    }
  }

  /**
   * Run the dummy Spark job to ensure that all slaves have registered. This avoids all the
   * receivers to be scheduled on the same node.
   *
   * TODO Should poll the executor number and wait for executors according to
   * "spark.scheduler.minRegisteredResourcesRatio" and
   * "spark.scheduler.maxRegisteredResourcesWaitingTime" rather than running a dummy job.
   */
  private def runDummySparkJob(): Unit = {
    if (!ssc.sparkContext.isLocal) {
      ssc.sparkContext.makeRDD(1 to 50, 50).map(x => (x, 1)).reduceByKey(_ + _, 20).collect()
    }
    assert(getExecutors.nonEmpty)
  }

  /**
   * Get the receivers from the ReceiverInputDStreams, distributes them to the
   * worker nodes as a parallel collection, and runs them.
   * 基于ReceiverInputDStreams获得具体的Receiver实例(在worker中启动了
   * 该Receiver)，然后将其分配到Executor上并行执行。
   *
   * ReceiverInputDStreams是在Driver端，可以认为是Receiver的元数据。
   */
  private def launchReceivers(): Unit = {
    //一个InputDStream只会产生一个Receiver！！
    val receivers = receiverInputStreams.map(nis => {
      //每个具体ReceiverInputDStream中会有Receiver的实现用于运行在Executor
      // 上接收数据
      val rcvr = nis.getReceiver()
      rcvr.setReceiverId(nis.id)
      rcvr
    })
    //确保资源分配的均衡性
    runDummySparkJob()

    logInfo("Starting " + receivers.length + " receivers")
    endpoint.send(StartAllReceivers(receivers))
  }

  /** Check if tracker has been marked for starting */
  private def isTrackerStarted: Boolean = trackerState == Started

  /** Check if tracker has been marked for stopping */
  private def isTrackerStopping: Boolean = trackerState == Stopping

  /** Check if tracker has been marked for stopped */
  private def isTrackerStopped: Boolean = trackerState == Stopped

  /** RpcEndpoint to receive messages from the receivers. */
  private class ReceiverTrackerEndpoint(override val rpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint {

    // TODO Remove this thread pool after https://github.com/apache/spark/issues/7385 is merged
    private val submitJobThreadPool = ExecutionContext.fromExecutorService(
      ThreadUtils.newDaemonCachedThreadPool("submit-job-thread-pool"))

    private val walBatchingThreadPool = ExecutionContext.fromExecutorService(
      ThreadUtils.newDaemonCachedThreadPool("wal-batching-thread-pool"))

    @volatile private var active: Boolean = true

    override def receive: PartialFunction[Any, Unit] = {
      // Local messages 确保Receiver一定被启动，通过作业方式实现！
      case StartAllReceivers(receivers) =>
        //schedulingPolicy负责计算将Receiver可以分配到哪些Executors上
        val scheduledLocations = schedulingPolicy.scheduleReceivers(receivers, getExecutors)
        //每个receiver都用一个作业来启动
        for (receiver <- receivers) {
          //一个Receiver可以运行在多个Executors，因此这里获取哪些Executors可以运行Receiver
          val executors = scheduledLocations(receiver.streamId)
          updateReceiverScheduledExecutors(receiver.streamId, executors)
          receiverPreferredLocations(receiver.streamId) = receiver.preferredLocation
          //确认Receiver的运行位置后，启动Receiver
          startReceiver(receiver, executors)
        }
      case RestartReceiver(receiver) =>
        /**
          * 在重试启动Receiver时会遍历所有Receiver可能运行的Executors最
          * 大程度的保证负责均衡并且增加Receiver运行成功的机会。
          */
        // Old scheduled executors minus the ones that are not active any more
        //不重试，直接将运行失败的Executor减掉
        val oldScheduledExecutors = getStoredScheduledExecutors(receiver.streamId)
        val scheduledLocations = if (oldScheduledExecutors.nonEmpty) {
            // Try global scheduling again
            oldScheduledExecutors
          } else {
            //若可选的Executors为空，则重新进行调度
            val oldReceiverInfo = receiverTrackingInfos(receiver.streamId)
            // Clear "scheduledLocations" to indicate we are going to do local scheduling
            val newReceiverInfo = oldReceiverInfo.copy(
              state = ReceiverState.INACTIVE, scheduledLocations = None)
            receiverTrackingInfos(receiver.streamId) = newReceiverInfo
            schedulingPolicy.rescheduleReceiver(
              receiver.streamId,
              receiver.preferredLocation,
              receiverTrackingInfos,
              getExecutors)
          }
        // Assume there is one receiver restarting at one time, so we don't need to update
        // receiverTrackingInfos
        startReceiver(receiver, scheduledLocations)
      case c: CleanupOldBlocks =>
        receiverTrackingInfos.values.flatMap(_.endpoint).foreach(_.send(c))
      case UpdateReceiverRateLimit(streamUID, newRate) =>
        for (info <- receiverTrackingInfos.get(streamUID); eP <- info.endpoint) {
          eP.send(UpdateRateLimit(newRate))
        }
      // Remote messages
      case ReportError(streamId, message, error) =>
        reportError(streamId, message, error)
    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      // Remote messages
      case RegisterReceiver(streamId, typ, host, executorId, receiverEndpoint) =>
        val successful =
          registerReceiver(streamId, typ, host, executorId, receiverEndpoint, context.senderAddress)
        context.reply(successful)
      case AddBlock(receivedBlockInfo) =>
        if (WriteAheadLogUtils.isBatchingEnabled(ssc.conf, isDriver = true)) {
          walBatchingThreadPool.execute(new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              if (active) {
                context.reply(addBlock(receivedBlockInfo))
              } else {
                throw new IllegalStateException("ReceiverTracker RpcEndpoint shut down.")
              }
            }
          })
        } else {
          context.reply(addBlock(receivedBlockInfo))
        }
      case DeregisterReceiver(streamId, message, error) =>
        deregisterReceiver(streamId, message, error)
        context.reply(true)
      // Local messages
      case AllReceiverIds =>
        context.reply(receiverTrackingInfos.filter(_._2.state != ReceiverState.INACTIVE).keys.toSeq)
      case StopAllReceivers =>
        assert(isTrackerStopping || isTrackerStopped)
        stopReceivers()
        context.reply(true)
    }

    /**
     * Return the stored scheduled executors that are still alive.
     */
    private def getStoredScheduledExecutors(receiverId: Int): Seq[TaskLocation] = {
      if (receiverTrackingInfos.contains(receiverId)) {
        val scheduledLocations = receiverTrackingInfos(receiverId).scheduledLocations
        if (scheduledLocations.nonEmpty) {
          val executors = getExecutors.toSet
          // Only return the alive executors
          scheduledLocations.get.filter {
            case loc: ExecutorCacheTaskLocation => executors(loc)
            case loc: TaskLocation => true
          }
        } else {
          Nil
        }
      } else {
        Nil
      }
    }

    /**
     * Start a receiver along with its scheduled executors
     * 这个意思是：Spark Streaming框架决定运行Receiver在哪个Executor上
     * scheduledLocations是机器级别的而不是Executor级别的！
     */
    private def startReceiver(
        receiver: Receiver[_],
        scheduledLocations: Seq[TaskLocation]): Unit = {
      def shouldStartReceiver: Boolean = {
        // It's okay to start when trackerState is Initialized or Started
        !(isTrackerStopping || isTrackerStopped)
      }

      val receiverId = receiver.streamId
      if (!shouldStartReceiver) {
        onReceiverJobFinish(receiverId)
        return
      }

      val checkpointDirOption = Option(ssc.checkpointDir)
      val serializableHadoopConf =
        new SerializableConfiguration(ssc.sparkContext.hadoopConfiguration)

      // Function to start the receiver on the worker node
      //在具体WorkerNode上启动Receiver，
      val startReceiverFunc: Iterator[Receiver[_]] => Unit =
        (iterator: Iterator[Receiver[_]]) => {
          if (!iterator.hasNext) {
            throw new SparkException(
              "Could not start receiver as object not found.")
          }
          if (TaskContext.get().attemptNumber() == 0) {
            val receiver = iterator.next()
            assert(iterator.hasNext == false)

            //Receiver监控器，同时负责写数据
            val supervisor = new ReceiverSupervisorImpl(
              receiver, SparkEnv.get, serializableHadoopConf.value, checkpointDirOption)
            //启动Receiver
            supervisor.start()
            supervisor.awaitTermination()
          } else {
            // It's restarted by TaskScheduler, but we want to reschedule it again. So exit it.
            //也就是说：重新启动Receiver不是通过失败重试(不会重试)而是通过重新调度作业重新执行
          }
        }

      // Create the RDD using the scheduledLocations to run the receiver in a Spark job
      val receiverRDD: RDD[Receiver[_]] =
        if (scheduledLocations.isEmpty) {
          ssc.sc.makeRDD(Seq(receiver), 1)
        } else {
          val preferredLocations = scheduledLocations.map(_.toString).distinct
          ssc.sc.makeRDD(Seq(receiver -> preferredLocations))
        }
      //说明receiverRDD中只有一个Receiver
      receiverRDD.setName(s"Receiver $receiverId")
      ssc.sparkContext.setJobDescription(s"Streaming job running receiver $receiverId")
      ssc.sparkContext.setCallSite(Option(ssc.getStartSite()).getOrElse(Utils.getCallSite()))
      /**
        * 每一个Receiver启动都是运行一个Spark Core的Job作业来启动，改作
        * 业会运行startReceiverFunc函数并调用supervisor.start()方法该
        * 方法内部会启动Receiver，Receiver.start启动后启动一个线程用于接
        * 收数据。
        *
        * 为什么要启动一个Spark Core作业来启动Receiver？
        * 1、确保Receiver一定会被启动，下面代码可以看到当Receiver启动失败
        * 时会不断进行重启，而Spark Core的Task重启次数是一定的，若一直没有
        * 启动成功就需要通过发送RestartReceiver再次重新启动，即Job可以无
        * 限重复启动！
        * 2、一个一个的Job负责启动一个Receiver实例，比较平均，一般情况下不
        * 大可能出现两个Receiver在同一个机器上，如果是Task则很有可能的。可
        * 以保证Receiver吞吐量比较强。
        *
        * ReceiverTracker和Receiver是master和slave的关系!
        *
        * 问题：假设Spark Streaming应用程序有10个InputDStream，就会产生
        * 10个Receiver，这里的submitJob中的作业是启动一个Receiver还是一
        * 次性把所有的Receiver一起启动？
        * 由于receiverRDD中只有一个元素，因此每一次提交一个Spark Job只是
        * 启动一个Receiver。
        * 采用一个Job把所有Receiver都启动的问题：
        * 1、多个Receiver可能运行在同一个Executor上，分布不均、数据倾斜；
        * 2、Job失败导致所有Receiver失败；
        * 而每个Receiver采用一个Job方式启动避免了上述的两个问题且保证一定会
        * 启动Receiver，并且在重试启动Receiver时会遍历所有Receiver可能运
        * 行的Executors最大程度的保证负责均衡并且增加Receiver运行成功的机会。
        */
      val future = ssc.sparkContext.submitJob[Receiver[_], Unit, Unit](
        receiverRDD, startReceiverFunc, Seq(0), (_, _) => Unit, ())
      // We will keep restarting the receiver job until ReceiverTracker is stopped
      future.onComplete {
        case Success(_) =>
          if (!shouldStartReceiver) {
            onReceiverJobFinish(receiverId)
          } else {
            logInfo(s"Restarting Receiver $receiverId")
            self.send(RestartReceiver(receiver))
          }
        case Failure(e) =>
          if (!shouldStartReceiver) {
            onReceiverJobFinish(receiverId)
          } else {
            logError("Receiver has been stopped. Try to restart it.", e)
            logInfo(s"Restarting Receiver $receiverId")
            self.send(RestartReceiver(receiver))
          }
          //采用线程池可以并发启动Receiver，因为各个Receiver之间没有关联，因此可以并发启动；
      }(submitJobThreadPool)
      logInfo(s"Receiver ${receiver.streamId} started")
    }

    override def onStop(): Unit = {
      submitJobThreadPool.shutdownNow()
      active = false
      walBatchingThreadPool.shutdown()
    }

    /**
     * Call when a receiver is terminated. It means we won't restart its Spark job.
     */
    private def onReceiverJobFinish(receiverId: Int): Unit = {
      receiverJobExitLatch.countDown()
      receiverTrackingInfos.remove(receiverId).foreach { receiverTrackingInfo =>
        if (receiverTrackingInfo.state == ReceiverState.ACTIVE) {
          logWarning(s"Receiver $receiverId exited but didn't deregister")
        }
      }
    }

    /** Send stop signal to the receivers. */
    private def stopReceivers() {
      receiverTrackingInfos.values.flatMap(_.endpoint).foreach { _.send(StopReceiver) }
      logInfo("Sent stop signal to all " + receiverTrackingInfos.size + " receivers")
    }
  }

}
