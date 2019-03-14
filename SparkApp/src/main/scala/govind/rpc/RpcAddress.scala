package govind.rpc

import java.net.URI

case class RpcAddress(host: String, port: Int) {
	def hostPort = s"${host}:${port}"
	def toSparkURL = s"spark://${hostPort}"
	override def toString: String = hostPort
}

object RpcAddress {
	def fromURIString(uri: String): RpcAddress = {
		val uriObj = new URI(uri)
		RpcAddress(uriObj.getHost, uriObj.getPort)
	}

	def fromSparkURL(url: String): RpcAddress =  {
		fromURIString(url)
	}

	def main(args: Array[String]): Unit = {
		val uri = "http://112.23.54.1:9080"
		val url = "spark://145.85.69.1:7867"

		println(RpcAddress.fromURIString(uri))
		println(RpcAddress.fromURIString(url))
	}
}
