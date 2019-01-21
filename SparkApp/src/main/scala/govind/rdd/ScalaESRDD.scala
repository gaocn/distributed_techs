package govind.rdd

import govind.conf.ESConf
import org.apache.spark.{SparkContext, TaskContext}

/**
	* 		-----------------            -----------------
	* 		| AbstractESRDD |-----       |   Partition   |
	* 		-------*---------    |       --------*--------
	* 					 |             |               |
	* 		-----------------    |       -----------------      -----------------
	*     |  ScalaESRDD   |    -------+|  ESPartition  |----->|ESPartitionInfo|
	*     -----------------            -----------------      -----------------
	*         |
	*         |
	*         |    -----------------------
	*         |    |AbastratESRDDIterator|-----    -----------------          -----------------
	*         |    ----------*------------    |---+|PartitionReader |-----    |  Iterator[T]  |
	*         |              |                     -----------------     |    -------*---------
	*         |              |                                           |            |
	* --------+---------     |                                           |    -----------------      ------------------------
	* |ScalaRDDIterator|-----                                            ----+|  ScrollQuery  |-----+|JRestRepository.scroll|
	* ------------------                                                      -----------------      ------------------------
	*/
abstract class ScalaESRDD[T](@transient sc: SparkContext) extends AbstractESRDD(sc){
//	override def compute(split: Partition, context: TaskContext): Iterator[T] = {
//		new ScalaESIterator[T](
//			split.asInstanceOf[ESPartition].esPartiton,
//			context,
//			esConf
//		)
//	}
}

class ScalaESIterator[T](split: ESPartitionInfo, context: TaskContext, esConf: ESConf) extends AbstractESRDDIterator[T](context, split, esConf) {
	override def createValue(batch: Array[AnyRef]):  T = {
		batch.asInstanceOf[T]
	}
}
