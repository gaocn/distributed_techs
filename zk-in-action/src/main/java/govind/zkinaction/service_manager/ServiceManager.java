package govind.zkinaction.service_manager;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

public class ServiceManager extends Thread implements Service{
    private final static Logger logger = LoggerFactory.getLogger(ServiceManager.class);
    private CuratorFramework client;
    private List<String> availableServices;

    public ServiceManager() {
        availableServices = new LinkedList<>();
        client = initZKClient();
    }

    private void refreshAvailableServices() {
        if (client == null) { logger.info("无法连接ZK"); return;}

        try {
            String basePath = BASE_SERVICE_PATH;
            List<String> childrenPaths = client.getChildren().forPath(basePath);

            for (String path : childrenPaths) {
                byte[] data = client.getData().forPath(basePath + "/" +path);
                String serviceName = new String(data);
                availableServices.add(serviceName);
                logger.info("可用服务节点=[{}], 可用服务节点数据=[{}]", path, serviceName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addWatcher() {
        if (client == null) { logger.info("无法连接ZK"); return;}

        try {
            final PathChildrenCache watcher = new PathChildrenCache(client, BASE_SERVICE_PATH,true);
            watcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            watcher.getListenable().addListener(
                    new PathChildrenCacheListener() {
                        @Override
                        public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {

                            switch (event.getType()) {
                                case CHILD_REMOVED:
                                    logger.info("监听到事件[{}]", event.getType());
                                    refreshAvailableServices();
                                    break;
                                case CHILD_ADDED:
                                    logger.info("监听到事件[{}]", event.getType());
                                    refreshAvailableServices();
                                    break;
                                default:
                                    logger.info("监听到事件[{}]", event.getType());
                                    break;
                            }
                        }
                    }
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        refreshAvailableServices();
        addWatcher();
    }

    public List<String> getAvailableServers() {
        return availableServices;
    }

    public void close() {
        if (client  != null) {
            client.close();
        }
    }
}
