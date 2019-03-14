package govind.rpc

import scala.concurrent.Future


/**
	*
	* 1、RpcEndpoint向RpcEnv注册用于接收消息；
	* 2、客户端通过RpcEndpointRef发送消息到RpcEnv，在RpcEnv接收到消息后路由到本地或远程RpcEndpoint实体；
	* 3、RpcEnv捕获到的异常会通过RpcCallContext.sendFailures将异常发送给sender，若异常不能序列化或无sender则在本地记录
	*
	*/
abstract class RpcEnv(conf: RpcConf) {
	val defaultLookupTimeout = RpcTimeout("default RpcTimeout")

	//return address RpcEnv is listening to
	def address: RpcAddress

	//RpcEndpoint注册
	def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef

	//================= 根据URI或RpcAddress获取对应的RpcEndpointRef ===============
	//返回已注册的endpoint对应的endpointRef
	def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef
	//异步获取uri对应的RpcEndpointRef
	def asyncSetupEndpointRefByUri(uri: String): Future[RpcEndpointRef]
	//同步获取uri对应的RpcEndpointRef
	def setupEndpointRefByUri(uri: String): RpcEndpointRef = {
		defaultLookupTimeout.awaitResult(asyncSetupEndpointRefByUri(uri))
	}
	//同步获取给定`systemName`, `address` and `endpointName`对应的RpcEndpointRef实例
	def setupEndpointRef(systemName:String, address: RpcAddress, endpointName: String):RpcEndpointRef = {
		setupEndpointRefByUri(uriOf(systemName, address, endpointName))
	}
	//不同的RpcEnv实现有不同的格式
	def uriOf(systemName:String, address: RpcAddress, endpointName: String):String


	//停止RpcEnv中的指定RpcEndpoint
	def stop(endpoint: RpcEndpointRef): Unit


	//异步关停当前RpcEnv，若需要确保RpcEnv成功关停可以先调用shutdown()然后调用awaitTermination()
	def  shutdown(): Unit
	def awaitTermination(): Unit

	//RpcEndpointRef本身需要借助RpcEnv进行反序列化
	def deserialize[T](deserializationAction: () => T): T

	//=========  基于Rpc的文件服务器  TODO
}

case class RpcEnvConfig(conf: RpcConf, name: String, host: String, port: Int, clientMode: Boolean)
