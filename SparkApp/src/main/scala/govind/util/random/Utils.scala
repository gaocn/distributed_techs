package govind.util.random

object Utils {

	def tryLogNonfatalException[T](block: => T): T = {
		try {
			block
		} catch {
			case e: Exception => println(s"Exception encountered: ${e}")
				throw e
		}
	}

	def main(args: Array[String]): Unit = {
		Utils.tryLogNonfatalException{
			throw new Exception("hello exception")
		}
	}
}
