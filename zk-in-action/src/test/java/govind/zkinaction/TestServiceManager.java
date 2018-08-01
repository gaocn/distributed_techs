package govind.zkinaction;


import govind.zkinaction.service_manager.ServiceManager;
import govind.zkinaction.service_manager.ServiceRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
public class TestServiceManager {


    @Test
    public void testService() {
        ServiceRegistry sr1 =  new ServiceRegistry("govind1.com");
        ServiceRegistry sr2 =  new ServiceRegistry("butter.govind1.com");
        ServiceRegistry sr3 =  new ServiceRegistry("news.govind1.com");
        ServiceRegistry sr4 =  new ServiceRegistry("story.govind1.com");

        ServiceManager sm = new ServiceManager();
        sm.start();

        sr1.start();
        sr2.start();
        sr3.start();
        sr4.start();


        try {
            Thread.sleep(1000);
            sr2.close();


            Thread.sleep(3000);
            sr4.close();


            Thread.sleep(2000);
            sr1.close();
            sr3.close();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
