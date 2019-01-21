package govind.partitioner


trait Partition extends {
	// RDD中分区的索引
	def index: Int

	// A better default implementation of HashCode
	override def hashCode(): Int = index

}
