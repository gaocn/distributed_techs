/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

import org.apache.spark.rdd.{PartitionPruningRDD, RDD}
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.util.random.SamplingUtils
import org.apache.spark.util.{CollectionsUtils, Utils}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.hashing.byteswap32

/**
 * An object that defines how the elements in a key-value pair RDD are partitioned by key.
 * Maps each key to a partition ID, from 0 to `numPartitions - 1`.
  *
  *Spark的Shuffle过程需要依赖分区器partitioner，主要用于RDD数据的重分步，常用的数据分区器有两类：
  * HashPartitioner和RangePartitioner,其中HashPartitioner根据RDD中key的hashcode值进行分区，
  * 而RangePartitioner根据范围进行数据分区。
  *
 */
abstract class Partitioner extends Serializable {
  def numPartitions: Int
  def getPartition(key: Any): Int
}

object Partitioner {
  /**
   * Choose a partitioner to use for a cogroup-like operation between a number of RDDs.
   *
   * If any of the RDDs already has a partitioner, choose that one.
   *
   * Otherwise, we use a default HashPartitioner. For the number of partitions, if
   * spark.default.parallelism is set, then we'll use the value from SparkContext
   * defaultParallelism, otherwise we'll use the max number of upstream partitions.
   *
   * Unless spark.default.parallelism is set, the number of partitions will be the
   * same as the number of partitions in the largest upstream RDD, as this should
   * be least likely to cause out-of-memory errors.
   *
   * We use two method parameters (rdd, others) to enforce callers passing at least 1 RDD.
   */
  def defaultPartitioner(rdd: RDD[_], others: RDD[_]*): Partitioner = {
    val bySize = (Seq(rdd) ++ others).sortBy(_.partitions.size).reverse
    for (r <- bySize if r.partitioner.isDefined && r.partitioner.get.numPartitions > 0) {
      return r.partitioner.get
    }
    if (rdd.context.conf.contains("spark.default.parallelism")) {
      new HashPartitioner(rdd.context.defaultParallelism)
    } else {
      new HashPartitioner(bySize.head.partitions.size)
    }
  }
}

/**
 * A [[org.apache.spark.Partitioner]] that implements hash-based partitioning using
 * Java's `Object.hashCode`.
 *
 * Java arrays have hashCodes that are based on the arrays' identities rather than their contents,
 * so attempting to partition an RDD[Array[_]] or RDD[(Array[_], _)] using a HashPartitioner will
 * produce an unexpected or incorrect result.
  *
  * HashPartitioner是Spark默认的分区器，默认用于90%以上的RDD相关的API，工作过程：根据RDD中key的hashCode值
  * 将数据取模后得到key对应的下一个RDD的分区id值mod(key.hashCode,numPartitions)，支持key为Null的情况，当
  * key为Null时默认分区id为0，该分区器适用于所有RDD数据类型的数据进行分区操作。需要注意：由于Java中数组的
  * hashCode是基于数组对象本身，不是基于数组内容的，所以如果RDD的key是数组类型，可能会导致数组内容一样的key无
  * 法被分配到同一个RDD分区中，这个时候最好自定义数据分区器，采用书组合内容进行分区或将数组内容转换为集合。
  *
 */
class HashPartitioner(partitions: Int) extends Partitioner {
  require(partitions >= 0, s"Number of partitions ($partitions) cannot be negative.")

  def numPartitions: Int = partitions

  /**
    * 根据${key}返回key对应的分区编号
    * @param key
    * @return
    */
  def getPartition(key: Any): Int = key match {
    case null => 0
    //要求非负数取模，若为负数，则通过 mod + numPartitions转换为正数
    case _ => Utils.nonNegativeMod(key.hashCode, numPartitions)
  }

  override def equals(other: Any): Boolean = other match {
    case h: HashPartitioner =>
      h.numPartitions == numPartitions
    case _ =>
      false
  }

  override def hashCode: Int = numPartitions
}

/**
 * A [[org.apache.spark.Partitioner]] that partitions sortable records by range into roughly
 * equal ranges. The ranges are determined by sampling the content of the RDD passed in.
 *
 * Note that the actual number of partitions created by the RangePartitioner might not be the same
 * as the `partitions` parameter, in the case where the number of sampled records is less than
 * the value of `partitions`.
  *
  * RangePartitioner用于RDD的数据排序相关API中，会尽量保证每个分区中的数量的均匀，该分区器要求RDD中的KEY类型必
  * 须是可以排序的。
  * 【实现步骤】
  * 第一步：
  * 先从整个RDD中抽取出样本数据，将样本数据排序，计算出每个分区的最大key值，形成一个Array[KEY]类型的数组变量rangeBounds；
  * 第二步：
  * 判断key在rangeBounds中所处的范围，给出该key值在下一个RDD中的分区id下标；
 */
class RangePartitioner[K : Ordering : ClassTag, V](
    partitions: Int,
    rdd: RDD[_ <: Product2[K, V]],
    private var ascending: Boolean = true)
  extends Partitioner {

  // We allow partitions = 0, which happens when sorting an empty RDD under the default settings.
  require(partitions >= 0, s"Number of partitions cannot be negative but found $partitions.")

  //获取RDD中key类型数据的排序器
  private var ordering = implicitly[Ordering[K]]

  // An array of upper bounds for the first (partitions - 1) partitions
  private var rangeBounds: Array[K] = {
    if (partitions <= 1) {
      //如果给定的分区数是一个的情况下，直接返回一个空的集合，表示数据不进行分区
      Array.empty
    } else {
      // This is the sample size we need to have roughly balanced output partitions, capped at 1M.
      // 给定总的数据抽样大小，最多1M的数据量(10^6)，最少20倍的RDD分区数量，也就是每个RDD分区至少抽取40条数据
      val sampleSize = math.min(20.0 * partitions, 1e6)
      // Assume the input partitions are roughly balanced and over-sample a little bit.
      // 计算每个分区抽取的数据量大小， 假设输入数据每个分区分布的比较均匀
      // 对于超大数据集(分区数超过5万的)乘以3会让数据稍微增大一点，对于分区数低于5万的数据集，每个分区抽取数据量为60条也不算多
      val sampleSizePerPartition = math.ceil(3.0 * sampleSize / rdd.partitions.size).toInt
      // 从rdd中抽取数据，返回值:(总rdd数据量， Array[分区id，当前分区的数据量，当前分区抽取的数据])
      val (numItems, sketched) = RangePartitioner.sketch(rdd.map(_._1), sampleSizePerPartition)
      if (numItems == 0L) {
        // 如果总的数据量为0(RDD为空)，那么直接返回一个空的数组
        Array.empty
      } else {
        // If a partition contains much more than the average number of items, we re-sample from it
        // to ensure that enough items are collected from that partition.
        // 计算总样本数量和总记录数的占比，占比最大为1.0
        val fraction = math.min(sampleSize / math.max(numItems, 1L), 1.0)
        // 保存样本数据的集合buffer
        val candidates = ArrayBuffer.empty[(K, Float)]
        // 保存数据分布不均衡的分区id(数据量超过fraction比率的分区)
        val imbalancedPartitions = mutable.Set.empty[Int]
        // 计算抽取出来的样本数据
        sketched.foreach { case (idx, n, sample) =>
          if (fraction * n > sampleSizePerPartition) {
            // 如果fraction乘以当前分区中的数据量大于之前计算的每个分区的抽象数据大小，那么表示当前分区抽取的
            // 数据太少了，该分区数据分布不均衡，需要重新抽取
            imbalancedPartitions += idx
          } else {
            // 当前分区不属于数据分布不均衡的分区，计算占比权重，并添加到candidates集合中
            // The weight is 1 over the sampling probability.
            val weight = (n.toDouble / sample.size).toFloat
            for (key <- sample) {
              candidates += ((key, weight))
            }
          }
        }
        // 对于数据分布不均衡的RDD分区，重新进行数据抽样
        if (imbalancedPartitions.nonEmpty) {
          // Re-sample imbalanced partitions with the desired sampling probability.
          // 获取数据分布不均衡的RDD分区并根据相同key对分区进行合并（裁剪分区）减少分区个数，增加分区中元素数目
          val imbalanced = new PartitionPruningRDD(rdd.map(_._1), imbalancedPartitions.contains)
          val seed = byteswap32(-rdd.id - 1)
          // 利用rdd的sample抽样函数API进行数据抽样
          val reSampled = imbalanced.sample(withReplacement = false, fraction, seed).collect()
          val weight = (1.0 / fraction).toFloat
          candidates ++= reSampled.map(x => (x, weight))
        }
        // 将最终的抽样数据计算出rangeBounds出来,为Array类型，保存每个分区上界值
        RangePartitioner.determineBounds(candidates, partitions)
      }
    }
  }
  // 下一个RDD的分区数量是rangeBounds数组中元素数量+ 1个s
  def numPartitions: Int = rangeBounds.length + 1

  // 二分查找器
  private var binarySearch: ((Array[K], K) => Int) = CollectionsUtils.makeBinarySearch[K]

  // 根据RDD的key值返回对应的分区id(从0开始)
  def getPartition(key: Any): Int = {
    val k = key.asInstanceOf[K]
    var partition = 0
    if (rangeBounds.length <= 128) {
      // If we have less than 128 partitions naive search
      // 如果分区数据小于等于128个，那么直接本地循环寻找当前k所属的分区下标
      while (partition < rangeBounds.length && ordering.gt(k, rangeBounds(partition))) {
        partition += 1
      }
    } else {
      // Determine which binary search method to use only once.
      // 如果分区数量大于128个，那么使用二分查找方法寻找对应k所属的下标;
      // 但是如果k在rangeBounds中没有出现，实质上返回的是一个负数(范围)或者是一个超过rangeBounds大小的数(最后一个分区，比所有数据都大)
      partition = binarySearch(rangeBounds, k)
      // binarySearch either returns the match location or -[insertion point]-1
      if (partition < 0) {
        partition = -partition-1
      }
      if (partition > rangeBounds.length) {
        partition = rangeBounds.length
      }
    }
    // 根据数据排序是升序还是降序进行数据的排列，默认为升序
    if (ascending) {
      partition
    } else {
      rangeBounds.length - partition
    }
  }

  override def equals(other: Any): Boolean = other match {
    case r: RangePartitioner[_, _] =>
      r.rangeBounds.sameElements(rangeBounds) && r.ascending == ascending
    case _ =>
      false
  }

  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    var i = 0
    while (i < rangeBounds.length) {
      result = prime * result + rangeBounds(i).hashCode
      i += 1
    }
    result = prime * result + ascending.hashCode
    result
  }

  @throws(classOf[IOException])
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {
    val sfactory = SparkEnv.get.serializer
    sfactory match {
      case js: JavaSerializer => out.defaultWriteObject()
      case _ =>
        out.writeBoolean(ascending)
        out.writeObject(ordering)
        out.writeObject(binarySearch)

        val ser = sfactory.newInstance()
        Utils.serializeViaNestedStream(out, ser) { stream =>
          stream.writeObject(scala.reflect.classTag[Array[K]])
          stream.writeObject(rangeBounds)
        }
    }
  }

  @throws(classOf[IOException])
  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    val sfactory = SparkEnv.get.serializer
    sfactory match {
      case js: JavaSerializer => in.defaultReadObject()
      case _ =>
        ascending = in.readBoolean()
        ordering = in.readObject().asInstanceOf[Ordering[K]]
        binarySearch = in.readObject().asInstanceOf[(Array[K], K) => Int]

        val ser = sfactory.newInstance()
        Utils.deserializeViaNestedStream(in, ser) { ds =>
          implicit val classTag = ds.readObject[ClassTag[Array[K]]]()
          rangeBounds = ds.readObject[Array[K]]()
        }
    }
  }
}

/**
  * 【步骤】
  * RangePartitioner的重点是在于构建rangeBounds数组对象，主要步骤是：
  * 1. 如果分区数量小于2或者rdd中不存在数据的情况下，直接返回一个空的数组，不需要计算range的边界；如果分区数据大于1的情况下，而且rdd中有数据的情况下，才需要计算数组对象；
  * 2. 计算总体的数据抽样大小sampleSize，计算规则是：至少每个分区抽取20个数据或者最多1M的数据量；
  * 3. 根据sampleSize和分区数量计算每个分区的数据抽样样本数量sampleSizePrePartition；
  * 4. 调用RangePartitioner的sketch函数进行数据抽样，计算出每个分区的样本；
  * 5. 计算样本的整体占比以及数据量过多的数据分区，防止数据倾斜；
  * 6. 对于数据量比较多的RDD分区调用RDD的sample函数API重新进行数据抽取；
  * 7. 将最终的样本数据通过RangePartitoner的determineBounds函数进行数据排序分配，计算出rangeBounds；
  */
private[spark] object RangePartitioner {

  /**
   * Sketches the input RDD via reservoir sampling on each partition.
   *
   * @param rdd the input RDD to sketch
   * @param sampleSizePerPartition max sample size per partition
   * @return (total number of items, an array of (partitionId, number of items, sample))
   *
   * RangePartitioner的sketch函数的作用是对RDD中的数据按照需要的样本数据量进行数据抽取，主要调用
   * SamplingUtils类的reservoirSampleAndCount方法对每个分区进行数据抽取，抽取后计算出整体所有
   * 分区的数据量大小。
   */
  def sketch[K : ClassTag](
      rdd: RDD[K],
      sampleSizePerPartition: Int): (Long, Array[(Int, Long, Array[K])]) = {
    val shift = rdd.id
    // val classTagK = classTag[K] // to avoid serializing the entire partitioner object
    val sketched = rdd.mapPartitionsWithIndex { (idx, iter) =>
      val seed = byteswap32(idx ^ (shift << 16))
      val (sample, n) = SamplingUtils.reservoirSampleAndCount(
        iter, sampleSizePerPartition, seed)
      Iterator((idx, n, sample))
    }.collect()
    val numItems = sketched.map(_._2).sum
    (numItems, sketched)
  }

  /**
   * Determines the bounds for range partitioning from candidates with weights indicating how many
   * items each represents. Usually this is 1 over the probability used to sample this candidate.
   *
   * @param candidates unordered candidates with weights
   * @param partitions number of partitions
   * @return selected bounds
   */
  def determineBounds[K : Ordering : ClassTag](
      candidates: ArrayBuffer[(K, Float)],
      partitions: Int): Array[K] = {
    val ordering = implicitly[Ordering[K]]
    val ordered = candidates.sortBy(_._1)
    val numCandidates = ordered.size
    val sumWeights = ordered.map(_._2.toDouble).sum
    val step = sumWeights / partitions
    var cumWeight = 0.0
    var target = step
    val bounds = ArrayBuffer.empty[K]
    var i = 0
    var j = 0
    var previousBound = Option.empty[K]
    while ((i < numCandidates) && (j < partitions - 1)) {
      val (key, weight) = ordered(i)
      cumWeight += weight
      if (cumWeight >= target) {
        // Skip duplicate values.
        if (previousBound.isEmpty || ordering.gt(key, previousBound.get)) {
          bounds += key
          target += step
          j += 1
          previousBound = Some(key)
        }
      }
      i += 1
    }
    bounds.toArray
  }
}
