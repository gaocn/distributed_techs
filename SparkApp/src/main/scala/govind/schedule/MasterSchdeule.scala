package govind.schedule

import java.util

import scala.collection.mutable.ArrayBuffer

class MasterSchdeule {

}

object MasterSchdeule {

	case class AppInfo(coresPerExecutor: Option[Int], memoryPerExecutorMB: Int, coresLeft: Int, executorLimit: Int, executorSize: Int)
	case class Worker(coresFree: Int, memoryFree: Int)

	def main(args: Array[String]): Unit = {
		val app = AppInfo(Some(16), 256, 48, 5, 0)
		val workers = ArrayBuffer[Worker]()
		workers  += Worker(48, 640)
		workers  += Worker(32, 512)
		workers  += Worker(16, 1024)
		workers  += Worker(16, 2048)
//		val coresAssigned = scheduleExecutorsOnWorkers(app, workers.toArray, true);
//		println(s"coresAssign: ${util.Arrays.toString(coresAssigned)}")
		val coresAssigned1 = scheduleExecutorsOnWorkers(app, workers.toArray, false);
		println(s"coresAssign: ${util.Arrays.toString(coresAssigned1)}")
	}

	def scheduleExecutorsOnWorkers(app: AppInfo, usableWorkers: Array[Worker], spreadOut: Boolean): Array[Int] = {
		val coresPerExecutor = app.coresPerExecutor
		val minCoresPerExecutor = coresPerExecutor.getOrElse(1)
		//为空表示一个Worker上只允许启一个Executor，否则允许启动多个Executor
		val oneExecutorPerWorker = coresPerExecutor.isEmpty
		val memoryPerExecutor = app.memoryPerExecutorMB
		val numUsable = usableWorkers.size
		val assignedCores  = new Array[Int](numUsable)
		val assignedExecutors = new Array[Int](numUsable)

		//最多只能分配COREs数为两者中的最小值
		var coresToAssign = scala.math.min(app.coresLeft, usableWorkers.map(_.coresFree).sum)

		def canLaunchExecutor(pos: Int): Boolean = {
			val keepingScheduling = coresToAssign >= minCoresPerExecutor
			val enoughCores = usableWorkers(pos).coresFree - assignedCores(pos) >= minCoresPerExecutor

			val launchingNewExecutor = !oneExecutorPerWorker || assignedCores(pos) ==  0
			if (launchingNewExecutor) {
				val assignedMemory = assignedExecutors(pos) * memoryPerExecutor
				val enoughMemory = usableWorkers(pos).memoryFree - assignedMemory >= memoryPerExecutor
				val underLimit = assignedExecutors(pos) + app.executorSize < app.executorLimit
				keepingScheduling && enoughCores && enoughMemory && underLimit
			} else {
				//Worker上只允许启动一个Executor，因此直接把COREs和MEM分配该Executor即可，不需要进行内存和executor数目检查
				keepingScheduling && enoughCores
			}
		}

		var freeWorkers = (0 until numUsable).filter(canLaunchExecutor)
		while(freeWorkers.nonEmpty) {
			freeWorkers.foreach{ pos =>
				var keepScheduling = true
				while(keepScheduling && canLaunchExecutor(pos)) {
					coresToAssign  -= minCoresPerExecutor
					assignedCores(pos) += minCoresPerExecutor

					if (oneExecutorPerWorker) {
						assignedExecutors(pos) = 1
					} else {
						assignedExecutors(pos) += 1
					}

					if (spreadOut) {
						keepScheduling =  false
					}
				}
			}
			freeWorkers = freeWorkers.filter(canLaunchExecutor)
		}
		assignedCores
	}
}
