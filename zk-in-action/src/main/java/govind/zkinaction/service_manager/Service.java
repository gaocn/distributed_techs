package govind.zkinaction.service_manager;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface Service {
    Logger logger = LoggerFactory.getLogger(Service.class);

    /**
     * 服务节点父目录，名称应根据具体服务设置
     */
    String BASE_SERVICE_PATH =  "/service";

    /**
     * 创建临时顺序节点目录的前缀
     */
    String SERVICE_NODE_PREFIX = "/node_";

    /**
     * ZooKeepe连接参数
     */
    String CONN_STR="10.233.87.54:9080";

    int SESSION_TIMEOUT = 2000;

    int CONN_TIMEOUT = 5000;

    String SERVICE_NAMESPACE = "SERVICE_NODES_MANAGER";
    /**
     * 重连策略
     */
    int RETRY_TIMES =  10;
    int RETRY_INTERVAL  = 1000;

    default CuratorFramework initZKClient() {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(RETRY_INTERVAL,
                RETRY_TIMES);

        CuratorFramework client = CuratorFrameworkFactory.builder()
                .retryPolicy(retryPolicy)
                .connectString(CONN_STR)
                .connectionTimeoutMs(CONN_TIMEOUT)
                .sessionTimeoutMs(SESSION_TIMEOUT)
                .build();
        client.start();
        client.usingNamespace(SERVICE_NAMESPACE);
        logger.info("ZK客户端连接成功...,client=[{}]", client);
        return client;
    }
}
