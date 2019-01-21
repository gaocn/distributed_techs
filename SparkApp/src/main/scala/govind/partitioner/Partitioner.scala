package govind.partitioner

import java.net.URI

import govind.rdd.RDD

// 对于<K,V>RDD，给定K可以确定其partitionID，范围在[0, numPartitions - 1`11 B ]
abstract class Partitioner extends Serializable {
	def numPartitions: Int
	def getPartition(key: Any): Int
}

object Partitioner {
	/**
		* 从RDD中获取默认Partitioner，若没有则新建HashPartitioner，新建Partitioner的
		* 分区数为RDD中的${spark.default.parallelism}或上游RDD的分区数
		* 1、若给定的RDD中有partitioner，则从中选择中选一个RDD的partitioner；
		* 2、若没有，新建HashPartitioner并将numPartitions设置为默认配置${spark.default.parallelism}
		* 3、若spark.default.parallelism没有设置，则从依赖RDDs中选择分区最大的那个RDD的分区数作为新建分区的分区数；
		*/
	def defaultPartitioner(rdd: RDD[_], others: RDD[_]*): Partitioner = {
		val sortBySize = (Seq(rdd) ++ others).sortBy(_.partitions.size).reverse
		for (r <- sortBySize if r.partitioner.isDefined && r.partitioner.get.numPartitions > 0) {
			return r.partitioner.get
		}

		if(rdd.context.getConf.contains("spark.default.parallelism")) {
			new HashPartitioner(rdd.context.defaultParallelism)
		} else {
			new HashPartitioner(sortBySize.head.partitions.size)
		}
	}
}

class HashPartitioner(partitions: Int) extends Partitioner {
	override def numPartitions: Int = partitions

	override def getPartition(key: Any): Int = key match {
		case null => 0
		case _ =>
			val rawMod = key.hashCode % numPartitions
			rawMod + (if (rawMod < 0) numPartitions  else 0)
	}

	override def equals(obj: scala.Any): Boolean = obj match {
		case h: HashPartitioner => h.numPartitions == numPartitions
		case _ => false
	}

	override def hashCode(): Int = numPartitions
}


class UrlPartitioner(partitions: Int) extends Partitioner {
	override def numPartitions: Int = partitions

	override def getPartition(key: Any): Int = {
		val host = new URI(key.toString).getHost
		val partition = host.hashCode % numPartitions
		partition + (if(partition < 0) numPartitions else 0)
	}

	override def equals(obj: scala.Any): Boolean = obj match {
		case u: UrlPartitioner =>
			u.numPartitions == numPartitions
		case _ =>
			false
	}
	override def hashCode(): Int = numPartitions
}

