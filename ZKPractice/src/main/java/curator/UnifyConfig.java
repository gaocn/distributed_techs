package curator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.RetryNTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

public class UnifyConfig {
    private static final Logger logger = LoggerFactory.getLogger(UnifyConfig.class);
    final static String CONFIG_PATH = "/redis";
    final static String SUB_CONFIG_PATH = "/config";
    final static String CONN_STR="10.233.87.241:9080,10.233.87.54:9080";
    final static int SESSION_TIMEOUT = 2000;
    final static int CONN_TIMEOUT = 5000;

    //用于挂起主进程
    public static CountDownLatch latch =  new CountDownLatch(1);

    private static CuratorFramework client = null;

    public static void main(String[] args) {
        //启动Client

        try {
             client = CuratorFrameworkFactory.builder()
                    .retryPolicy(new RetryNTimes(10,  1000))
                    .connectString(CONN_STR)
                    .sessionTimeoutMs(SESSION_TIMEOUT)
                    .connectionTimeoutMs(CONN_TIMEOUT)
                    .build();
            logger.info("客户端连接成功，[{}]", client);

            /**
             * 采用PathChildrenCache对配置进行监控
             */

            final PathChildrenCache configNode = new PathChildrenCache(client, CONFIG_PATH,true);
            configNode.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            configNode.getListenable().addListener(
              new PathChildrenCacheListener() {
                  public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                      if (event.getType()== PathChildrenCacheEvent.Type.CHILD_UPDATED)  {
                          // 确保发生数据更新的路径为/redis/config
                          String pathChanged  = event.getData().getPath();
                          if (pathChanged.equals(CONFIG_PATH+SUB_CONFIG_PATH)) {
                              logger.info("[{}]配置发生改变，需要同步更新", pathChanged);

                              //读取节点数据
                              String jsonConfig = new String(event.getData().getData());
                              logger.info("节点[{}]的配置为：{}", pathChanged, jsonConfig);

                              //json配置转换为POJO

                          }
                      }
                  }
              }
            );

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (client != null) {
                client.close();
                logger.info("关闭会话");
            }
        }

    }

}
