package govind.election

import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

class ZookeeperLeaderElectionAgent(val masterInstance:LeaderElectable) extends LeaderLatchListener with LeaderElectionAgent {
	val CONN_STR = "localhost:2181"
	val WORKING_DIR = "/spark/leader_election"

	//ZK client
	private var zk: CuratorFramework =  _
	//
	private var leaderLatch:LeaderLatch = _

	//当前节点是否为leader，默认为否
	private var status = LeaderShipStatus.NOT_LEADER

	//构建实例时就启动
	start()

	private def start(): Unit = {
		println("Starting Zookeeper LeaderElection agent")
		zk = CuratorFrameworkFactory.builder()
  			.connectString(CONN_STR)
  			.sessionTimeoutMs(10000)
  			.connectionTimeoutMs(10000)
  			.retryPolicy(new ExponentialBackoffRetry(5000, 3))
  			.build()
		zk.start()

		leaderLatch = new LeaderLatch(zk, WORKING_DIR)
		leaderLatch.addListener(this)
		leaderLatch.start() //开始进行选举
	}

	override def stop(): Unit = {
		leaderLatch.close()
		zk.close()
	}

	//leaderLatch参选后，若被选举为Leader会调用isLeader方法
	override def isLeader: Unit = {
		synchronized{
			//可能会立即失去LeaderShip
			if(!leaderLatch.hasLeadership) {
				return
			}
			println(s"${Thread.currentThread().getName} have gained LeaderShip")
			updateLeaderShipStatus(true)
		}
	}

	//若失去LeaderShip则会调用 notLeader方法
	override def notLeader(): Unit = {
		synchronized {
			//可能立即获得LeaderShip
			if(leaderLatch.hasLeadership) {
				return
			}
			print(s"${Thread.currentThread().getName} have loss LeaderShip")
			updateLeaderShipStatus(false)
		}
	}

	private def updateLeaderShipStatus(isLeader: Boolean): Unit = {
		if(isLeader &&  status == LeaderShipStatus.NOT_LEADER) {
			status = LeaderShipStatus.LEADER
			masterInstance.electedLeader()
		}  else if(!isLeader && status == LeaderShipStatus.LEADER) {
			status = LeaderShipStatus.NOT_LEADER
			masterInstance.revokedLeadership()
		}
	}
}

private object LeaderShipStatus extends Enumeration {
	type LeaderShipStatus = Value
	val LEADER, NOT_LEADER = Value
}

