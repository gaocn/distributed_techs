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

package org.apache.spark.executor

import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.hadoop.conf.Configuration

import scala.collection.mutable
import scala.util.{Failure, Success}

import org.apache.spark.rpc._
import org.apache.spark._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.worker.WorkerWatcher
import org.apache.spark.scheduler.TaskDescription
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.util.{ThreadUtils, SignalLogger, Utils}

private[spark] class CoarseGrainedExecutorBackend(
    override val rpcEnv: RpcEnv,
    driverUrl: String,
    executorId: String,
    hostPort: String,
    cores: Int,
    userClassPath: Seq[URL],
    env: SparkEnv)
  extends ThreadSafeRpcEndpoint with ExecutorBackend with Logging {

  private[this] val stopping = new AtomicBoolean(false)
  var executor: Executor = null
  @volatile var driver: Option[RpcEndpointRef] = None

  // If this CoarseGrainedExecutorBackend is changed to support multiple threads, then this may need
  // to be changed so that we don't share the serializer instance across threads
  private[this] val ser: SerializerInstance = env.closureSerializer.newInstance()

  override def onStart() {
    logInfo("Connecting to driver: " + driverUrl)
    rpcEnv.asyncSetupEndpointRefByURI(driverUrl).flatMap { ref =>
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      driver = Some(ref)
      ref.ask[RegisterExecutorResponse](
        RegisterExecutor(executorId, self, hostPort, cores, extractLogUrls))
    }(ThreadUtils.sameThread).onComplete {
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      case Success(msg) => Utils.tryLogNonFatalError {
        Option(self).foreach(_.send(msg)) // msg must be RegisterExecutorResponse
      }
      case Failure(e) => {
        logError(s"Cannot register with driver: $driverUrl", e)
        System.exit(1)
      }
    }(ThreadUtils.sameThread)
  }

  def extractLogUrls: Map[String, String] = {
    val prefix = "SPARK_LOG_URL_"
    sys.env.filterKeys(_.startsWith(prefix))
      .map(e => (e._1.substring(prefix.length).toLowerCase, e._2))
  }

  override def receive: PartialFunction[Any, Unit] = {
    case RegisteredExecutor(hostname) =>
      logInfo("Successfully registered with driver")
      executor = new Executor(executorId, hostname, env, userClassPath, isLocal = false)

    case RegisterExecutorFailed(message) =>
      logError("Slave registration failed: " + message)
      System.exit(1)

    case LaunchTask(data) =>
      if (executor == null) {
        logError("Received LaunchTask command but executor was null")
        System.exit(1)
      } else {
        val taskDesc = ser.deserialize[TaskDescription](data.value)
        logInfo("Got assigned task " + taskDesc.taskId)
        executor.launchTask(this, taskId = taskDesc.taskId, attemptNumber = taskDesc.attemptNumber,
          taskDesc.name, taskDesc.serializedTask)
      }

    case KillTask(taskId, _, interruptThread) =>
      if (executor == null) {
        logError("Received KillTask command but executor was null")
        System.exit(1)
      } else {
        executor.killTask(taskId, interruptThread)
      }

    case StopExecutor =>
      stopping.set(true)
      logInfo("Driver commanded a shutdown")
      // Cannot shutdown here because an ack may need to be sent back to the caller. So send
      // a message to self to actually do the shutdown.
      self.send(Shutdown)

    case Shutdown =>
      stopping.set(true)
      executor.stop()
      stop()
      rpcEnv.shutdown()
  }

  override def onDisconnected(remoteAddress: RpcAddress): Unit = {
    if (stopping.get()) {
      logInfo(s"Driver from $remoteAddress disconnected during shutdown")
    } else if (driver.exists(_.address == remoteAddress)) {
      logError(s"Driver $remoteAddress disassociated! Shutting down.")
      System.exit(1)
    } else {
      logWarning(s"An unknown ($remoteAddress) driver disconnected.")
    }
  }

  override def statusUpdate(taskId: Long, state: TaskState, data: ByteBuffer) {
    val msg = StatusUpdate(executorId, taskId, state, data)
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }
}

private[spark] object CoarseGrainedExecutorBackend extends Logging {

  private def run(
     /**
      * 问题：CoarseGrainExecutorBackend在启动的时候会向driverUrl所代表的endpoint进行注册，这个driverUrl代表的endpoint到底实体是谁？（即ClientEndpoint和DriverEndpoint如何诞生如何通信）
      * 背景：集群启动的时候启动了Master(s)和Workers。
整个注册过程全流程分析：
第一步：创建SparkContext，其中会创建DAGScheduler,TaskSchedulerImpl和SparkDeploySchedulerBackend实例，并调用TaskSchedulerImpl.start启动
第二步：TaskSchedulerImpl.start启动会调用SparkDeploySchedulerBackend.start方法（standalone模式下）
——（DriverEndpoint的诞生）SparkDeploySchedulerBackend会首先调用父类CoarseGrainedSchedulerBackend.start方法创建DriverEndpoint实例用于进行周期性的资源调度和资源管理（在实例化时根据Spark RPC的消息工作机制会调用生命周期方法onStart，该方法会周期性给自己发送RiverOffer空消息用于Make fake resource offers on all executors）；
——（ClientEndpoint的诞生）然后调用SparkDeploySchedulerBackend本身的start方法创建AppClient实例，在AppClient实例内部会创建ClientEndpoint实例并调用RPC消息体的onStart方法用于向Masters注册应用程序。
——Master如何向Worker发送指令启动CoarseGrainedExecutorBackend？Master完成应用程序注册后会调用schedule方法，该方法会调用startExecutorsOnWorkers方法，其中会调用scheduleExecutorsOnWorkers和allocateWorkerResourceToExecutor方法，在allocateWorkerResourceToExecutor方法中会调用launchExecutor方法发送两条消息：LaunchExecutor(给Worker)和ExecutorAdded（给ClientEndpoint）。当Worker接收到LaunchExecutor后会调用创建ExecutorRunner内部通过线程方式创建CoarseGrainExecutorBackend进程其中的Executor线程池用于执行具体任务，最后Worker发送消息ExecutorStateChanged给Master通知Executor状态变化；当ClientEndpoint接收到ExecutorAdded消息形式上Executor添加成功（之所以称为因为此时是Master把Executor的信息交给ClientEndpoint，此时CoarseGrainedExecutorBackend并没有实例化，真正实例化过程是Worker进程接收到LaunchExecutor后完成的！）。

第三步：启动CoarseGrainExecutorBackend的过程分析
在CoarseGrainExecutorBackend启动的时候，main方法会通过args match获得"driver-url"参数，在main中会调用run方法其中会根据"driver-url"获取RPCEndpoint的代理句柄，而此时CoarseGrainExecutorBackend在启动后最终会注册给"driver-url"所代表的远程RPCEndpoint对象实例。而"--driver-url"参数是在SparkDeploySchedulerBackend.start方法中设置的，并由AppClient在注册应用程序是传送给Master，而后由Master传送给Worker来创建CoarseGrainExecutorBackend时用的，其值为CoarseGrainedSchedulerBackend.ENDPOINT_NAME="CoarseGrainedScheduler"，由于driverEndpoint在rpcEnv中注册的Endpoint名称为"CoarseGrainedScheduler"，而clientEndpoint在rpcEnv中注册的名称为"AppClient"，所以CoarseGrainExecutorBackend要通信的对象是driverEndpoint，而不是clientEndpoint。
第四步：在CoarseGrainExecutorBackend向DriverEndpoint发送RegiesterExecutor消息后，并接收DriverEndpoint回复的RegisteredExecutor后就会完成Executor线程池的创建。
      */
      driverUrl: String, //这里的driverUrl不是ClientEndpoint，而是代表DriverEndpoint所在的url
      executorId: String,
      hostname: String,
      cores: Int,
      appId: String,
      workerUrl: Option[String],
      userClassPath: Seq[URL]) {

    SignalLogger.register(log)

    SparkHadoopUtil.get.runAsSparkUser { () =>
      // Debug code
      Utils.checkHost(hostname)

      // Bootstrap to fetch the driver's Spark properties.
      val executorConf = new SparkConf
      val port = executorConf.getInt("spark.executor.port", 0)
      val fetcher = RpcEnv.create(
        "driverPropsFetcher",
        hostname,
        port,
        executorConf,
        new SecurityManager(executorConf),
        clientMode = true)
      //指向DriverEndpoint所代表的RPCEndpoint
      val driver = fetcher.setupEndpointRefByURI(driverUrl)
      val props = driver.askWithRetry[Seq[(String, String)]](RetrieveSparkProps) ++
        Seq[(String, String)](("spark.app.id", appId))
      fetcher.shutdown()

      // Create SparkEnv using properties we fetched from the driver.
      //SparkEnv的相关参数需要从Driver中获取保证SparkEnv的配置一致
      val driverConf = new SparkConf()
      for ((key, value) <- props) {
        // this is required for SSL in standalone mode
        if (SparkConf.isExecutorStartupConf(key)) {
          driverConf.setIfMissing(key, value)
        } else {
          driverConf.set(key, value)
        }
      }
      if (driverConf.contains("spark.yarn.credentials.file")) {
        logInfo("Will periodically update credentials from: " +
          driverConf.get("spark.yarn.credentials.file"))
        SparkHadoopUtil.get.startExecutorDelegationTokenRenewer(driverConf)
      }
      //利用SparkEnv的静态方法创建ExecutorBackend中的SparkEnv
      val env = SparkEnv.createExecutorEnv(
        driverConf, executorId, hostname, port, cores, isLocal = false)

      // SparkEnv will set spark.executor.port if the rpc env is listening for incoming
      // connections (e.g., if it's using akka). Otherwise, the executor is running in
      // client mode only, and does not accept incoming connections.
      val sparkHostPort = env.conf.getOption("spark.executor.port").map { port =>
          hostname + ":" + port
        }.orNull
      //创建消息循环体
      env.rpcEnv.setupEndpoint("Executor", new CoarseGrainedExecutorBackend(
        env.rpcEnv, driverUrl, executorId, sparkHostPort, cores, userClassPath, env))
      workerUrl.foreach { url =>
        env.rpcEnv.setupEndpoint("WorkerWatcher", new WorkerWatcher(env.rpcEnv, url))
      }
      //由此可以知道 整个Backend的启动及结束都是被SparkEnv对象实例所管理的
      //SparkEnv不是Master/Slave结构，因为M/S结构一般都会有状态变化，而SparkEnv创建后不会改变！
      //Driver和Executor上都有SparkEnv但不是M/S结构，它们都需要读取系统的配置，在配置完成后对象实例不变
      //虽然SparkEnv分为两种类型，但本质上是一样的,所以在SparkEnv源码注释中说是全局唯一，这里的全局唯一是隐性的全局唯一
      env.rpcEnv.awaitTermination()
      SparkHadoopUtil.get.stopExecutorDelegationTokenRenewer()
    }
  }

  def main(args: Array[String]) {
    var driverUrl: String = null
    var executorId: String = null
    var hostname: String = null
    var cores: Int = 0
    var appId: String = null
    var workerUrl: Option[String] = None
    val userClassPath = new mutable.ListBuffer[URL]()

    var argv = args.toList
    while (!argv.isEmpty) {
      argv match {
        case ("--driver-url") :: value :: tail =>
          //这里的driverUrl不是ClientEndpoint，而是代表DriverEndpoint所在的url
          //注意：这里的driver-url参数是来自
          driverUrl = value
          argv = tail
        case ("--executor-id") :: value :: tail =>
          executorId = value
          argv = tail
        case ("--hostname") :: value :: tail =>
          hostname = value
          argv = tail
        case ("--cores") :: value :: tail =>
          cores = value.toInt
          argv = tail
        case ("--app-id") :: value :: tail =>
          appId = value
          argv = tail
        case ("--worker-url") :: value :: tail =>
          // Worker url is used in spark standalone mode to enforce fate-sharing with worker
          workerUrl = Some(value)
          argv = tail
        case ("--user-class-path") :: value :: tail =>
          userClassPath += new URL(value)
          argv = tail
        case Nil =>
        case tail =>
          // scalastyle:off println
          System.err.println(s"Unrecognized options: ${tail.mkString(" ")}")
          // scalastyle:on println
          printUsageAndExit()
      }
    }

    if (driverUrl == null || executorId == null || hostname == null || cores <= 0 ||
      appId == null) {
      printUsageAndExit()
    }

    run(driverUrl, executorId, hostname, cores, appId, workerUrl, userClassPath)
  }

  private def printUsageAndExit() = {
    // scalastyle:off println
    System.err.println(
      """
      |"Usage: CoarseGrainedExecutorBackend [options]
      |
      | Options are:
      |   --driver-url <driverUrl>
      |   --executor-id <executorId>
      |   --hostname <hostname>
      |   --cores <cores>
      |   --app-id <appid>
      |   --worker-url <workerUrl>
      |   --user-class-path <url>
      |""".stripMargin)
    // scalastyle:on println
    System.exit(1)
  }

}
