package org.apache.spark

import scala.util.Random

object TestPartitioner {
	def main(args: Array[String]): Unit = {
		println("Partitioner")
		val conf = new SparkConf()
		conf.setAppName("Partitioner Test").setMaster("local")
		val sc = new SparkContext(conf)
		val nums = 1 to 20
		val keys = nums.map{suffix =>
			Random.nextPrintableChar().toString + suffix
		}
		val rdd = sc.parallelize(keys.zip(nums), 3)
		rdd.mapPartitionsWithIndex{(idx, elems)=>
			println(s"Partition ID = ${idx}")
			elems.foreach{e => print(s"${e} ")}
			elems
		}.collect()
		rdd.join(rdd)
		val partitioner = new RangePartitioner[String, Int](5,rdd)
		println(partitioner.getPartition(keys.take(1)(0)))
	}
}
