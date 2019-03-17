package govind.rpc

import java.util.concurrent.TimeoutException


case class RpcException(msg: String) extends Exception(msg)

case class RpcEndpointNotFoundException(msg:String) extends Exception(msg)

case class RpcTimeoutException(msg: String,cause: TimeoutException) extends TimeoutException(msg) {
	initCause(cause)
}