## 第1课 并发编程与ActiveMQ消息服务

> 线程安全：当多个线程访问某一个类(对象或方法)时，这个类始终都能表现出正确的行为，那么这个类(对象或方法)就是线程安全的。

### 1. **synchronized---共享资源**

可以在任意对象及方法加锁，而加锁的这段代码称为互斥区或临界区。一个线程想要执行sychronized修饰的代码块会首先尝试获取这把锁，若是拿到锁就会执行synchronized中的代码；若没有拿到则线程会不断的尝试获取这把锁，因此会存在多个线层同时竞争这把锁。

**多线程多个锁**：每个线程都可以拿到自己的锁，然后去执行临界区中的代码。**一个对象有一把锁**，下面代码中m1、m2对象各自获取有一把对象锁，因此在执行时会各自回去锁然后执行临界区中的代码因此打印结果为：
$$
tag \ a, set \ num \ over! \ \   tag \ b, set\  num \ over! \ \  tag  \ a, num=100! \ \  tag \ b, num = 200!
$$

```java
public class MultiThread {
	private int num = 0;
	/** static */
	public synchronized void printNum(String tag){
		try {
			if(tag.equals("a")){
				num = 100;
				System.out.println("tag a, set num over!");
				Thread.sleep(1000);
			} else {
				num = 200;
				System.out.println("tag b, set num over!");
			}
			System.out.println("tag " + tag + ", num = " + num);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	//注意观察run方法输出顺序
	public static void main(String[] args) {
		//俩个不同的对象
		final MultiThread m1 = new MultiThread();
		final MultiThread m2 = new MultiThread();
		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				m1.printNum("a");
			}
		});
		Thread t2 = new Thread(new Runnable() {
			@Override 
			public void run() {
				m2.printNum("b");
			}
		});		
		t1.start();
		t2.start();
	}
```

关键字synchronized取得的锁都是对象锁，而不是把一段代码（方法）当做锁，所以代码中哪个线程先执行synchronized关键字的方法，哪个线程就持有该方法所属对象的锁（Lock），在静态方法上加synchronized关键字，表示锁定.class类，类一级别的锁（独占.class类）。

#### 1.1 **对象锁的同步与异步问题**

下面代码中method1和method2各自打印调用该方法的线程名称，不同的是method1上加了synchronized修饰。当两个线程分别同时访问method1和method2时打印的结果会不同：

1.  当同时访问method1时，先打印t1，然后打印t2。**t1线程先持有object对象的Lock锁，t2线程如果在这个时候调用对象中的同步（synchronized）方法则需等待，也就是同步**
2. 当两个线程同时分别访问method1，method2时，同时打印t1,t2。**t1线程先持有object对象的Lock锁，t2线程可以以异步的方式调用对象中的非synchronized修饰的方法**

```java
public class MyObject {
	public synchronized void method1(){
		try {
			System.out.println(Thread.currentThread().getName());
			Thread.sleep(4000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	/** synchronized */
	public void method2(){
			System.out.println(Thread.currentThread().getName());
	}
	public static void main(String[] args) {
		final MyObject mo = new MyObject();

		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				mo.method1();
			}
		},"t2");
		Thread t2 = new Thread(new Runnable() {
			@Override
			public void run() {
				mo.method2();
			}
		},"t2");
		t1.start();
		t2.start();
	}
}
```

#### 1.2 脏读问题

对于对象的同步方法和异步方法，在设计程序时一定要考虑问题的整体，不然就会出现数据不一致的错误，很经典的错误就是脏读(Dirty Read)。

下面代码中set方法使用了synchronized修饰，而get方法没有用synchronized修饰，执行下面代码得到的结果是：getValue方法得到：username = name , password = 123；setValue最终结果：username =z3 , password = 456。实际上我们需要get的结果是后者，业务整体需要使用完整的synchronized，即对get和set方法同时加锁synchronized同步关键字，保证业务(service)的原子性，否则就会出现业务错误。

```java
public class DirtyRead {
	private String username = "name";
	private String password = "123";
	public synchronized void setValue(String username, String password){
		this.username = username;
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		this.password = password;
		System.out.println("setValue最终结果：username = " 
                           + username + " , password = " + password);
	}
	public void getValue(){
		System.out.println("getValue方法得到：username = " 
                           + this.username + " , password = " + this.password);
	}
	public static void main(String[] args) throws Exception{
		final DirtyRead dr = new DirtyRead();
		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				dr.setValue("z3", "456");		
			}
		});
		t1.start();
		Thread.sleep(1000);
		dr.getValue();
	}
}
```

**实例：** 关系型数据中的特性ACID并各自举出一个例子，假设数据库中的一个表有一千万条，一个用户A在9:00时要通过select查询一条数据，而这条数据在第9千万条且值为100，不考虑索引优化情况，假设这个请求要执行10分钟才能得到结果；而另一个用户B在9:05时对这一条数据进行update操作将其设置为200并提交了修改，那么当A执行完成select查询结果后，A得到的数据是什么？`结果一定是100`，数据库的ACID特性保证数据库的一致性读，在用户A发送请求的那一刻看到的数据一定是那个时刻的所有数据。

#### 1.3 Synchronized锁重入

关键字synchronized拥有锁重入功能，类似JDK底层API ReentrantLock，即当一个线程得到一个对象的锁后，再次请求此对象时是可以再次得到该对象的锁。

```java
public class ReentrantLock {
	public synchronized void method1(){
		System.out.println("method1..");
		method2();
	}
	public synchronized void method2(){
		System.out.println("method2..");
		method3();
	}
	public synchronized void method3(){
		System.out.println("method3..");
	}
	public static void main(String[] args) {
		final ReentrantLock sd = new ReentrantLock();
		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				sd.method1();
			}
		});
		t1.start();
	}
}
```

在父子继承关系存在时，使用synchronized也能够实现锁的重入

```java
public class SyncDubbo2 {
	static class Main {
		public int i = 10;
		public synchronized void operationSup(){
			try {
				i--;
				System.out.println("Main print i = " + i);
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	static class Sub extends Main {
		public synchronized void operationSub(){
			try {
				while(i > 0) {
					i--;
					System.out.println("Sub print i = " + i);
					Thread.sleep(100);		
					this.operationSup();
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	public static void main(String[] args) {
		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				Sub sub = new Sub();
				sub.operationSub();
			}
		});
		t1.start();
	}
}
```

#### 1.4 **出现异常，锁自动释放**

对于web应用程序，异常释放锁的情况，如果不及时处理，很可能对你的应用程序业务逻辑产生严重的错误，比如你现在执行一个队列任务，很多对象都去在等待第一个对象正确执行完毕再去释放，但是第一个对象由于异常的出现，导致业务逻辑没有正常执行完毕，就释放了锁，那么可想而知后续的对象执行的都是错误的逻辑。

在operation获得锁后执行业务逻辑时出现异常后，假设operation业务是一个整体，不应该因为异常就释放锁业务不继续执行下去了，而是应该应该捕获异常打印日志后继续运行。若要退出线程执行过程可以抛出InterruptedException或RuntimeException异常。

```java
public class SyncException {
	private int i = 0;
	public synchronized void operation(){
		while(true){
			try {
				i++;
				Thread.sleep(100);
				System.out.println(Thread.currentThread().getName() + " , i = " + i);
				if(i == 20){
					//终止线程运行
					throw new RuntimeException();
				}
			} catch (InterruptedException e) {
                //打印日志后继续运行 
                e.printStackTrace();
                continue;
			}
		}
	}
	public static void main(String[] args) {
		final SyncException se = new SyncException();
		Thread t1 = new Thread(new Runnable() {
			@Override
			public void run() {
				se.operation();
			}
		},"t1");
		t1.start();
	}
}
```



#### 1.5 使用synchronized需要注意的问题

1. 使用synchronized声明的方法在某些情况下是有弊端的，比如A线程调用同步的方法执行一个很长时间的任务，那么B线程就必须等待比较长的时间才能执行，这样的情况下可以使用synchronized代码块优化代码执行时间，减少锁的粒度。synchronized可以是使用任意的Object进行加锁，用法灵活。

   ```java
   public class ObjectLock {
   	public void method1(){
   		synchronized (this) {	//对象锁
   			try {
   				System.out.println("do method1..");
   				Thread.sleep(2000);
   			} catch (InterruptedException e) {
   				e.printStackTrace();
   			}
   		}
   	}
   	public void method2(){		//类锁
   		synchronized (ObjectLock.class) {
   			try {
   				System.out.println("do method2..");
   				Thread.sleep(2000);
   			} catch (InterruptedException e) {
   				e.printStackTrace();
   			}
   		}
   	}
   	private Object lock = new Object();
   	public void method3(){		//任何对象锁
   		synchronized (lock) {
   			try {
   				System.out.println("do method3..");
   				Thread.sleep(2000);
   			} catch (InterruptedException e) {
   				e.printStackTrace();
   			}
   		}
   	}
   	public static void main(String[] args) {
   		final ObjectLock objLock = new ObjectLock();
   		Thread t1 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				objLock.method1();
   			}
   		});
   		Thread t2 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				objLock.method2();
   			}
   		});
   		Thread t3 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				objLock.method3();
   			}
   		});
   		t1.start();
   		t2.start();
   		t3.start();
   	}
   }
   ```

   

2. 不要使用String的常量加锁，会出现死循环问题，synchronized代码块对字符串的锁，注意String常量池的缓存功能。

   ```java
   public class StringLock {
   	public void method() {
   		synchronized ("字符串常量") { //常量只有一个引用，用new String("字符串常量")替换
   			try {
   				while(true){
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() + "开始");
   					Thread.sleep(1000);		
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() + "结束");
   				}
   			} catch (InterruptedException e) {
   				e.printStackTrace();
   			}
   		}
   	}
   	public static void main(String[] args) {
   		final StringLock stringLock = new StringLock();
   		Thread t1 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				stringLock.method();
   			}
   		},"t1");
   		Thread t2 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				stringLock.method();
   			}
   		},"t2");
   		t1.start();
   		t2.start();
   	}
   }
   ```

   

3. 锁对象的改变问题，当使用一个对象进行加锁时，要注意对象本身发生改变的时候，那么持有的锁就不同。如果对象本身不发生改变，那么依然是同步的， 同一对象属性的修改不会影响锁的情况。下面例子中，但锁对象改变后，其他进程就能够进入临界区。

   ```java
   public class ChangeLock {
   	private String lock = "lock";
   	private void method(){
   		synchronized (lock) {
   			try {
   				System.out.println("当前线程 : "  
                                      + Thread.currentThread().getName() + "开始");
   				lock = "change lock";
   				Thread.sleep(2000);
   				System.out.println("当前线程 : "  
                                      + Thread.currentThread().getName() + "结束");
   			} catch (InterruptedException e) {
   				e.printStackTrace();
   			}
   		}
   	}
   	public static void main(String[] args) {
   		final ChangeLock changeLock = new ChangeLock();
   		Thread t1 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				changeLock.method();
   			}
   		},"t1");
   		Thread t2 = new Thread(new Runnable() {
   			@Override
   			public void run() {
   				changeLock.method();
   			}
   		},"t2");
   		t1.start();
   		try {
   			Thread.sleep(100);
   		} catch (InterruptedException e) {
   			e.printStackTrace();
   		}
   		t2.start();
   	}
   }
   ```

   

4. 死锁问题，在设计程序时就应该避免双方相互持有对方的锁的情况。

   ```java
   public class DeadLock implements Runnable{
   	private String tag;
   	private static Object lock1 = new Object();
   	private static Object lock2 = new Object();
   	@Override
   	public void run() {
   		if(tag.equals("a")){
   			synchronized (lock1) {
   				try {
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() 
                                          + " 进入lock1执行");
   					Thread.sleep(2000);
   				} catch (InterruptedException e) {
   					e.printStackTrace();
   				}
   				synchronized (lock2) {
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() 
                                          + " 进入lock2执行");
   				}
   			}
   		}
   		if(tag.equals("b")){
   			synchronized (lock2) {
   				try {
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() 
                                          + " 进入lock2执行");
   					Thread.sleep(2000);
   				} catch (InterruptedException e) {
   					e.printStackTrace();
   				}
   				synchronized (lock1) {
   					System.out.println("当前线程 : "  
                                          + Thread.currentThread().getName() 
                                          + " 进入lock1执行");
   				}
   			}
   		}
   	}
   	public static void main(String[] args) {
   		DeadLock d1 = new DeadLock();
   		d1.setTag("a");
   		DeadLock d2 = new DeadLock();
   		d2.setTag("b");
   		Thread t1 = new Thread(d1, "t1");
   		Thread t2 = new Thread(d2, "t2");
   		t1.start();
   		try {
   			Thread.sleep(500);
   		} catch (InterruptedException e) {
   			e.printStackTrace();
   		}
   		t2.start();
   	}
   }
   ```

### 2. Volatile---可见性

#### 2.1 volatile的含义及使用

Volatile关键字的主要作用是**使变量在多线程间可见**。在Java中每个线程都会有一块工作内存区，其中存放着所有线程共享的主内存中的变量值的拷贝。当线程执行时，他在自己的工作内存区中操作这些变量。为了存取一个共享的变量，一个线程通常先获取锁定并清除它的内存工作区，把这些共享变量从所有线程的共享内存区中正确的装入大它自己的工作内存区中，当线程解锁时，保证该工作内存区中变量的值写回到共享内存中。

- 一个线程可以执行的操作有use、assign、load、store、lock、unlock；
- 主内存可以执行的操作有read、write、lock、unlock，其中每个操作都是原子的；

volatile的作用就是强制线程到主内存(共享内存)里去读取变量，而不去线程工作区里读取，从而实现了多个线程间的变量可见，也是满足线程安全的可见性。

```java
public class RunThread extends Thread{
    //改变量在多个线程间可见，若为非volatile则不同RunThread对象所看到的isRunning是不同的
	private volatile boolean isRunning = true;
	private void setRunning(boolean isRunning){
		this.isRunning = isRunning;
	}
	public void run(){
		System.out.println("进入run方法..");
		int i = 0;
		while(isRunning == true){
			//..
		}
		System.out.println("线程停止");
	}
	public static void main(String[] args) throws InterruptedException {
		RunThread rt = new RunThread();
       //创建线程并分配专用内存空间(存放引用的主内存中的变量值得拷贝)，并把主内存中的isRunning的拷贝放
       //在专用内存中，以后使用时直接load，而使用volatile后，线程每一次访问都会从主内存中read出变量的值
		rt.start();
		Thread.sleep(1000);
        //设置的是主内存中的isRunning变量的值
		rt.setRunning(false);
		System.out.println("isRunning的值已经被设置了false");
	}
}
```

#### 2.2 原子性与可见性的区别

volatile关键字修饰的变量虽然拥有多个线程之间的可见性，但是却不具备同步性(原子性)，可以算是一个轻量级的synchronized，性能要比synchronized强很多，不会造成阻塞。在很多开源的架构里，比如Netty的底层代码就大量使用volatile，可见Netty性能很好。需要注意的是：一般volatile用于多线程可见的变量操作，并不能代替synchronized的同步功能。**volatile只具有可见性不具备原子性**，要实现原子性建议使用atomic包中的对象，需要注意的是atomic类只保证本身方法的原子性，并不保证多次操作的原子性。

下面例子中，若对volatile变量count进行自增操作，由于不是原子性存在并行操作，则会导致最后得到的count的结果为一定小于等于10000，而采用AtomicInteger则能保证原子性结果恒定为10000。

```java
import java.util.concurrent.atomic.AtomicInteger;
// volatile关键字不具备synchronized关键字的原子性（同步）
public class VolatileNoAtomic extends Thread{
	private static volatile int count;
	//private static AtomicInteger count = new AtomicInteger(0);
	private static void addCount(){
		for (int i = 0; i < 1000; i++) {
			count++ ;
			//count.incrementAndGet();
		}
		System.out.println(count);
	}
	public void run(){
		addCount();
	}
    
    //多个addAndGet在一个方法内是非原子性的，需要加synchronized进行修饰，保证4个addAndGet整体原子性
	public synchronized int multiAdd(){
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			count.addAndGet(1);
			count.addAndGet(2);
			count.addAndGet(3);
			count.addAndGet(4); //+10
			return count.get();
	}
    
	public static void main(String[] args) {
		VolatileNoAtomic[] arr = new VolatileNoAtomic[100];
		for (int i = 0; i < 10; i++) {
			arr[i] = new VolatileNoAtomic();
		}
		for (int i = 0; i < 10; i++) {
			arr[i].start();
		}
	}
}
```















