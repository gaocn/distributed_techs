package zk;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class ZKUsage implements Watcher{
    private final static Logger logger  = LoggerFactory.getLogger(ZKUsage.class);
    private final static CountDownLatch latch = new CountDownLatch(1);
    final static String CONN_STR="10.233.87.54:9080";
    final static int SESSION_TIMEOUT = 2000;
    final static int CONN_TIMEOUT = 5000;

    private static ZooKeeper client  = null;

    public static void main(String[] args) {

        try {
            Watcher watcher =  new ZKUsage();
            client = new ZooKeeper(CONN_STR,  SESSION_TIMEOUT, watcher,false);

            logger.info("ZK Client 连接中......");
            logger.info("连接状态：[{}]", client.getState());
            //要睡眠一段时间，ZK连接需要一定时间，或者使用CounterDownLatch
//            Thread.sleep(5000);

            /**
             * 会话恢复
             */
            long sessionId = client.getSessionId();
            byte[] sessionPwd  = client.getSessionPasswd();
            logger.info("会话ID=[{}], 会话密码=[{}]", sessionId,  sessionPwd);

            //会话重连
//            ZooKeeper client2 = new ZooKeeper(CONN_STR, SESSION_TIMEOUT, watcher, sessionId, sessionPwd);
//            logger.info("ZK重连中......");
//            logger.info("重连状态：[{}]", client.getState());
//            Thread.sleep(2000);

//            logger.info("重连前的sessionId=[{}], 重连后的sessionId=[{}]", sessionId, client2.getSessionId());


            // world:anyone:cdrwa
            client.create("/acl", "acl".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            //scheme=auth、digest认证方式
            List<ACL> acls  =  new ArrayList<ACL>();
            acls.add(new ACL(ZooDefs.Perms.CREATE ,new Id("auth", "gao:gao")));

            String digestGao =  DigestAuthenticationProvider.generateDigest("gao:gao");
            logger.info("生成摘要认证密码：[{}]", digestGao);
            Id digestId = new Id("digeset", digestGao);
            acls.add(new ACL(ZooDefs.Perms.WRITE | ZooDefs.Perms.READ, digestId));

            client.create("/acl/acl_a", "acl_1".getBytes(), acls, CreateMode.PERSISTENT);

            //注册过的用户必须通过addAuthInfo才能操作节点
            client.addAuthInfo("digest", "gao:gao".getBytes());
            byte[] data  = client.getData("/acl/acl_a",null,null);
            logger.info("获取/acl/acl_a数据: [{}]", new String(data));

            //scheme=ip认证
            acls.add(new ACL(ZooDefs.Perms.CREATE, new Id("ip", "127.0.0.1")));
            client.create("/acl/acl_b", "acl_b".getBytes(), acls, CreateMode.PERSISTENT);


//            latch.await();

        }catch (KeeperException e){
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } finally {
            if (client != null)  {
                try {
                    client.close();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }

    }

    public void process(WatchedEvent event) {
        logger.info("接收到事件：[{}], state=[{}]", event.getType(), event.getState());
    }
}
