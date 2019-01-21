package govind.rdd

import govind.conf.ESConf
import govind.service.{JRestRepository, ScrollQuery}
import org.apache.spark.TaskContext

abstract class AbstractESRDDIterator[T](
			val context: TaskContext,
			partition: ESPartitionInfo, esConf: ESConf) extends Iterator[T]{
	var initialized = false
	var finished = false
	var closed = false

	lazy val reader = {
		initialized = true

		val repository = new JRestRepository(esConf)
		val endpoint = s"${esConf.esIndex}/_search"
		val query =
			s"""
				{"slice": {"id": ${partition.slice.id},"max": ${partition.slice.max},
				 "size": ${esConf.MAX_BATCH_DOC_COUNT_TO_READ},
				 "query": {"match_all": {}}}
			""".stripMargin
		new ScrollQuery(repository, endpoint, query, esConf.MAX_DOC_TO_READ, partition)
	}

	override def hasNext: Boolean = {
		!finished && reader.hasNext
	}

	def createValue(batch: Array[AnyRef]): T

	override def next(): T = {
		val batch = reader.next()
		createValue(batch)
	}

	def closeIfNeeded(): Unit = {
		if (!closed) {
			close()
			closed = true
		}
	}

	protected def close()= {
		if (initialized) {
			reader.close()
		}
	}
}
