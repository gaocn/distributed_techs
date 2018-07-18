package curator;


import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.recipes.cache.*;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CuratorUsage {
    private static Logger logger  = LoggerFactory.getLogger(CuratorUsage.class);
    final static String CONN_STR="localhost:2181,localhost:2182,localhost:2183";
    final static int SESSION_TIMEOUT = 2000;
    final static int CONN_TIMEOUT = 5000;

    public static void main(String[] args) throws  Exception{
        ExponentialBackoffRetry backoffRetry = new ExponentialBackoffRetry(1000, 10);

//        CuratorFramework client = CuratorFrameworkFactory.newClient(
//                CONN_STR,
//                SESSION_TIMEOUT,
//                CONN_TIMEOUT,
//                backoffRetry
//        );

        /**
         * ACL有IP授权和用户名密码访问的模式
         */
        ACL  aclRoot = new ACL(ZooDefs.Perms.ALL,
                new Id("digest",
                        DigestAuthenticationProvider.generateDigest("root:root")));
        List<ACL> acls =  new ArrayList<ACL>();
        acls.add(aclRoot);

        CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(CONN_STR)
                .connectionTimeoutMs(CONN_TIMEOUT)
                .sessionTimeoutMs(SESSION_TIMEOUT)
                .retryPolicy(backoffRetry)
                .authorization("digest", "root:root".getBytes())
                .build();

        //连接状态监听
        client.getConnectionStateListenable().addListener(
                new ConnectionStateListener() {
                    public void stateChanged(CuratorFramework client, ConnectionState newState) {
                        logger.info("Connection Stated Changed: [{}]", newState);
                    }
                }
        );

        client.start();

        String result = null;

        result = client.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL)
                .withACL(acls)
                .forPath("/acls", "acl".getBytes());
        logger.info("create for path /acls result[{}]", result);

        Stat rootStat = new Stat();
        result = new String(client.getData().storingStatIn(rootStat).forPath("/"));
        logger.info("getData for path / result[{}]", result);
        logger.info("root path stat: {}", rootStat);

        Stat stat= client.setData().withVersion(rootStat.getVersion()).forPath("/", "".getBytes());
        logger.info("setData for path / result[{}]", stat);

        stat = client.checkExists().inBackground(
                new BackgroundCallback() {
                    public void processResult(CuratorFramework curatorFramework, CuratorEvent curatorEvent) throws Exception {

                        logger.info("event=[{}], result code=[{}]", curatorEvent.getType(), curatorEvent.getResultCode());

                        switch (curatorEvent.getType()) {
                            case CREATE:
                                logger.info("prdocess CREATE event");
                                break;
                            case DELETE:
                                logger.info("process DELETE event");
                                break;
                            case EXISTS:
                                logger.info("process EXISTS event");
                                break;
                            case GET_DATA:
                                logger.info("process GET_DATA event");
                                break;
                            case SET_DATA:
                                logger.info("process SET_DATA event");
                                break;
                            case CHILDREN:
                                logger.info("process CHILDREN event");
                                break;
                            case SYNC:
                                logger.info("process SYNC event");
                                break;
                            case GET_ACL:
                                break;
                            case SET_ACL:
                                logger.info("process SET_ACL event");
                                break;
                            case WATCHED:
                                logger.info("process WATCHED event");
                                break;
                            case CLOSING:
                                logger.info("process CLOSING event");
                                break;
                        }
                    }
                }
        ).forPath("/");
        logger.info("checkExists for path / result[{}]", stat);


        List<String> paths = client.getChildren().forPath("/");
        logger.info("paths:{}", paths);


        /**
         * NodeCache: 节点监听
         *
         * NodeCache不仅可以用于监听数据节点内容变更，也能监听指定节点是否存在。
         * 如果原本节点不存在，那么cache就会在节点创建后触发NodeCacheListener,
         * 但是如果该节点被删除，那么Curator就无法触发NodeCacheListener了。
         */
        final NodeCache nodeCache = new NodeCache(client, "/acls", false);
        // 立即从ZK中获取数据并缓存
        nodeCache.start(true);

        nodeCache.getListenable().addListener(
                new NodeCacheListener() {
                    public void nodeChanged() throws Exception {
                        logger.info("cache node data changed to [{}] ", new String(nodeCache.getCurrentData().getData()));
                    }
                }
        );
        client.setData().forPath("/acls", "test cache node listener".getBytes());
        // 使用原生监听器
//        client.getData().usingWatcher(new Watcher() {
//            public void process(WatchedEvent watchedEvent) {
//
//            }
//        });


        /**
         * PathChildrenCache：子节点监听
         *
         */
        result = client.create().withProtection().withMode(CreateMode.PERSISTENT).forPath("/nodes", "nodes".getBytes());
        logger.info("create for path / result[{}]", result);

        Thread.sleep(2000);

        result = client.create().withProtection().withMode(CreateMode.EPHEMERAL).forPath("/nodes/node1", "node1".getBytes());
        logger.info("create for path / result[{}]", result);

        final PathChildrenCache pathChildrenCache = new PathChildrenCache(client, "/nodes/node1", true);
        pathChildrenCache.getListenable().addListener(
                new PathChildrenCacheListener() {

                    /**
                     * 1. 注册子节点触发 CHILD_ADDED
                     * 2. 更新子节点值触发 CHILD_UPDATED
                     * 3. 删除子节点触发 CHILD_REMOVED
                     * 4. ZK挂掉触发CONNECTION_SUSPENDED，一段时间后触发CONNECTION_LOST
                     * 5. ZK重启触发CONNECTION_RECONNECTED
                     */
                    public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                        switch (event.getType()) {
                            case CHILD_ADDED:
                                logger.info("process CHILD_ADDED event");
                                break;
                            case CHILD_UPDATED:
                                logger.info("process CHILD_UPDATED event");
                                break;
                            case CHILD_REMOVED:
                                logger.info("process CHILD_REMOVED event");
                                break;
                            case CONNECTION_SUSPENDED:
                                logger.info("process CONNECTION_SUSPENDED event");
                                break;
                            case CONNECTION_RECONNECTED:
                                logger.info("process CONNECTION_RECONNECTED event");
                                break;
                            case CONNECTION_LOST:
                                logger.info("process CONNECTION_LOST event");
                                break;
                            case INITIALIZED:
                                logger.info("process INITIALIZED event");
                                break;
                        }
                    }
                }
        );

        /**
         * 1. POST_INITIALIZED_EVENT：异步初始化cache，初始化完成后会出发事件INITIALIZED；
         * 2. NORMAL：异步初始化cache，在监听器启动的时候会枚举当前路径所有子节点，触发CHILD_ADDED类型的事件；
         * 3. BUILD_INITIAL_CACHE：同步初始化客户端的cache，即创建cache后，就从服务器端拉入对应的数据；
         */
        pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);


        client.close();
    }

}