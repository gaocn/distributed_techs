package govind.election

trait LeaderElectionAgent {
	val masterInstance: LeaderElectable
	def stop() {} // to avoid noops in implementations.
}

trait LeaderElectable {
	def electedLeader()
	def revokedLeadership()
}
