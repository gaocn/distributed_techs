高可用HA：是在有单节点故障的系统中对容易出现故障的系统进行处理方法，保证系统出现故障时仍然可用。从Spark的角度看，Spark集群中Master比较容易出故障。Spark支持：stand alone、基于ZK的高可用模式、YARN和MESOS，无论哪一种方式Spark内部的原理一样的，因为Spark是基于接口的编程，采用可插拔方式运行。Master负责管理集群中的所有资源，所以Master容易出现单点故障问题。

例如：Driver若是在Spark集群中运行，也有可能挂掉，因此有supervise模式支持挂掉后重启Driver，并在原有基础上正常运行。

通过源码角度看一下Spark的HA模式，源码有两个层次（脚本和日志、代码）。这里在启动日志和脚本就是对已有行为的记录。而

Master被Worker注册时会怎么做？

## 配置基于ZK的高可用，解决SPOF（Single Point of Failure ）单点故障问题
In order to enable this recovery mode, you can set SPARK_DAEMON_JAVA_OPTS in spark-env using this configuration（在spark-env.sh中添加该配置）:
- spark.deploy.recoveryMode：Set to ZOOKEEPER to enable standby Master recovery mode (default: NONE).
- spark.deploy.zookeeper.url：The ZooKeeper cluster url (e.g., 192.168.1.100:2181,192.168.1.101:2181).
- spark.deploy.zookeeper.dir： The directory in ZooKeeper to store recovery state (default: /spark).

 For example, you might start your `SparkContext` pointing to `spark://host1:port1,host2:port2`. This would cause your SparkContext to try registering with both Masters – if host1 goes down, this configuration would still be correct as we’d find the new leader, host2.

There’s an important distinction to be made between “registering with a Master” and normal operation. When starting up, an application or Worker needs to be able to find and register with the current lead Master. Once it successfully registers, though, it is “in the system” (i.e., stored in ZooKeeper). If failover occurs, the new leader will contact all previously registered applications and Workers to inform them of the change in leadership, so they need not even have known of the existence of the new Master at startup.

## 基于ZK的Master选举
1. `ZooKeeperPersistenceEngine` 用于调用CuratorFramework对创建节点、持久化数据到ZK中
2. `ZooKeeperLeaderElectionAgent`用于Leader选举，实现`LeaderLatchListener`异步接口，若节点被选择为Leader这isLeader会被调用，若被剥夺了Leader角色，则notLeader会被调用。由于LeaderLatchListener是异步接口，有可能和实际情况不一样，在isLeader和notLeader被调用时都需要进一步确认自己的状态。

`LeaderLatch`为普通的Java类，负责在众多的连接到ZK的Masters的竞争者中，选举出一个Leader，选举机制就是投票。
监听器模式
LeaderLatch.addListener(LeaderLatchListener)添加监听器，在集群Leader状态发生改变时会调用其中的LeaderLatchListener告知其状态（回调）。

```
class ZooKeeperLeaderElectionAgent(val masterInstance: LeaderElectable, conf: SparkConf) extends LeaderLatchListener with LeaderElectionAgent
public interface LeaderLatchListener {
    void isLeader();
    void notLeader();
}
```
master实现了LeaderElectable接口用于在节点被选举为Leader时回调，以便Master节点进行状态恢复或者停止Master进程，具体可以参见Master的源码
```
trait LeaderElectable {
  def electedLeader()
  def revokedLeadership()
}
```



