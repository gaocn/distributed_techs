package govind.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ToString
public class NodeInfo implements Serializable {
	private  String id;
	private  String name;
	private  String host;
	private  String ip;
	private  String publishAddress;
	private  boolean hasHttp;
	private  boolean isClient;
	private  boolean isData;
	private  boolean isIngest;

	public NodeInfo(String id, Map<String, Object> map) {
		this.id = id;
		this.name = (String) map.get("name");
		this.host = (String) map.get("host");
		this.ip = (String) map.get("ip");

		List<String> roles = (List<String>) map.get("roles");
		this.isClient = roles.contains("data") == false;
		this.isData = roles.contains("data");
		this.isIngest = roles.contains("ingest");
		Map<String, Object> httpMap = (Map<String, Object>) map.get("http");
		if (httpMap != null) {
			String addr = (String) httpMap.get("publish_address");
			if (addr != null) {
				IpAndPort ipAndPort = parseIpAddress(addr);
				this.publishAddress = ipAndPort.ip + ":" + ipAndPort.port;
				this.hasHttp = true;
			} else {
				this.publishAddress = null;
				this.hasHttp = false;
			}
		} else {
			this.publishAddress = null;
			this.hasHttp = false;
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o){ return true;}
		if (o == null || getClass() != o.getClass()){ return false;}

		NodeInfo nodeInfo = (NodeInfo) o;

		if (hasHttp != nodeInfo.hasHttp){ return false;}
		if (isClient != nodeInfo.isClient){ return false;}
		if (isData != nodeInfo.isData){ return false;}
		if (isIngest != nodeInfo.isIngest){ return false;}
		if (id != null ? !id.equals(nodeInfo.id) : nodeInfo.id != null){ return false;}
		if (name != null ? !name.equals(nodeInfo.name) : nodeInfo.name != null){ return false;}
		if (host != null ? !host.equals(nodeInfo.host) : nodeInfo.host != null){ return false;}
		if (ip != null ? !ip.equals(nodeInfo.ip) : nodeInfo.ip != null) {return false;}
		return publishAddress != null ? publishAddress.equals(nodeInfo.publishAddress) : nodeInfo.publishAddress == null;
	}

	@Override
	public int hashCode() {
		int result = id != null ? id.hashCode() : 0;
		result = 31 * result + (name != null ? name.hashCode() : 0);
		result = 31 * result + (host != null ? host.hashCode() : 0);
		result = 31 * result + (ip != null ? ip.hashCode() : 0);
		result = 31 * result + (publishAddress != null ? publishAddress.hashCode() : 0);
		result = 31 * result + (hasHttp ? 1 : 0);
		result = 31 * result + (isClient ? 1 : 0);
		result = 31 * result + (isData ? 1 : 0);
		result = 31 * result + (isIngest ? 1 : 0);
		return result;
	}

	public static IpAndPort parseIpAddress(String httpAddr) {
		// strip ip address - regex would work but it's overkill

		// there are four formats - ip:port, hostname/ip:port or [/ip:port] and [hostname/ip:port]
		// first the ip is normalized
		if (httpAddr.contains("/")) {
			int startIp = httpAddr.indexOf("/") + 1;
			int endIp = httpAddr.indexOf("]");
			if (endIp < 0) {
				endIp = httpAddr.length();
			}
			if (startIp < 0) {
				throw new IllegalArgumentException("Cannot parse http address " + httpAddr);
			}
			httpAddr = httpAddr.substring(startIp, endIp);
		}

		// then split
		int portIndex = httpAddr.lastIndexOf(":");

		if (portIndex > 0) {
			String ip = httpAddr.substring(0, portIndex);
			int port = Integer.valueOf(httpAddr.substring(portIndex + 1));
			return new IpAndPort(ip, port);
		}
		return new IpAndPort(httpAddr);
	}
	public static class IpAndPort {
		public final String ip;
		public final int port;

		IpAndPort(String ip, int port) {
			this.ip = ip;
			this.port = port;
		}

		IpAndPort(String ip) {
			this.ip = ip;
			this.port = 0;
		}

		@Override
		public String toString() {
			return (port > 0 ? ip + ":" + port : ip);
		}
	}
}
