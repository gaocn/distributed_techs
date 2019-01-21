package govind.election

import scala.collection.mutable.ArrayBuffer

object ZKLeaderElectionTest extends App {
	class Candidate(val name:String) extends Thread with LeaderElectable {
		override def electedLeader(): Unit = {
			println(s"${name} is master")
		}

		override def revokedLeadership(): Unit = {
			println(s"${name} leader ship is revoked")
		}

		@volatile var isStoped = false
		var agent:ZookeeperLeaderElectionAgent = _
		override def run(): Unit = {
			agent = new ZookeeperLeaderElectionAgent(this)
			this.setName(name)
			while(!isStoped) {
				Thread.sleep(scala.util.Random.nextInt(9) * 1000)
			}
		}
		def stopCandidate(): Unit = {
			isStoped = true
			agent.stop()
		}
	}

	val candidates = ArrayBuffer[Candidate]()
	var candidate:Candidate = _
	for (i <- 0 to 10) {
		candidate = new Candidate(s"EligableLeader ${i}")
		candidates += candidate
	}
	candidates.foreach(_.start)

	while(true) {
		Thread.sleep(10000)
		val toStop = scala.util.Random.nextInt(candidates.size)
		if(candidates.size > 1) {
			println(s"random to kill ${toStop}")
			candidates(toStop).stopCandidate()
			candidates.remove(toStop)
		}
	}
}
