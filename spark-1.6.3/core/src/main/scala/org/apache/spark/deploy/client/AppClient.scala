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

package org.apache.spark.deploy.client

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Future => JFuture, ScheduledFuture => JScheduledFuture}

import scala.util.control.NonFatal

import org.apache.spark.{Logging, SparkConf}
import org.apache.spark.deploy.{ApplicationDescription, ExecutorState}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.master.Master
import org.apache.spark.rpc._
import org.apache.spark.util.{RpcUtils, ThreadUtils, Utils}

/**
 * Interface allowing applications to speak with a Spark deploy cluster. Takes a master URL,
 * an app description, and a listener for cluster events, and calls back the listener when various
 * events occur.
 *
 *AppClient为应用程序与Spark集群通信的接口，因此需要有masterUrls标识通信地址
 *
 * @param masterUrls Each url should look like spark://host:port.
 */
private[spark] class AppClient(
    rpcEnv: RpcEnv,
    masterUrls: Array[String],
    appDescription: ApplicationDescription,
    listener: AppClientListener,
    conf: SparkConf)
  extends Logging {

  //（在HA Spark集群中）实际上发生作用的只有一个，即处于Active状态的Master
  private val masterRpcAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))

  private val REGISTRATION_TIMEOUT_SECONDS = 20
  private val REGISTRATION_RETRIES = 3

  private val endpoint = new AtomicReference[RpcEndpointRef]
  private val appId = new AtomicReference[String]
  private val registered = new AtomicBoolean(false) //是否被注册

  /**
    * AppClient有一个内部类
    */
  private class ClientEndpoint(override val rpcEnv: RpcEnv) extends ThreadSafeRpcEndpoint
    with Logging {
    //连接的Master
    private var master: Option[RpcEndpointRef] = None
    // To avoid calling listener.disconnected() multiple times
    private var alreadyDisconnected = false
    // To avoid calling listener.dead() multiple times
    private val alreadyDead = new AtomicBoolean(false)
    private val registerMasterFutures = new AtomicReference[Array[JFuture[_]]]
    private val registrationRetryTimer = new AtomicReference[JScheduledFuture[_]]

    /**
      * 所谓的高效程序：从物理层面讲就是消息驱动的多线程通信模式 + 缓存！！！
      * Spark以前的代码不是通过线程池进行注册的，而是直接注册（占用主线程，接收消息时会被阻塞）。
      */

    // A thread pool for registering with masters. Because registering with a master is a blocking
    // action, this thread pool must be able to create "masterRpcAddresses.size" threads at the same
    // time so that we can register with all masters.
    private val registerMasterThreadPool = ThreadUtils.newDaemonCachedThreadPool(
      "appclient-register-master-threadpool",
      masterRpcAddresses.length // Make sure we can register with all masters at the same time
    )

    // A scheduled executor for scheduling the registration actions
    private val registrationRetryThread =
      ThreadUtils.newDaemonSingleThreadScheduledExecutor("appclient-registration-retry-thread")

    // A thread pool to perform receive then reply actions in a thread so as not to block the
    // event loop.
    private val askAndReplyThreadPool =
      ThreadUtils.newDaemonCachedThreadPool("appclient-receive-and-reply-threadpool")

    override def onStart(): Unit = {
      try {
        registerWithMaster(1)
      } catch {
        case e: Exception =>
          logWarning("Failed to connect to master", e)
          markDisconnected()
          stop()
      }
    }

    /**
     *  Register with all masters asynchronously and returns an array `Future`s for cancellation.
     *  为什么用线程池而不是用线程？可以确保所有Master的注册程序同时发起！
     */
    private def tryRegisterAllMasters(): Array[JFuture[_]] = {
      for (masterAddress <- masterRpcAddresses) yield { //yield生成一个Array集合
        registerMasterThreadPool.submit(new Runnable {
          override def run(): Unit = try {
            if (registered.get) { //若已经注册，则退出，只能注册一个Master
              return
            }
            logInfo("Connecting to master " + masterAddress.toSparkURL + "...")
            //代理模式，获取Master远程句柄的引用
            val masterRef =
              rpcEnv.setupEndpointRef(Master.SYSTEM_NAME, masterAddress, Master.ENDPOINT_NAME)
            //注册完成后发送消息：应用程序注册
            //为什么发送消息只有两个参数？第一个是应用程序本身，第二个参数为ClientEndpoint，为什么传递这个参数？因为Master要回复消息！
            masterRef.send(RegisterApplication(appDescription, self))
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
          }
        })
      }
    }

    /**
     * 异步注册（分布式即代表：消息通信+异步）
     * Register with all masters asynchronously. It will call `registerWithMaster` every
     * REGISTRATION_TIMEOUT_SECONDS seconds until exceeding REGISTRATION_RETRIES times.
     * 连上之后，其余工作就由Master指挥，所以其他正在注册工作就可以被取消。
     * Once we connect to a master successfully, all scheduling work and Futures will be cancelled.
     *
     * nthRetry means this is the nth attempt to register with master.
     */
    private def registerWithMaster(nthRetry: Int) {
      //JFuture的原子引用，表示一系列进行实际注册的线程结果
      registerMasterFutures.set(tryRegisterAllMasters())
      //定时器，按照固定频率（超时时间）循环调用注册程序
      registrationRetryTimer.set(registrationRetryThread.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = {
          if (registered.get) {
            //连接成功，取消其他注册程序
            registerMasterFutures.get.foreach(_.cancel(true))
            //关闭线程池
            registerMasterThreadPool.shutdownNow()
          } else if (nthRetry >= REGISTRATION_RETRIES) {
            //重试次数超过3次，则表示Master没有响应
            markDead("All masters are unresponsive! Giving up.")
          } else {
            //若没有重试超过3次，则要么正在重试连接
            registerMasterFutures.get.foreach(_.cancel(true))
            registerWithMaster(nthRetry + 1)
          }
        }
      }, REGISTRATION_TIMEOUT_SECONDS, REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    /**
     * Send a message to the current master. If we have not yet registered successfully with any
     * master, the message will be dropped.
     */
    private def sendToMaster(message: Any): Unit = {
      master match {
        case Some(masterRef) => masterRef.send(message)
        case None => logWarning(s"Drop $message because has not yet connected to master")
      }
    }

    private def isPossibleMaster(remoteAddress: RpcAddress): Boolean = {
      masterRpcAddresses.contains(remoteAddress)
    }

    override def receive: PartialFunction[Any, Unit] = {
      case RegisteredApplication(appId_, masterRef) =>
        // FIXME How to handle the following cases?
        // 1. A master receives multiple registrations and sends back multiple
        // RegisteredApplications due to an unstable network.
        // 2. Receive multiple RegisteredApplication from different masters because the master is
        // changing.
        //这两个问题实际上所有分布式系统设计中都没有解决，简单方式是维护一个集群级别的全局变量！
        //但是这样做比较多事！！ 例如C语言为什么总有指针溢出，为什么做边界检查？ --》因为效率原因！！！
        appId.set(appId_)
        registered.set(true)
        master = Some(masterRef)
        listener.connected(appId.get)

      case ApplicationRemoved(message) =>
        markDead("Master removed our application: %s".format(message))
        stop()

      case ExecutorAdded(id: Int, workerId: String, hostPort: String, cores: Int, memory: Int) =>
        val fullId = appId + "/" + id
        logInfo("Executor added: %s on %s (%s) with %d cores".format(fullId, workerId, hostPort,
          cores))
        listener.executorAdded(fullId, workerId, hostPort, cores, memory)

      case ExecutorUpdated(id, state, message, exitStatus) =>
        val fullId = appId + "/" + id
        val messageText = message.map(s => " (" + s + ")").getOrElse("")
        logInfo("Executor updated: %s is now %s%s".format(fullId, state, messageText))
        if (ExecutorState.isFinished(state)) {
          listener.executorRemoved(fullId, message.getOrElse(""), exitStatus)
        }

      case MasterChanged(masterRef, masterWebUiUrl) =>
        logInfo("Master has changed, new master is at " + masterRef.address.toSparkURL)
        master = Some(masterRef)
        alreadyDisconnected = false
        masterRef.send(MasterChangeAcknowledged(appId.get))
    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      case StopAppClient =>
        markDead("Application has been stopped.")
        sendToMaster(UnregisterApplication(appId.get))
        context.reply(true)
        stop()

      case r: RequestExecutors =>
        master match {
          case Some(m) => askAndReplyAsync(m, context, r)
          case None =>
            logWarning("Attempted to request executors before registering with Master.")
            context.reply(false)
        }

      case k: KillExecutors =>
        master match {
          case Some(m) => askAndReplyAsync(m, context, k)
          case None =>
            logWarning("Attempted to kill executors before registering with Master.")
            context.reply(false)
        }
    }

    private def askAndReplyAsync[T](
        endpointRef: RpcEndpointRef,
        context: RpcCallContext,
        msg: T): Unit = {
      // Create a thread to ask a message and reply with the result.  Allow thread to be
      // interrupted during shutdown, otherwise context must be notified of NonFatal errors.
      askAndReplyThreadPool.execute(new Runnable {
        override def run(): Unit = {
          try {
            context.reply(endpointRef.askWithRetry[Boolean](msg))
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(t) =>
              context.sendFailure(t)
          }
        }
      })
    }

    override def onDisconnected(address: RpcAddress): Unit = {
      if (master.exists(_.address == address)) {
        logWarning(s"Connection to $address failed; waiting for master to reconnect...")
        markDisconnected()
      }
    }

    override def onNetworkError(cause: Throwable, address: RpcAddress): Unit = {
      if (isPossibleMaster(address)) {
        logWarning(s"Could not connect to $address: $cause")
      }
    }

    /**
     * Notify the listener that we disconnected, if we hadn't already done so before.
     */
    def markDisconnected() {
      if (!alreadyDisconnected) {
        listener.disconnected()
        alreadyDisconnected = true
      }
    }

    def markDead(reason: String) {
      if (!alreadyDead.get) {
        listener.dead(reason)
        alreadyDead.set(true)
      }
    }

    override def onStop(): Unit = {
      if (registrationRetryTimer.get != null) {
        registrationRetryTimer.get.cancel(true)
      }
      registrationRetryThread.shutdownNow()
      registerMasterFutures.get.foreach(_.cancel(true))
      registerMasterThreadPool.shutdownNow()
      askAndReplyThreadPool.shutdownNow()
    }

  }

  def start() {
    // Just launch an rpcEndpoint; it will call back into the listener.
    endpoint.set(rpcEnv.setupEndpoint("AppClient", new ClientEndpoint(rpcEnv)))
  }

  def stop() {
    if (endpoint.get != null) {
      try {
        val timeout = RpcUtils.askRpcTimeout(conf)
        timeout.awaitResult(endpoint.get.ask[Boolean](StopAppClient))
      } catch {
        case e: TimeoutException =>
          logInfo("Stop request to Master timed out; it may already be shut down.")
      }
      endpoint.set(null)
    }
  }

  /**
   * Request executors from the Master by specifying the total number desired,
   * including existing pending and running executors.
   *
   * @return whether the request is acknowledged.
   */
  def requestTotalExecutors(requestedTotal: Int): Boolean = {
    if (endpoint.get != null && appId.get != null) {
      endpoint.get.askWithRetry[Boolean](RequestExecutors(appId.get, requestedTotal))
    } else {
      logWarning("Attempted to request executors before driver fully initialized.")
      false
    }
  }

  /**
   * Kill the given list of executors through the Master.
   * @return whether the kill request is acknowledged.
   */
  def killExecutors(executorIds: Seq[String]): Boolean = {
    if (endpoint.get != null && appId.get != null) {
      endpoint.get.askWithRetry[Boolean](KillExecutors(appId.get, executorIds))
    } else {
      logWarning("Attempted to kill executors before driver fully initialized.")
      false
    }
  }

}
