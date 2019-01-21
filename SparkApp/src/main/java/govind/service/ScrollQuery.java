package govind.service;

import govind.conf.ESConf;
import govind.rdd.ESPartitionInfo;
import govind.rdd.Slice;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

@Slf4j
@ToString
public class ScrollQuery implements Iterator<Object>, Closeable{
	private JRestRepository restRepository;
	private ESPartitionInfo esPartitionInfo;
	private String scrollId;
	private Object[] batch = null;
	private int batchIndex = 0;

	private boolean finished = false;
	private boolean closed = false;
	private boolean initialized = false;

	private String query;
	private String body;

	private long read = 0;
	// how many docs to read - in most cases, all the docs that match
	private long size;

	public ScrollQuery(JRestRepository restRepository, String query, String body, long size, ESPartitionInfo esPartitionInfo) {
		this.restRepository = restRepository;
		this.query = query;
		this.body = body;
		this.size = size;
		this.esPartitionInfo = esPartitionInfo;
	}

	@Override
	public void close() {
		if (!closed) {
			closed  = true;
			finished = true;
			batch = null;
			if (!scrollId.isEmpty()) {
				restRepository.deleteScroll(scrollId);
				restRepository.close();
			}
		}
	}

	@Override
	public boolean hasNext() {
		if (finished){
			return false;
		}

		if (!initialized) {
			initialized = true;
			try {
				Scroll scroll = restRepository.scroll(esPartitionInfo, query, body);
				this.scrollId = scroll.getScrollId();
				size = (size < 1 ? scroll.getHitsTotal() : size);
				batch = scroll.getHits();
				read += batch.length;
			} catch (Exception e) {
				throw new RuntimeException(String.format("can not create scroll for query [%s/%s]", query, body));
			}

			//no longer needed
			query = null;
			body = null;
		}else  {
			if (read >= size) {
				finished = true;
				return false;
			}

			Scroll scroll = restRepository.scroll(scrollId);
			if (scroll == null) {
				log.info("no docs available for scrollid[{}]", scrollId);
				finished = true;
				restRepository.close();
				return false;
			}
			batch = scroll.getHits();
			read += batch.length;
		}


		return true;
	}

	@Override
	public Object[] next() {
//		if (!hasNext()) {
//			throw new NoSuchElementException("No More Documents Avaiable!");
//		}
		return batch;
	}

	public static void main(String[] args) {
		ESConf conf = new ESConf();
		JRestRepository restRepository = new JRestRepository(conf);

		ESPartitionInfo  esPartitionInfo = new ESPartitionInfo("kafka_log_mbank_log-2019.01.18", 0, new Slice(0, 2), new String[]{"10.230.150.175:9200"});
		String body = "{" +
				"\"slice\": {" +
				"\"id\": "+ esPartitionInfo.slice().id() +"," +
				"\"max\": "+ esPartitionInfo.slice().max()+"" +
				"}," +
				"\"query\": {" +
				"\"match_all\": {}" +
				"}" +
				"}";
		ScrollQuery scrollQuery = new ScrollQuery(restRepository, esPartitionInfo.index()+"/_search", body, 20,esPartitionInfo);

		while (scrollQuery.hasNext()) {
			Object[] batch = scrollQuery.next();
			log.info("batch: {}, size: {}", batch[0], batch.length);
		}
		scrollQuery.close();
	}
}
