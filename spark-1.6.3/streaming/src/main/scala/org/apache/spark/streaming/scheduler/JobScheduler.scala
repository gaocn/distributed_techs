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

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.collection.JavaConverters._
import scala.util.Failure

import org.apache.spark.Logging
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.streaming._
import org.apache.spark.streaming.ui.UIUtils
import org.apache.spark.util.{EventLoop, ThreadUtils, Utils}


private[scheduler] sealed trait JobSchedulerEvent
private[scheduler] case class JobStarted(job: Job, startTime: Long) extends JobSchedulerEvent
private[scheduler] case class JobCompleted(job: Job, completedTime: Long) extends JobSchedulerEvent
private[scheduler] case class ErrorReported(msg: String, e: Throwable) extends JobSchedulerEvent

/**
 * This class schedules jobs to be run on Spark. It uses the JobGenerator to generate
 * the jobs and runs them using a thread pool.
 *
 * JobScheduler是Spark Streaming作业的调度者，是完成Spark Streaming和
 * Spark Core衔接的控制点，即在Spark Streaming生成作业后由JobScheduler
 * 交给Spark Core执行。
 *
 * Spark Core中生成作业的是SparkContext，而SparkStreaming中生成作业的是
 * JobGenerator。JobGenerator是根据RDD生成作业，而RDD中是有数据来源的，
 * 这就需要BlockManager管理器用于获取操作数据的元数据，Spark Streaming作
 * 为上层抽象框架，提出了ReceiverTracker底层封装了BlockManager。
 *
 * Spark Streaming作业最后会调用Spark Context运行作业！
 *
 */
private[streaming]
class JobScheduler(val ssc: StreamingContext) extends Logging {

  // Use of ConcurrentHashMap.keySet later causes an odd runtime problem due to Java 7/8 diff
  // https://gist.github.com/AlainODea/1375759b8720a3f9f094
  //每个Batch Duration对应生成的作业，因为输出可能有多个，所以生成的作业就会多个
  private val jobSets: java.util.Map[Time, JobSet] = new ConcurrentHashMap[Time, JobSet]
  /**
    * 从Spark Streaming Context中获取并发作业的配置，在Spark Core上可
    * 以同时运行多个作业，采用多线程实现，每个Job用一个单独线程运行！超过并发
    * 作业数时会进入队列中，Job按照FIFO进行调度。
    *
    * Spark Core的FIFO调度策略是应用程序级别的！这里是同一个应用程序内部的
    * 多个Job并发运行！
    */
  private val numConcurrentJobs = ssc.conf.getInt("spark.streaming.concurrentJobs", 1)
  private val jobExecutor =
    ThreadUtils.newDaemonFixedThreadPool(numConcurrentJobs, "streaming-job-executor")
  private val jobGenerator = new JobGenerator(this)
  val clock = jobGenerator.clock
  //Spark Streaming监控作业的工具
  val listenerBus = new StreamingListenerBus()

  // These two are created only when scheduler starts.
  // eventLoop not being null means the scheduler has been started and not stopped
  var receiverTracker: ReceiverTracker = null
  // A tracker to track all the input stream information as well as processed record number
  var inputInfoTracker: InputInfoTracker = null

  private var eventLoop: EventLoop[JobSchedulerEvent] = null

  def start(): Unit = synchronized {
    if (eventLoop != null) return // scheduler has already been started

    logDebug("Starting JobScheduler")
    eventLoop = new EventLoop[JobSchedulerEvent]("JobScheduler") {
      override protected def onReceive(event: JobSchedulerEvent): Unit = processEvent(event)

      override protected def onError(e: Throwable): Unit = reportError("Error in job scheduler", e)
    }
    eventLoop.start()

    // attach rate controllers of input streams to receive batch completion updates
    for {
      inputDStream <- ssc.graph.getInputStreams
      rateController <- inputDStream.rateController
    } ssc.addStreamingListener(rateController)

    listenerBus.start(ssc.sparkContext)
    receiverTracker = new ReceiverTracker(ssc)
    inputInfoTracker = new InputInfoTracker(ssc)
    receiverTracker.start()
    jobGenerator.start()
    logInfo("Started JobScheduler")
  }

  def stop(processAllReceivedData: Boolean): Unit = synchronized {
    if (eventLoop == null) return // scheduler has already been stopped
    logDebug("Stopping JobScheduler")

    if (receiverTracker != null) {
      // First, stop receiving
      receiverTracker.stop(processAllReceivedData)
    }

    // Second, stop generating jobs. If it has to process all received data,
    // then this will wait for all the processing through JobScheduler to be over.
    jobGenerator.stop(processAllReceivedData)

    // Stop the executor for receiving new jobs
    logDebug("Stopping job executor")
    jobExecutor.shutdown()

    // Wait for the queued jobs to complete if indicated
    val terminated = if (processAllReceivedData) {
      jobExecutor.awaitTermination(1, TimeUnit.HOURS)  // just a very large period of time
    } else {
      jobExecutor.awaitTermination(2, TimeUnit.SECONDS)
    }
    if (!terminated) {
      jobExecutor.shutdownNow()
    }
    logDebug("Stopped job executor")

    // Stop everything else
    listenerBus.stop()
    eventLoop.stop()
    eventLoop = null
    logInfo("Stopped JobScheduler")
  }

  def submitJobSet(jobSet: JobSet) {
    if (jobSet.jobs.isEmpty) {
      //什么时候没有Job？没有数据时也会生成Job，除非graph.generateJob生
      // 成失败，注意这个Job不是Spark Core的Job只是一个Spark Streaming
      // 中的作业的抽象！
      logInfo("No jobs added for time " + jobSet.time)
    } else {
      listenerBus.post(StreamingListenerBatchSubmitted(jobSet.toBatchInfo))
      jobSets.put(jobSet.time, jobSet)
      //Spark Core中没有Job这个概念，而Spark Streaming中的Job只是负责
      // 封装业务逻辑，然后用于构造JobHandler交给线程去运行，而真正执行的是
      //是Spark Core中的Job，这个Job是我们看不到的但确是真正起作用的！
      //JobHandler就是Runnable接口，进一步说明Job就是业务逻辑代表了RDD的依赖关系
      jobSet.jobs.foreach(job => jobExecutor.execute(new JobHandler(job)))
      logInfo("Added jobs for time " + jobSet.time)
    }
  }

  def getPendingTimes(): Seq[Time] = {
    jobSets.asScala.keys.toSeq
  }

  def reportError(msg: String, e: Throwable) {
    eventLoop.post(ErrorReported(msg, e))
  }

  def isStarted(): Boolean = synchronized {
    eventLoop != null
  }

  private def processEvent(event: JobSchedulerEvent) {
    try {
      event match {
        case JobStarted(job, startTime) => handleJobStart(job, startTime)
        case JobCompleted(job, completedTime) => handleJobCompletion(job, completedTime)
        case ErrorReported(m, e) => handleError(m, e)
      }
    } catch {
      case e: Throwable =>
        reportError("Error in job scheduler", e)
    }
  }

  private def handleJobStart(job: Job, startTime: Long) {
    val jobSet = jobSets.get(job.time)
    val isFirstJobOfJobSet = !jobSet.hasStarted
    jobSet.handleJobStart(job)
    if (isFirstJobOfJobSet) {
      // "StreamingListenerBatchStarted" should be posted after calling "handleJobStart" to get the
      // correct "jobSet.processingStartTime".
      listenerBus.post(StreamingListenerBatchStarted(jobSet.toBatchInfo))
    }
    job.setStartTime(startTime)
    listenerBus.post(StreamingListenerOutputOperationStarted(job.toOutputOperationInfo))
    logInfo("Starting job " + job.id + " from job set of time " + jobSet.time)
  }

  private def handleJobCompletion(job: Job, completedTime: Long) {
    val jobSet = jobSets.get(job.time)
    jobSet.handleJobCompletion(job)
    job.setEndTime(completedTime)
    listenerBus.post(StreamingListenerOutputOperationCompleted(job.toOutputOperationInfo))
    logInfo("Finished job " + job.id + " from job set of time " + jobSet.time)
    if (jobSet.hasCompleted) {
      jobSets.remove(jobSet.time)
      jobGenerator.onBatchCompletion(jobSet.time)
      logInfo("Total delay: %.3f s for time %s (execution: %.3f s)".format(
        jobSet.totalDelay / 1000.0, jobSet.time.toString,
        jobSet.processingDelay / 1000.0
      ))
      listenerBus.post(StreamingListenerBatchCompleted(jobSet.toBatchInfo))
    }
    job.result match {
      case Failure(e) =>
        reportError("Error running job " + job, e)
      case _ =>
    }
  }

  private def handleError(msg: String, e: Throwable) {
    logError(msg, e)
    ssc.waiter.notifyError(e)
  }

  private class JobHandler(job: Job) extends Runnable with Logging {
    import JobScheduler._

    def run() {
      try {
        val formattedTime = UIUtils.formatBatchTime(
          job.time.milliseconds, ssc.graph.batchDuration.milliseconds, showYYYYMMSS = false)
        val batchUrl = s"/streaming/batch/?id=${job.time.milliseconds}"
        val batchLinkText = s"[output operation ${job.outputOpId}, batch time ${formattedTime}]"

        ssc.sc.setJobDescription(
          s"""Streaming job from <a href="$batchUrl">$batchLinkText</a>""")
        ssc.sc.setLocalProperty(BATCH_TIME_PROPERTY_KEY, job.time.milliseconds.toString)
        ssc.sc.setLocalProperty(OUTPUT_OP_ID_PROPERTY_KEY, job.outputOpId.toString)

        // We need to assign `eventLoop` to a temp variable. Otherwise, because
        // `JobScheduler.stop(false)` may set `eventLoop` to null when this method is running, then
        // it's possible that when `post` is called, `eventLoop` happens to null.
        var _eventLoop = eventLoop
        if (_eventLoop != null) {
          _eventLoop.post(JobStarted(job, clock.getTimeMillis()))
          // Disable checks for existing output directories in jobs launched by the streaming
          // scheduler, since we may need to write output to an existing directory during checkpoint
          // recovery; see SPARK-4835 for more details.
          PairRDDFunctions.disableOutputSpecValidation.withValue(true) {
            job.run()
          }
          _eventLoop = eventLoop
          if (_eventLoop != null) {
            _eventLoop.post(JobCompleted(job, clock.getTimeMillis()))
          }
        } else {
          // JobScheduler has been stopped.
        }
      } finally {
        ssc.sc.setLocalProperty(JobScheduler.BATCH_TIME_PROPERTY_KEY, null)
        ssc.sc.setLocalProperty(JobScheduler.OUTPUT_OP_ID_PROPERTY_KEY, null)
      }
    }
  }
}

private[streaming] object JobScheduler {
  val BATCH_TIME_PROPERTY_KEY = "spark.streaming.internal.batchTime"
  val OUTPUT_OP_ID_PROPERTY_KEY = "spark.streaming.internal.outputOpId"
}
