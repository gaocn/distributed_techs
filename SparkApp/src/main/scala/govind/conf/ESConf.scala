package govind.conf

class ESConf {
	val hosts = Array("10.230.150.176:9200", "10.230.150.175:9200")
	val esIndex = "kafka_log_mbank_log-2019.01.18"
	val esType = "logs"
	val query =
		"""{,"query": {"match_all": {}}}"""
	val MAX_DOC_COUNT_PER_PARTITION  = 1000
	val MAX_BATCH_DOC_COUNT_TO_READ  = 100
	val MAX_DOC_TO_READ = Int.MaxValue
}
