package govind.rpc

//工厂类，需要有一个无参构造函数以便创建默认的RpcEnv实例
trait RpcEnvFactory {
	def create(rpcEnvConfig: RpcEnvConfig): RpcEnv
}


trait RpcEndpoint {
	//The rpcEnv instance this RpcEndpoint is registered to
	val rpcEnv:RpcEnv

	/**
		* 1、在onStart调用后并且当前RpcEndpoint注册后才会有值；
		* 2、在onStop后返回null
		*/
	def self: RpcEndpointRef = {
		require(rpcEnv!=null, "rpcEnv has not been initialized!")
		rpcEnv.endpointRef(this)
	}


	def receive: PartialFunction[Any, Unit] = {
		case _ => throw new RpcException(s"${self} does not implement 'receive'")
	}

	def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
		case _ => context.sendFailure(new RpcException(s"${self} does not implement 'receiveAndReply'"))
	}

	def onStart(): Unit = {
		// By default, do nothing.
	}

	def onStop(): Unit = {
		// By default, do nothing.
	}

	def onError(cause: Throwable): Unit = {
		throw cause
	}

	def onConnected(remoteAddress: RpcAddress): Unit = {
		// By default, do nothing.
	}

	def onDisconnected(remoteAddress: RpcAddress): Unit = {
		// By default, do nothing.
	}

	def onNetworkError(cause: Throwable, remoteAddress: RpcAddress): Unit = {
		// By default, do nothing.
	}

	//用于停止当前RpcEndpoint实例
	def stop(): Unit = {
		val _self = self
		if (_self !=  null) {
			rpcEnv.stop(_self) //Question：这里停止的是RpcEndpointRef不是RpcEndpoint！！
		}
	}
}

/**
	* Thread-safety means changes to internal fields of a ThreadSafeRpcEndpoint
	*   are visible when processing the next message, fields in it need not be volatile!
	*/
trait ThreadSafeRpcEndpoint extends RpcEndpoint
