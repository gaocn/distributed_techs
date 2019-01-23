package govind.rdd

import govind.partitioner.Partition

import scala.collection._
import scala.collection.mutable.ArrayBuffer

class CoalesceRDD {

}

private[rdd] case class PartitionGroup(prefLoc:Option[String] = None) {
	var arr = mutable.ArrayBuffer[Partition]()
	def size = arr.size

	override def toString: String = {
		arr.toString()
	}
}
private[rdd] object PartitionGroup {
	def apply(prefLoc: String): PartitionGroup ={
		require(prefLoc != "", "Preferred location must not be empty!")
		new PartitionGroup(Some(prefLoc))
	}
}

/**
	*
	* @param maxPartition math.min(maxPartiton, rdd.partitions.size)调整后的RDD分区不大于RDD分区数
	* @param prev 要调整的分区的RDD
	* @param balanceSlack 负载松弛因子
	*/
private[rdd] class PartitionCoalescer(maxPartition: Int, prev: RDD[String], balanceSlack: Double) {
	def compare(o1: PartitionGroup, o2: PartitionGroup): Boolean = o1.size < o2.size
	def compare(o1: Option[PartitionGroup], o2: Option[PartitionGroup]):Boolean = {
		if(None == o1) false else if (None == o2) false else compare(o1.get, o2.get)
	}
	def currPrefLocs(part: Partition):Seq[String] = prev.getPreferredLocs(part)

	//(replicaHost, partition)
	class LocationIterator(prev: RDD[String]) extends Iterator[(String, Partition)] {

		var itr:Iterator[(String, Partition)] = resetIterator()

		override val isEmpty = !itr.hasNext

		def resetIterator(): Iterator[(String, Partition)] = {
			val iterator = (0 to 2).map(x =>
				prev.partitions.iterator.flatMap(p =>{
					val locs = currPrefLocs(p)
					if(locs.size > x) Some((locs(x), p)) else None
				})
			)
			iterator.reduceLeft(_ ++ _)
		}

		override def next(): (String, Partition) = {
			if (itr.hasNext) {
				itr.next()
			} else {
				itr = resetIterator()
				itr.next()
			}
		}
		override def hasNext: Boolean = !isEmpty
	}


	val rnd =  new scala.util.Random(7979)
	val partitionGroups = ArrayBuffer[PartitionGroup]()
	val hostPartitionGroup = mutable.HashMap[String, ArrayBuffer[PartitionGroup]]()

	val visitedPartition = mutable.Set[Partition]()

	//trade-off between partition size and locality
	val slack = (balanceSlack * prev.partitions.length).toInt

	//若为true，分组过程不考虑分区本地性
	var noLocality = true


	def addPartitionToPGroup(part: Partition, partitionGroup: PartitionGroup) = {
		if (!visitedPartition.contains(part)) {
			partitionGroup.arr += part
			visitedPartition += part
			true
		} else {
			false
		}
	}

	/**
		* Coupon Collector Estimation
		* 1、分区数大于存有分区的机器数，设targetLen=5，hostLen=3
		* host1                     第一次循环          第二次循环
		* ------------------        -----------        -----------
		* |  P1   P2   P3  |        |   PG1   |        |   PG4   |
		* |   P4    P5     |        -----------        -----------
		* ------------------
		* host2
		* ------------------        -----------        -----------
		* |    P6    P7    |        |   PG2   |        |   PG3   |
		* |       P8       |        -----------        -----------
		* ------------------
		* host3
		* ------------------        -----------
		* |   P9   P10     |        |   PG3   |
		* ------------------        -----------
		*
		* 2、分区数不于存有分区的机器数，设targetLen=3，hostLen=3
		* host1                     第一次循环         第二次循环跳过
		* ------------------        -----------
		* |  P1   P2   P3  |        |   PG1   |
		* |   P4    P5     |        -----------
		* ------------------
		* host2
		* ------------------        -----------
		* |    P6    P7    |        |   PG2   |
		* |       P8       |        -----------
		* ------------------
		* host3
		* ------------------        -----------
		* |   P9   P10     |        |   PG3   |
		* ------------------        -----------
		*
		* @param targetLen <= rdd.partitions.length 需要对rdd进行重新分区的分区数
		*/
	def setupGroups(targetLen: Int): Unit = {
		val locationIterator = new LocationIterator(prev)

		//RDD没有本地性数据，则直接targetLen个不包含本地性数据的分组
		if (!locationIterator.hasNext) {
			(1 to targetLen).foreach(x => partitionGroups += PartitionGroup())
			return
		}

		noLocality = false

		//为了能够遍历所有的preferred locations所需要进行的尝试次数 O(NlogN)
		//当targetLen >> preferredLocations，没必要遍历所有分区的locations,因为很多都是重复，
		val expectedCoupons2 = 2 * (math.log(targetLen)*targetLen + targetLen + 0.5).toInt
		var numPartitionGroupCreated =0
		var tryTimes =0

		//尽量将PartitionGroup放在不同机器上
		while (numPartitionGroupCreated < targetLen && tryTimes < expectedCoupons2) {
			tryTimes += 1

			val (host, part) = locationIterator.next()
			if (!hostPartitionGroup.contains(host)) {
				val pg = PartitionGroup(host)
				partitionGroups += pg
				addPartitionToPGroup(part, pg)
				hostPartitionGroup.put(host, ArrayBuffer(pg))
				numPartitionGroupCreated += 1
			}
		}

		//若机器数小于要创建的分区数，则允许统一位置有多个PartitionGroup
		while (numPartitionGroupCreated < targetLen) {
			var (host, part) = locationIterator.next()
			val pg = PartitionGroup(host)
			partitionGroups += pg
			hostPartitionGroup.getOrElse(host, ArrayBuffer()) += pg

			var tries = 0
			while (!addPartitionToPGroup(part, pg) && tries < targetLen) {
				part = locationIterator.next()._2
				tries += 1
			}
			numPartitionGroupCreated += 1
		}
	}

	/**
		* Power of 2 Choice for load balance
		*/
	def pickBin(part:Partition):PartitionGroup = {

		val idx1 = rnd.nextInt(partitionGroups.size)
		val idx2 = rnd.nextInt(partitionGroups.size)

		val minPowerOfTwo = {
			if (partitionGroups(idx1).size < partitionGroups(idx2).size)partitionGroups(idx1)  else partitionGroups(idx2)
		}

		/**
			* 1、若有根据分区本地行信息，根据松弛因子和本地性将分区尽可能分配到本地节点上
			* 2、否则，直接返回结果
			*/
		def getLeastPG(host: String): Option[PartitionGroup] = {
			try {
				Some(hostPartitionGroup(host).sortWith(compare).head)
			} catch {
				case _ => None
			}
		}

		val prefLoc = currPrefLocs(part)
			.map(getLeastPG(_)).sortWith(compare).head

		if (prefLoc == None) return minPowerOfTwo
		val  prefPG = prefLoc.get


		//为达到本地性要求，添加松弛因子balanceSlack，允许分区均衡性和本地性间上下倾斜
		if (minPowerOfTwo.size + slack <= prefPG.size) {
			minPowerOfTwo
		} else {
			prefPG
		}
	}

	def  throllBalls(): Unit = {
		if (noLocality) {
			if (maxPartition > partitionGroups.size) {
				//1. 重新分区数大于RDD的已有分区数，则直接返回结果
				for ((p, i) <- prev.partitions.zipWithIndex) {
					 partitionGroups(i).arr += p
				}

			} else {
				//2. 重新分区数小于RDD，则按照范围平均分配到PartitionGroup中
				for (i <- 0 until maxPartition) {
					val start = ((i * prev.partitions.length) / maxPartition).toInt
					val end = (((i + 1) * prev.partitions.length) / maxPartition).toInt
					(start until end).foreach(j => partitionGroups(i).arr += prev.partitions(j))
				}
			}


		} else {
			//根据分区本地性重新划分分区
			for (part <- prev.partitions if !visitedPartition.contains(part)) {
				pickBin(part).arr += part
				visitedPartition += part
			}
		}
	}

	val getPartitions: Array[PartitionGroup] = partitionGroups.toArray

	def run(): Array[PartitionGroup] = {
		setupGroups(Math.min(maxPartition, prev.partitions.length))
		throllBalls()
		getPartitions
	}
}



object PartitionTest extends App{
//	val pg = PartitionGroup("10.230.150.175")
//	println(pg.toString)
  val rdd = RDD()
	println(rdd.toString)

	val pc  = new PartitionCoalescer(5, rdd, 0.1)
	val lIter = new pc.LocationIterator(rdd)

//	pc.noLocality = true
	pc.run()
	println(s"${pc.getPartitions}")


}

