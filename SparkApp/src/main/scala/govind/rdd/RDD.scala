package govind.rdd

import govind.partitioner.{Partition, Partitioner}
import org.apache.spark.SparkContext

import scala.collection.mutable
import scala.reflect.ClassTag

abstract class RDD[T:ClassTag] (val sc: SparkContext){

	/** A unique ID for this RDD (within its SparkContext). */
//	val id: Int = sc.newRddId()

	@transient val partitioner: Option[Partitioner] = None

	final def partitions: Array[Partition]  = {
		getPartitions
	}

	val partLoctions = mutable.HashMap[Int, Seq[String]]()

	def context = sc
	def getPartitions: Array[Partition]

	val hosts:Seq[String] = {
		(1 to 9).map(i => s"192.168.12.${i}".toString)
	}

	def getPreferredLocs(part: Partition): Seq[String] = {
		partLoctions(part.index)
	}

	override def toString = s"RDD(${partitions.toSeq})"
}

object RDD {
	def apply(): RDD[String] = {
		new RDD[String](null) {
			override def getPartitions: Array[Partition] = {
				(0 to 10).map(i =>{

					val res = (0 to 2).filter(scala.util.Random.nextInt(10) == _).map(i => {
						val rnd = scala.util.Random.nextInt(hosts.length)
						hosts(rnd)
					})
					println(s"partition: ${i}, locations: ${res}")
					partLoctions(i) = res

					new Partition {
						override def index: Int = i

						override def hashCode(): Int = i

						override def equals(obj: scala.Any): Boolean = {
							if (obj.isInstanceOf[Partition]) {
								this.index == obj.asInstanceOf[Partition].index
							} else {
								false
							}
						}
					}
				} ).toArray
			}

		}
	}
}