package govind.rdd

import java.io.Serializable

/**
	* AbstractESRDD下有若干个ESPartition，每个ESPartition中的数据在ESPartitionInfo中
	*
	* ESPartitionInfo代表一个ES Query结果的一个逻辑分片
	*/
class ESPartitionInfo(
			val index: String,
			val shardID: Int,
			val slice: Slice,
			val locations: Array[String]
			) extends Serializable with Ordered[ESPartitionInfo]{

	override def compare(that: ESPartitionInfo): Int = {
		var cmp = this.index.compare(that.index)
		if (cmp != 0) return cmp

		cmp = this.shardID - that.shardID
		if (cmp != 0) return cmp

		if (this.slice != null) {
			return this.slice.compare(that.slice);
		}
		return -1;
	}

	override def toString = s"ESPartitionInfo($index, $shardID, $slice, ${locations.toList})"
}

class Slice(val id: Int, val max: Int) extends Serializable with Ordered[Slice] {

	def canEqual(other: Any): Boolean = other.isInstanceOf[Slice]

	override def equals(other: Any): Boolean = other match {
		case that: Slice =>
			(that canEqual this) &&
				id == that.id &&
				max == that.max
		case _ => false
	}

	override def hashCode(): Int = {
		val state = Seq(id, max)
		state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
	}
	override def compare(o: Slice): Int = {
		val cmp = id - o.id
		if (cmp != 0) return cmp
		max - o.max
	}

	override def toString = s"Slice($id, $max)"
}