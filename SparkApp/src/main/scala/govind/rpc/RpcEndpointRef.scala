package govind.rpc

import scala.concurrent.Future
import scala.reflect.ClassTag

/**
	* RpcEndpointRef是远程RpcEndpoint的引用（代理），且是线程安全的。
	*
	*/
abstract class RpcEndpointRef(rpcConf: RpcConf) extends Serializable {
	val maxRetries = 3
	val retryWaitMS = 3000L
	val defaultAskTimeout = RpcTimeout.apply("RpcEndpointRef Default Ask Timeout")

	//返回RpcEndpointRef的地址信息
	def address:RpcAddress

	def name: String

	//异步单向发送消息
	def send(message: Any): Unit

	//异步 只发送一次消息给RpcEndpoint.receiveAndReply方法并返回一个Future在指定超时时间内接收结果
	def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T]
	def ask[T: ClassTag](message: Any): Future[T] = ask(message, defaultAskTimeout)

	//同步 发送消息给RpcEndpoint.receive方法并返回结果，若超过重试次数后仍未得到结果则抛出异常
	def askWithRetry[T:ClassTag](message: Any): T = askWithRetry(message, defaultAskTimeout)
	def askWithRetry[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
		var attempts = 0
		var lastException: Exception = null
		while (attempts < maxRetries) {
			attempts += 1

			try {
				val future = ask[T](message, timeout)
				val result = timeout.awaitResult(future)
				if (result == null) {
					throw new RpcException("RpcEndpoint returned null!")
				}
				return result
			} catch {
				case ie: InterruptedException => throw ie
				case e: Exception =>
					lastException = e
					println(s"Error sending message [message = $message] in $attempts attempts, Exception[${e}]")
			}
			if (attempts < maxRetries) {
				Thread.sleep(retryWaitMS)
			}
		}
		throw new RpcException(s"Error sending message [message = $message] in $attempts attempts, Exception[${lastException}]")
	}
}
