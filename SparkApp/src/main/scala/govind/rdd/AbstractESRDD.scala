package govind.rdd

import govind.conf.ESConf
import govind.service.JRestRepository
import org.apache.spark.rdd.{RDD => SRDD}
import org.apache.spark.{Partition, SparkContext}
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag

private[govind] abstract class AbstractESRDD[T : ClassTag](
			@transient sc: SparkContext) extends SRDD[T](sc,Nil){
	protected var logger = LoggerFactory.getLogger(this.getClass)
	@transient lazy val esConf = new ESConf
	val repository = new JRestRepository(esConf)

	lazy val esPartitionInfo = {
		repository.findPartitions()
	}

	import scala.collection.JavaConverters._

	override protected def getPartitions: Array[Partition] = {
		val sparkPartitions = new Array[Partition](esPartitionInfo.size())
		var idx = 0
		for(esPartition <- esPartitionInfo.asScala) {
			sparkPartitions(idx) = new ESPartition(id, idx, esPartition)
			idx += 1
		}
		sparkPartitions
	}

	override protected def getPreferredLocations(split: Partition): Seq[String] = {
		val esSplit  = split.asInstanceOf[ESPartition]
		esSplit.esPartiton.locations
	}

	def esCount() = {
		try {
			repository.count()
		} catch {
			case _ =>
				repository.close()
		}
	}

	override def checkpoint(): Unit = {
		// ES RDD should not be checkpointed!
	}
}

 class ESPartition(val rddID: Int, val idx: Int, val esPartiton: ESPartitionInfo) extends Partition {

	override def index: Int = idx

	override def hashCode(): Int = {
		41 * (41 * (41 + rddID) + idx) + esPartiton.hashCode
	}
}