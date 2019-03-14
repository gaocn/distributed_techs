package govind.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Map;

@Getter
@Setter
@ToString
public class ShardInfo implements Serializable, Comparable<ShardInfo> {
	@Override
	public int compareTo(ShardInfo o) {
		return this.shard - o.shard;
	}

	public enum State {
		UNASSIGNED, INITIALIZING, STARTED, RELOCATING;

		public boolean isStarted() {
			return STARTED == this;
		}
	}
	/**
	 * state : STARTED
	 * primary : true
	 * node : Y9WVHA2NTYWqulLKaxhs6Q
	 * relocating_node : null
	 * shard : 0
	 * index : kafka_log_mbank_log-2019.01.11
	 * allocation_id : {"id":"cBpf_WFFQzy7u08Nv0noLA"}
	 */
	private State state;
	private boolean primary;
	private String node;
	private String relocatingNode;
	private Integer shard;
	private String index;

	public ShardInfo(Map<String, Object> map) {
		this.state = State.valueOf((String)map.get("state"));
		this.node = (String)map.get("node");
		this.primary = Boolean.TRUE.equals(map.get("primary"));
		this.relocatingNode = (String) map.getOrDefault("relocating_node", "");
		this.shard = (Integer) map.get("shard");
		this.index = (String) map.get("index");
	}
}
