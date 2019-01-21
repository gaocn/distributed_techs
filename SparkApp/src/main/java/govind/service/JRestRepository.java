package govind.service;

import govind.JRestClient;
import govind.conf.ESConf;
import govind.entity.NodeInfo;
import govind.entity.ShardInfo;
import govind.rdd.ESPartitionInfo;
import govind.rdd.Slice;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.elasticsearch.client.Response;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class JRestRepository {
	public static final int MAX_DOC_COUNT_PER_PARTITION =  1000;
	private JRestClient client;
	private ESConf conf;
	private ObjectMapper mapper;

	public JRestRepository(ESConf conf) {
		this.conf = conf;
		this.client = new JRestClient(conf);
		mapper = new ObjectMapper();
		mapper.configure(DeserializationConfig.Feature.USE_ANNOTATIONS, false);
		mapper.configure(SerializationConfig.Feature.USE_ANNOTATIONS, false);
	}

	public int count() {
		String  endpoint = conf.esIndex() + "/_count";
		Response response = client.execute(endpoint, HttpMethod.GET);
		int count = (int) parseContent(response.getEntity(), "count");
		return count;
	}

	public int countPerShard(String index, int shard) {
		String endpoint = String.format("%s/_search?size=0&preference=_shards:%d", index, shard);
		Response response = client.execute(endpoint, HttpMethod.GET);
		Map<String, Object> map = (Map<String, Object>) parseContent(response.getEntity(), "hits");
		return (int)map.getOrDefault("total", 0);
	}

	public Map<String, NodeInfo> getNodesInfo(boolean clientNodeOnly) {
		String endpoint = "/_nodes/http";
		Response response = client.execute(endpoint, HttpMethod.GET);
		Map<String, Map<String, Object>> nodesData = (Map<String, Map<String, Object>>) parseContent(response.getEntity(), "nodes");

		Map<String, NodeInfo> nodeInfos = new HashMap<>();
		for (String key : nodesData.keySet()) {
			NodeInfo nodeInfo = new NodeInfo(key, nodesData.get(key));
			if (nodeInfo.isHasHttp() &&(!clientNodeOnly || nodeInfo.isClient())) {
				nodeInfos.put(key, nodeInfo);
			}
		}
		return nodeInfos;
	}

	private Object parseContent(HttpEntity entity, String key) {
		Object result = null;
		try {
			String content = EntityUtils.toString(entity);
			Map map = mapper.readValue(content, Map.class);
			result = map.get(key);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	public List<List<Map<String, Object>>> targetShards(String index, String routing) {
		String endpoint =  index + "/_search_shards";
		Response response = client.execute(endpoint, HttpMethod.GET);
		List<List<Map<String, Object>>> shardsInfo = (List<List<Map<String, Object>>>)parseContent(response.getEntity(), "shards");

		log.info(shardsInfo.toString());

		return shardsInfo;
	}

	public List<List<ShardInfo>> getShardsInfo(String index, String routing) {
		List<List<Map<String, Object>>> shardsInfo = targetShards(index, routing);
		List<List<ShardInfo>> shards = new ArrayList<>();

		shardsInfo.forEach( perShard -> {
			List<ShardInfo> perShardsInfo = new ArrayList<>();
			perShard.forEach(shard -> {
				ShardInfo shardInfo = new ShardInfo(shard);
				perShardsInfo.add(shardInfo);
			 }
			);
			shards.add(perShardsInfo);
		 }
		);
		return shards;
	}
	public void close() {
		client.close();
	}


	public List<ESPartitionInfo> findPartitions()  {
		Map<String, NodeInfo> nodes = getNodesInfo(false);
		List<List<ShardInfo>> shards = getShardsInfo(conf.esIndex(),"");
		List<ESPartitionInfo> partitions = findSlicePartitions(nodes, shards);

		return partitions;
	}


	/**
	 * {@link govind.rdd.ESPartitionInfo} per shard for each requested index
	 */
	public List<ESPartitionInfo> findSlicePartitions (Map<String, NodeInfo> nodes , List<List<ShardInfo>> shards) {
		List<ESPartitionInfo> partitionInfos = new ArrayList<>();
		shards.forEach(group -> {
			AtomicReference<String> index = new AtomicReference<>("");
			AtomicInteger shardId = new AtomicInteger(-1);
			String[] locations = new String[group.size()];
			AtomicInteger idx = new AtomicInteger();
			group.forEach(shard -> {
				index.set(shard.getIndex());
				shardId.set(shard.getShard());
				if (nodes.containsKey(shard.getNode())) {
					locations[idx.getAndIncrement()] = nodes.get(shard.getNode()).getPublishAddress();
				}
			});
			int shardCount =  countPerShard(index.get(), shardId.get());
			int numSlices = shardCount / MAX_DOC_COUNT_PER_PARTITION + 1;
			for (int i = 0; i < numSlices; i++) {
				Slice slice = new Slice(i, numSlices);
				ESPartitionInfo partitionInfo = new ESPartitionInfo(index.get(), shardId.get(), slice,  locations);
				partitionInfos.add(partitionInfo);
			}
		});
		return partitionInfos;
	}


	//#############################################################
	/** Sliced Scroll
	 * POST 10.230.150.175:9200/kafka_log_mbank_log-2019.01.18/_search?scroll=1m&preference=_shards:0
	 * {
	 *     "slice": {
	 *         "id": 0,
	 *         "max": 2
	 *     },
	 *     "query": {
	 *         "match_all" : {}
	 *     }
	 * }
	 *
	 * 注意/_search/scroll前面不能添加任何索引
	 * POST /_search/scroll
	 * {
	 *     "scroll" : "1m",
	 *     "scroll_id" : "DXF1ZXJ5QW5kRmV0Y2gBAAAAAAAGS7cWWTlXVkhBMk5UWVdxdWxMS2F4aHM2UQ=="
	 * }
	 *
	 * DELETE /_search/scroll
	 * {
	 *     "scroll_id" : ["DXF1ZXJ5QW5kRmV0Y2gBAAAAAAAAAD4WYm9laVYtZndUQlNsdDcwakFMNjU1QQ=="]
	 * }
	 *
	 */
	public Scroll scroll(ESPartitionInfo partitionInfo, String endpoint, String body) {
		Map<String, String> params = new HashMap<>();
		params.put("scroll","5m");
		params.put("preference","_shards:" + partitionInfo.shardID());
		return client.executeWithHostsAndBody(partitionInfo.locations(),endpoint, params , HttpMethod.valueOf("POST"),body);
	}

	public Scroll scroll(String scrollId) {
		String endpoint = "/_search/scroll";
		HttpMethod method =  HttpMethod.POST;
		String body = "{\"scroll\": \"5m\",\"scroll_id\":\""+ scrollId + "\"}";
		log.info("query scroll: {}", body);
		return client.executeScroll(endpoint, method, body);
	}

	public void deleteScroll(String scrollId) {
		try {
			String endpoint = "/_search/scroll";
			HttpMethod method =  HttpMethod.DELETE;
			String body = "{\"scroll_id\":[\""+ scrollId + "\"]}";
			log.info("delete scroll: {}", body);
			client.deleteScroll(endpoint, method, body);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		ESConf conf = new ESConf();
		JRestRepository restRepository = new JRestRepository(conf);

		ESPartitionInfo  esPartitionInfo = new ESPartitionInfo("kafka_log_mbank_log-2019.01.18", 0, new Slice(0, 2), new String[]{"10.230.150.175:9200"});

		log.info("count: {}", restRepository.count());

		String endpoint = esPartitionInfo.index() + "/_search";
		String body = "{" +
						"\"slice\": {" +
							"\"id\": "+ esPartitionInfo.slice().id() +"," +
							"\"max\": "+ esPartitionInfo.slice().max()+"" +
						"}," +
						"\"query\": {" +
							"\"match_all\": {}" +
						"}" +
					"}";
		log.info("endpoint: {}", endpoint);
		log.info("query: {}", body);
		Scroll scroll = restRepository.scroll(esPartitionInfo, endpoint, body);
		log.info("scrol 1:{}", scroll);
		log.info("scrol 2: {}", restRepository.scroll(scroll.getScrollId()));
		log.info("scrol 3: {}", restRepository.scroll(scroll.getScrollId()));
		restRepository.deleteScroll(scroll.getScrollId());

		restRepository.close();
	}
}
