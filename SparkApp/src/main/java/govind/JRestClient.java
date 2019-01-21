package govind;

import govind.conf.ESConf;
import govind.service.Scroll;
import lombok.extern.slf4j.Slf4j;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

@Slf4j
public class JRestClient {
	private RestClient client;
	private ESConf conf;
	private ObjectMapper mapper;
	public JRestClient(ESConf conf) {
		this.conf = conf;
		this.client = createClient(conf);
		mapper = new ObjectMapper();
		mapper.configure(DeserializationConfig.Feature.USE_ANNOTATIONS, false);
		mapper.configure(SerializationConfig.Feature.USE_ANNOTATIONS, false);
	}
	private  RestClient createClient(ESConf conf) {
		HttpHost[] hosts = new HttpHost[conf.hosts().length];

		for (int i = 0; i < hosts.length; i++) {
			String[] splits = conf.hosts()[i].split(":");
			hosts[i] = new HttpHost(splits[0], Integer.valueOf(splits[1]), "http");
		}

		RestClientBuilder clientBuilder = RestClient.builder(hosts);
		clientBuilder.setDefaultHeaders(new Header[]{
				new BasicHeader("ESRDD","RDD-ES5.2.0")
		});

		clientBuilder.setMaxRetryTimeoutMillis(30*1000);
		clientBuilder.setFailureListener(new RestClient.FailureListener(){
			@Override
			public void onFailure(HttpHost host) {
				System.out.println("Failed to connect to host: " + conf.hosts());
			}
		});

		clientBuilder.setRequestConfigCallback(builder -> {
			builder.setConnectionRequestTimeout(20000);
			return builder;
		});
		return clientBuilder.build();
	}

	public Response execute(String endpoint, HttpMethod method) {
		return execute(endpoint, method, null);
	}

	public Scroll executeScroll(String endpoint, HttpMethod method, String body) {
		return executeWithHostsAndBody(null, endpoint, Collections.EMPTY_MAP, method, body);
	}
	public Response execute(String endpoint, HttpMethod method, Header header) {
		Response response = null;
		try {
			if (null == header) {
				response = client.performRequest(method.name(), endpoint);
			} else {
				response = client.performRequest(method.name(), endpoint, header);
			}
			if (response.getStatusLine().getStatusCode() != 200) {
				log.info("response code: {}, details: {}", response.getStatusLine().getStatusCode(),
						EntityUtils.toString(response.getEntity()));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return response;
	}

	public Scroll executeWithHostsAndBody(String[] hosts, String endpoint, Map<String,String> params, HttpMethod method, String body) {
		Response response = null;
		if (hosts != null && hosts.length > 0) {
			HttpHost[] httpHosts = new HttpHost[hosts.length];

			for (int i = 0; i < hosts.length; i++) {
				String[] splits = hosts[i].split(":");
				httpHosts[i] = new HttpHost(splits[0], Integer.valueOf(splits[1]), "http");
			}
			client.setHosts(httpHosts);
		}

		try {
			HttpEntity entity = new NStringEntity(body, ContentType.APPLICATION_JSON);
			Header header = new BasicHeader("Content-Type","application/json");
			response = client.performRequest(method.name(), endpoint, params, entity, header);

			if (response.getStatusLine().getStatusCode()  == 200) {
				Scroll scroll = new Scroll();
				Map<String, Object> resultMap =  mapper.readValue(
						EntityUtils.toString(response.getEntity()), Map.class);
				scroll.setScrollId((String) resultMap.get("_scroll_id"));

				Map hits = (Map) resultMap.get("hits");

				scroll.setHitsTotal((int)hits.get("total"));
				scroll.setHits(((ArrayList)hits.get("hits")).toArray());

				log.info("scroll: {}", scroll);
				return scroll;
			} else {

			}
		} catch (Exception e) {
			log.info("请求出错：{}", e.getMessage());
			return null;
		}
		return null;
	}
	public void close() {
		try {
			if(client != null) {
				client.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean deleteScroll(String endpoint, HttpMethod method, String body) throws IOException {
		Response response = client.performRequest(method.name(), endpoint,Collections.emptyMap(), new NStringEntity(body),new BasicHeader("Content-Type","application/json"));
		return response.getStatusLine().getStatusCode() == 200;
	}
}
