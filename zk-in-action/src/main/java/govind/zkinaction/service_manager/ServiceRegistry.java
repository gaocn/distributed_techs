package govind.zkinaction.service_manager;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceRegistry extends Thread implements Service{
    private final static Logger logger = LoggerFactory.getLogger(ServiceRegistry.class);
    private CuratorFramework client;
    private String serverName;

    public ServiceRegistry(String serverName) {
        this.serverName = serverName;
        this.client = initZKClient();
    }

    private void regist() {
        if (client  == null) {
            logger.warn("ZK客户端无法连接成功！");
            return;
        }

        try {
            String path = BASE_SERVICE_PATH + SERVICE_NODE_PREFIX;
            String registPath = client.create().withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                    .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                    .forPath(path, serverName.getBytes());

            logger.info("[{}]注册成功，注册路径为[{}]", serverName, registPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        regist();
    }

    public void close() {
        if (client  != null) {
            client.close();
            logger.warn("[{}]注销", serverName);
        }
    }
}
