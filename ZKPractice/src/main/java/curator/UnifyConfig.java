package curator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import curator.model.RedisConfig;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
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
        try {

            RetryPolicy retryPolicy = new ExponentialBackoffRetry(0, 10);

             //启动Client
             client = CuratorFrameworkFactory.builder()
                    .retryPolicy(retryPolicy)
                    .connectString(CONN_STR)
                    .sessionTimeoutMs(SESSION_TIMEOUT)
                    .connectionTimeoutMs(CONN_TIMEOUT)
                    .build();
             client.start();
             logger.info("客户端连接成功，[{}]", client);

            /**
             * 采用PathChildrenCache对配置进行监控
             */

            final PathChildrenCache configNode = new PathChildrenCache(client, CONFIG_PATH,true);
            configNode.start();

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
                              RedisConfig redisConfig =  new Gson().fromJson(jsonConfig, RedisConfig.class);

                              if (redisConfig !=  null) {
                                  String type =  redisConfig.getType();
                                  String url = redisConfig.getUrl();
                                  String remark = redisConfig.getRemark();

                                  if (type.equals("add")) {
                                      logger.info("监听到新增配置，准备下载...");
                                      Thread.sleep(1000);
                                      logger.info("开始下载配置，下载路径为:[{}]", url);

                                      Thread.sleep(2000);
                                      logger.info("下载成功，已将其添加到项目中！");
                                  } else if (type.equals("update")) {
                                      logger.info("监听到更新配置，准备下载...");
                                      Thread.sleep(1000);
                                      logger.info("开始下载配置，下载路径为:[{}]", url);

                                      Thread.sleep(2000);
                                      logger.info("下载成功，准备替换配置...");
                                      logger.info("备份项目已有配置");
                                      logger.info("替换项目配置");
                                      Thread.sleep(2000);
                                      logger.info("配置成功！");
                                  } else if (type.equals("delete")) {
                                      logger.info("监听到需要删除配置");
                                      Thread.sleep(1000);
                                      logger.info("配置 删除成功");
                                  }
                              }
                          }
                      }
                  }
              }
            );

            latch.await();
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
