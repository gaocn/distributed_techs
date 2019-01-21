package govind.service
import govind.conf.ESConf

class RestService(esConf: ESConf) {
	val restRepository = new JRestRepository(esConf)



}
