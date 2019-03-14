package govind.rpc

import java.util.concurrent.{TimeUnit, TimeoutException}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Awaitable}

/**
	* eg: RpcTimeout(5 millis, "short timeout")
	*
	*/
class RpcTimeout(val duration: FiniteDuration, val description: String ) extends Serializable {
	private def  createRpcTimeoutException(te: TimeoutException): RpcTimeoutException = {
		new RpcTimeoutException(s"${te.getMessage}. This timeout is controlled by ${description}" ,te)
	}

	def addMessageIfTimeout[T]:PartialFunction[Throwable, T] = {
		case rtw: RpcTimeoutException => throw rtw
		case te: TimeoutException => throw createRpcTimeoutException(te)
	}

	def awaitResult[T](awaitable: Awaitable[T]): T = {
		try {
			Await.result(awaitable, duration)
		} catch addMessageIfTimeout
	}

}

object RpcTimeout {
	val seconds: FiniteDuration =FiniteDuration.apply(5, TimeUnit.SECONDS)
	def apply(description: String): RpcTimeout = new RpcTimeout(seconds, description)
	def apply(duration: FiniteDuration, description: String): RpcTimeout = new RpcTimeout(duration, description)
}