package zk;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ZKUsage implements Watcher{
    private final static Logger logger  = LoggerFactory.getLogger(ZKUsage.class);
    final static String CONN_STR="10.233.87.241:9080,10.233.87.54:9080";
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
            Thread.sleep(5000);

            /**
             * 会话恢复
             */
            long sessionId = client.getSessionId();
            byte[] sessionPwd  = client.getSessionPasswd();
            logger.info("会话ID=[{}], 会话密码=[{}]", sessionId,  sessionPwd);

            //会话重连
            ZooKeeper client2 = new ZooKeeper(CONN_STR, SESSION_TIMEOUT, watcher, sessionId, sessionPwd);
            logger.info("ZK重连中......");
            logger.info("重连状态：[{}]", client.getState());
            Thread.sleep(2000);

            logger.info("重连前的sessionId=[{}], 重连后的sessionId=[{}]", sessionId, client2.getSessionId());


//            while (true){ }

        } catch (IOException e) {
            e.printStackTrace();
            if (client != null)  {
                try {
                    client.close();
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


    }

    public void process(WatchedEvent event) {
        logger.info("接收到事件：[{}], state=[{}]", event.getType(), event.getState());
    }
}
