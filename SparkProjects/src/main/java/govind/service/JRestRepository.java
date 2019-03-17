package govind.service;

import govind.JRestClient;
import govind.entity.NodeInfo;
import govind.entity.ShardInfo;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.elasticsearch.client.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class JRestRepository {
	private static final int MAX_DOC_COUNT_PER_PARTITION =  10000;
	private JRestClient client;
	private ESconf conf;
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
		String endpoint = String.format("%s/_search?size=0&&preference=_shards:%d", index, shard);
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

	public static void main(String[] args) {
		ESConf conf = new ESConf();
		JRestRepository restRepository = new JRestRepository(conf);
//		log.info("args = [" + restRepository.count() + "]");
//		List<List<ShardInfo>> shards = restRepository.getShardsInfo(conf.esIndex(),"");
//		log.info(shards.toString());
		List<ESPartitionInfo> partitionInfos = restRepository.findPartitions();
		log.info(partitionInfos.toString());
		restRepository.close();
	}
}
