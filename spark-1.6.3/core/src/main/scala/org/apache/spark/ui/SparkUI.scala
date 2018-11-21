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

package org.apache.spark.ui

import java.util.Date

import org.apache.spark.status.api.v1.{ApiRootResource, ApplicationAttemptInfo, ApplicationInfo,
  UIRoot}
import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkContext}
import org.apache.spark.scheduler._
import org.apache.spark.storage.StorageStatusListener
import org.apache.spark.ui.JettyUtils._
import org.apache.spark.ui.env.{EnvironmentListener, EnvironmentTab}
import org.apache.spark.ui.exec.{ExecutorsListener, ExecutorsTab}
import org.apache.spark.ui.jobs.{JobsTab, JobProgressListener, StagesTab}
import org.apache.spark.ui.storage.{StorageListener, StorageTab}
import org.apache.spark.ui.scope.RDDOperationGraphListener

/**
 * 弱耦合消息机制
 * 监控Spark集群，采用事件监听（基于ListenerBus），事件就是异步调用。即集群发生具体事件后会发送消息给Driver
 * 在Driver中会接收到该消息或事件，然后处理。
 * 基于事件的监控，只需要发送消息即可而不用关心具体UI的渲染过程，实现不同模块的解耦合。
 * listenerBus从理论上说是属于SparkContext的， 在SparkContext中被创建，存在于SparkContext和SparkEnv对象中
 *
 * Top level user interface for a Spark application.
 */
private[spark] class SparkUI private ( //私有主构造器，只能方法内部被调用
    val sc: Option[SparkContext],
    val conf: SparkConf,
    securityManager: SecurityManager,
    //SparkUI可以通过监听器（ListenerBus）捕获Spark集群的动态实时展现到界面上
    val environmentListener: EnvironmentListener,
    val storageStatusListener: StorageStatusListener,
    val executorsListener: ExecutorsListener,
    val jobProgressListener: JobProgressListener,
    val storageListener: StorageListener,
    val operationGraphListener: RDDOperationGraphListener,
    var appName: String,
    val basePath: String,
    val startTime: Long)
  extends WebUI(securityManager, SparkUI.getUIPort(conf), conf, basePath, "SparkUI")
  with Logging
  with UIRoot {

  val killEnabled = sc.map(_.conf.getBoolean("spark.ui.killEnabled", true)).getOrElse(false)


  val stagesTab = new StagesTab(this)

  var appId: String = _

  /** Initialize all components of the server. 非常重要 */
  def initialize() {
    attachTab(new JobsTab(this))
    attachTab(stagesTab)
    attachTab(new StorageTab(this))
    attachTab(new EnvironmentTab(this))
    attachTab(new ExecutorsTab(this))
    attachHandler(createStaticHandler(SparkUI.STATIC_RESOURCE_DIR, "/static"))
    attachHandler(createRedirectHandler("/", "/jobs/", basePath = basePath))
    attachHandler(ApiRootResource.getServletHandler(this))
    // This should be POST only, but, the YARN AM proxy won't proxy POSTs
    attachHandler(createRedirectHandler(
      "/stages/stage/kill", "/stages/", stagesTab.handleKillRequest,
      httpMethods = Set("GET", "POST")))
  }
  initialize()

  def getAppName: String = appName

  def setAppId(id: String): Unit = {
    appId = id
  }

  /** Stop the server behind this web interface. Only valid after bind(). */
  override def stop() {
    super.stop()
    logInfo("Stopped Spark web UI at %s".format(appUIAddress))
  }

  /**
   * Return the application UI host:port. This does not include the scheme (http://).
   */
  private[spark] def appUIHostPort = publicHostName + ":" + boundPort

  private[spark] def appUIAddress = s"http://$appUIHostPort"

  def getSparkUI(appId: String): Option[SparkUI] = {
    if (appId == this.appId) Some(this) else None
  }

  def getApplicationInfoList: Iterator[ApplicationInfo] = {
    Iterator(new ApplicationInfo(
      id = appId,
      name = appName,
      coresGranted = None,
      maxCores = None,
      coresPerExecutor = None,
      memoryPerExecutorMB = None,
      attempts = Seq(new ApplicationAttemptInfo(
        attemptId = None,
        startTime = new Date(startTime),
        endTime = new Date(-1),
        sparkUser = "",
        completed = false
      ))
    ))
  }
}

private[spark] abstract class SparkUITab(parent: SparkUI, prefix: String)
  extends WebUITab(parent, prefix) {

  def appName: String = parent.getAppName

}

private[spark] object SparkUI {
  val DEFAULT_PORT = 4040 // 端口被占用时会自增1
  val STATIC_RESOURCE_DIR = "org/apache/spark/ui/static"
  val DEFAULT_POOL_NAME = "default"
  val DEFAULT_RETAINED_STAGES = 1000
  val DEFAULT_RETAINED_JOBS = 1000

  def getUIPort(conf: SparkConf): Int = {
    conf.getInt("spark.ui.port", SparkUI.DEFAULT_PORT)
  }

  //被SparkContext调用用于创建SparkUI对象
  def createLiveUI(
      sc: SparkContext,
      conf: SparkConf,
      listenerBus: SparkListenerBus,
      jobProgressListener: JobProgressListener,
      securityManager: SecurityManager,
      appName: String,
      startTime: Long): SparkUI = {
    create(Some(sc), conf, listenerBus, securityManager, appName,
      jobProgressListener = Some(jobProgressListener), startTime = startTime)
  }

  def createHistoryUI(
      conf: SparkConf,
      listenerBus: SparkListenerBus,
      securityManager: SecurityManager,
      appName: String,
      basePath: String,
      startTime: Long): SparkUI = {
    create(None, conf, listenerBus, securityManager, appName, basePath, startTime = startTime)
  }

  /**
   * Create a new Spark UI.
   *
   * @param sc optional SparkContext; this can be None when reconstituting a UI from event logs.
   * @param jobProgressListener if supplied, this JobProgressListener will be used; otherwise, the
   *                            web UI will create and register its own JobProgressListener.
   */
  private def create(
      //为什么是Option？即创建SparkUI不一定需要SparkContext，在没有Driver或应用程序的时候可以不需要。可以基于事件的日志构建SparkUI
      //例如：createHistoryUI就不需要SparkContext(解耦合)
      sc: Option[SparkContext],
      conf: SparkConf,
      listenerBus: SparkListenerBus,
      securityManager: SecurityManager,
      appName: String,
      basePath: String = "",
      jobProgressListener: Option[JobProgressListener] = None,
      startTime: Long): SparkUI = {

    val _jobProgressListener: JobProgressListener = jobProgressListener.getOrElse {
      val listener = new JobProgressListener(conf)
      listenerBus.addListener(listener)
      listener
    }

    val environmentListener = new EnvironmentListener
    val storageStatusListener = new StorageStatusListener
    val executorsListener = new ExecutorsListener(storageStatusListener)
    val storageListener = new StorageListener(storageStatusListener)
    val operationGraphListener = new RDDOperationGraphListener(conf)
    //监听器模式 listenerBus: SparkListenerBus
    listenerBus.addListener(environmentListener)
    listenerBus.addListener(storageStatusListener)
    listenerBus.addListener(executorsListener)
    listenerBus.addListener(storageListener)
    listenerBus.addListener(operationGraphListener)

    new SparkUI(sc, conf, securityManager, environmentListener, storageStatusListener,
      executorsListener, _jobProgressListener, storageListener, operationGraphListener,
      appName, basePath, startTime)
  }
}
