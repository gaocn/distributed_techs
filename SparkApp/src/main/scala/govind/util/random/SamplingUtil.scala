package govind.util.random

import scala.reflect.ClassTag
import scala.util.Random

object SamplingUtil {

	/**
		*
		* @param input
		* @param k
		* @param seed
		* @tparam T
		* @return (samples, input size)
		*/
	def reservoirSampleAndCount[T:ClassTag](
			input: Iterator[T],
			k: Int,
			seed: Int): (Array[T], Long) = {
		val reservoir = new Array[T](k)

		var i = 0
		while (i < k && input.hasNext) {
			reservoir(i) = input.next()
			i += 1
		}
		// have consumed all elements in input, then return. Otherwise do the replacement
		if (i < k)  {
			//trim the array to return only an array of input size if input size < k
			val trimReservoir = new Array[T](i)
			System.arraycopy(reservoir, 0, trimReservoir, 0, i)
			(trimReservoir, i)
		} else {
			// if input size > k, continue the sampling process
			var l = i.toLong
			while (input.hasNext) {
				val elem = input.next()
				val replacementIndex = (Random.nextDouble() * l).toLong
				if (replacementIndex < k) {
					reservoir(replacementIndex.toInt) = elem
				}
				l += 1
			}
			(reservoir, l)
		}
	}

	def main(args: Array[String]): Unit = {
		val nums = 1 to  1000
		val (reservoir, count) = SamplingUtil.reservoirSampleAndCount[Int](nums.iterator, 100, 99)
		println(s"${reservoir.toSeq}")
	}
}
