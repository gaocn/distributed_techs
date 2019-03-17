package govind.rpc

import govind.serializer.JavaSerializerInstance

import scala.concurrent.Future

class NettyRpcEnv(
	rpcConf: RpcConf,
	javaSerializerInstance: JavaSerializerInstance,
	host: String) extends RpcEnv(rpcConf){




	override def address: RpcAddress = ???

	override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef = ???

	override def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef = ???

	override def asyncSetupEndpointRefByUri(uri: String): Future[RpcEndpointRef] = ???

	override def uriOf(systemName: String, address: RpcAddress, endpointName: String): String = ???

	override def stop(endpoint: RpcEndpointRef): Unit = ???

	override def shutdown(): Unit = ???

	override def awaitTermination(): Unit = ???

	override def deserialize[T](deserializationAction: () => T): T = ???
}
