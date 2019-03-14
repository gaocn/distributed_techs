package govind.conf

class ESConf {
	val hosts = Array("10.230.150.175:9200","10.230.150.175:9200")
	val esIndex = "kafka_log_mbank_log-2019.01.15"
	val esType = "logs"
	val query =
		"""{"query": {"match_all": {}}}"""
}
