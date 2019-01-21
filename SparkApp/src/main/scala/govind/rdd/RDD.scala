package govind.rdd

import govind.partitioner.{Partition, Partitioner}
import org.apache.spark.SparkContext

import scala.reflect.ClassTag

abstract class RDD[T:ClassTag] (val sc: SparkContext){

	/** A unique ID for this RDD (within its SparkContext). */
//	val id: Int = sc.newRddId()

	@transient val partitioner: Option[Partitioner] = None

	final def partitions: Array[Partition]  = {
		getPartitions
	}

	def context = sc
	def getPartitions: Array[Partition]
}
