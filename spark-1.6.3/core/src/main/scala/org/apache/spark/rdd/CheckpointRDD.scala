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

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark.{Partition, SparkContext, TaskContext}

/**
  * An RDD partition used to recover checkpointed data.
  *
  *                   -------------------
  *                   |RDDCheckpointData|
  *                   ------*-----*------
  *                         |     |
  *            |------------|     |---------------|
  * ------------------------           ---------------------------
  * |LocalRDDCheckpointData|           |ReliableRDDCheckpointData|
  * ------------------------           ---------------------------
  *           |--------------+-----------+------------|
  *               |调用doCheckpoint方法产生对应的RDD|
  *               --------------------------------
  *                       -------|-------
  *                       |CheckpointRDD|
  *                       ----*-----*---
  *                           |     |
  *              |------------|     |---------------|
  * --------------------           -----------------------
  * |LocalCheckpointRDD|           |ReliableCheckpointRDD|
  * --------------------           -----------------------
  * 说明：
  * 1、RDDCheckpointData执行过程中的状态：Initializing -> CheckpointInProgress -> Chechpointed
  * 2、每个RDDCheckpointData实例关联一个RDD实例，负责具体RDD数据的持久化操作，包含一个CheckpointRDD实例！
  * 3、LocalRDDChechpointData其内部使用persist方法保存数据到本地，存储级别为MEMORY_AND_DISK；
  * 4、LocalChechpointRDD为dummy RDD，在使用LocalCheckPointRDD计算时会报错，因为存储在内存或本地的数据会被清理不完整、数据不可靠；
  * 5、ReliableRDDCheckpointData会将数据保存在可靠的存储中，如HDFS中，会在${checkpointDir}/rdd-${rddId}目录下存放数据
  * （1）、当该RDD上的第一Action执行完成后就会调用ReliableRDDCheckpointData.doCheckpoint方法，
  * （2）、在doCheckpoint方法内部会调用ReliableCheckpointRDD.writeRDDToCheckpointDirectory(rdd, cpDir)将数据写入外部存储中。
  * （3）、在writeRDDToCheckpointDirectory内部
  *   - 调用sc.runJob(originalRDD,writePartitionToCheckpointFile[T](checkpointDirPath.toString, broadcastedConf) _)执行一个Action操作，writePartitionToCheckpointFile针对每一个分区写入一个文件，文件名为："part-%05d".format(partitionid)。
  *   - 若RDD的Partitioner不为空则将其保存到名为"_partitioner"文件中。
  *   - 返回ReliableCheckpointRDD实例
  */
private[spark] class CheckpointRDDPartition(val index: Int) extends Partition

/**
 * An RDD that recovers checkpointed data from storage.
 */
private[spark] abstract class CheckpointRDD[T: ClassTag](sc: SparkContext)
  extends RDD[T](sc, Nil) {

  // CheckpointRDD should not be checkpointed again
  override def doCheckpoint(): Unit = { }
  override def checkpoint(): Unit = { }
  override def localCheckpoint(): this.type = this

  // Note: There is a bug in MiMa that complains about `AbstractMethodProblem`s in the
  // base [[org.apache.spark.rdd.RDD]] class if we do not override the following methods.
  // scalastyle:off
  protected override def getPartitions: Array[Partition] = ???
  override def compute(p: Partition, tc: TaskContext): Iterator[T] = ???
  // scalastyle:on

}
