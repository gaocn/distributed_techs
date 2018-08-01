package govind.zkinaction.utils;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.CountDownLatch;

@Service
public class DistributedLock {
    private static final Logger logger = LoggerFactory.getLogger(DistributedLock.class);

    /**
     * 无法获取锁时，用于阻塞进程。
     */
    private CountDownLatch  latch = new CountDownLatch(1);

    /**
     * 用于标识锁空间
     */
    private final static String NAMESPACE = DistributedLock.class.getSimpleName();

    /**
     * 分布式锁的父路径，一般用业务名称表示
     */
    private final static String SERVICE_PATH = "/govind-lock";
    /**
     * 分布式锁名，一般为具体的业务操作
     */
    private final static String LOCK = "/pay";

    /**
     * ZooKeeper初始化参数
     */
    private CuratorFramework client =  null;
    final static String CONN_STR="10.233.87.54:9080";
    final static int SESSION_TIMEOUT = 2000;
    final static int CONN_TIMEOUT = 5000;

    @PostConstruct
    public void init() {
        logger.info("初始化分布式锁");
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(0, 10);
        client = CuratorFrameworkFactory.builder()
                .connectString(CONN_STR)
                .connectionTimeoutMs(CONN_TIMEOUT)
                .sessionTimeoutMs(SESSION_TIMEOUT)
                .retryPolicy(retryPolicy)
                .build();
        client.start();
        client.usingNamespace(NAMESPACE);

        try {
            String basePath = SERVICE_PATH;
            if (client.checkExists().forPath(basePath) == null) {
                client.create()
                        .withMode(CreateMode.PERSISTENT)
                        .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                        .forPath(basePath);
                logger.info("分布式锁父目录创建成功：[{}]", basePath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        addLockWatcher();
    }

    public Boolean requireLock() {
        String lockPah = SERVICE_PATH  + LOCK;
        logger.info("尝试获取锁....");

        while (true)  {
            if (client == null) {logger.info("无法连接ZK"); return false;}
            try {
                client.create().withMode(CreateMode.EPHEMERAL)
                        .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
                        .forPath(lockPah);

                logger.info("成功获取分布式锁：[{}]", lockPah);
                return true;
            } catch (Exception e) {
                //其他进程占有锁，当前进程阻塞
                logger.info("锁被其他进程占有，当前进程阻塞！");
                if (latch.getCount() <=  0) {
                    latch =  new CountDownLatch(1);
                }
                try {
                    latch.await();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    public Boolean releaseLock() {

        if (client == null)  {logger.info("无法连接ZK"); return false;}

        String lockPath =  SERVICE_PATH  +  LOCK;
        try {
            if (client.checkExists().forPath(lockPath) != null) {
                client.delete().forPath(lockPath);
                logger.info("成功释放锁！");
                return true;
            }
        } catch (KeeperException.NoNodeException e) {
            logger.info("锁不存在，已经被释放");
        }  catch (Exception e1) {
            e1.printStackTrace();
        }
        return false;
    }

    private void addLockWatcher() {
        if (client == null)  {logger.info("无法连接ZK"); return;}

        final PathChildrenCache lockWatcher = new PathChildrenCache(client, SERVICE_PATH, true);
        try {
            lockWatcher.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            lockWatcher.getListenable().addListener(
                    new PathChildrenCacheListener() {
                        @Override
                        public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                            switch (event.getType())  {
                                case CHILD_REMOVED:
                                    logger.info("监听到释放锁事件，通知其他线程竞争锁！");
                                    latch.countDown();
                                    break;
                                default:
                                    logger.info("监听到事件：[{}]", event.getType());
                                    break;
                            }
                        }
                    }
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
