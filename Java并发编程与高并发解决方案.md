# Java并发编程与高并发解决方案

**并发编程知识体系**：线程安全、线程封闭、线程调度、同步容器、并发容器、AQS、J.U.C。

**高并发解决思路与手段**：扩容、缓存、队列、拆分、服务降级与熔断、数据库切库、分库分表。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\1.jpg)

## 1. 并发与并发的线程安全处理

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\2.jpg)

**并发**：同时拥有两个或多个线程，如果程序在单核处理器上运行，多个线程将交替地换人换出内存，这些线程是同时“存在”的，每个线程处于执行过程中的某个状态，如果运行在多核处理器上，此时程序中的每个线程都将分配到一个处理核上，因此可以同时运行。

**高并发（High Concurrency）**：是互联网分布式系统架构设计中必须考虑的因素之一，它通常是指通过设计保证系统能够同时并行处理很多请求。

并发指多个线程操作相同资源，此时讨论的点更多是**保证线程安全及合理使用资源**。而高并发指系统集中收到大量请求，会导致系统在这段时间内执行大量操作，如数据库、资源请求等，若高并发处理不好不仅会降低用户体验，请求时间变长，OOM异常甚至导致系统宕机停止工作；若要系统能够适应高并发的状态，需要从多个方面进行系统优化包括硬件、网络、系统架构、开发语言的选取、数据结构的运用、算法优化、数据库优化等，这时关注的是如何**提高程序的性能**，更多是对高并发场景提供解决方案、思路和手段。

### 1.1 CPU多级缓存与MESI协议

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\CPU多级缓存.PNG)

为什么需要缓存？CPU频率太快，快到主存跟不上，这样在处理的时钟周期内，CPU常常需要等待主存，浪费资源。缓存的出现是为了缓解CPU和主存之间速度不匹配问题。缓存容量有效，缓存的意义主要有两点：

1. 时间局部性，如果某个数据被访问，那么在不久的将来它很有可能被再次访问；
2. 空间局部性，如果某个数据被访问，那么与它相邻的数据很快也可能被访问；

**Cache一致性协议之MESI**

单核Cache中每个Cache line有2个标志：dirty和valid标志，它们很好的描述了Cache和Memory(内存)之间的数据关系(数据是否有效，数据是否被修改)，而在多核处理器中，多个核会共享一些数据，MESI协议就包含了描述共享的状态。   在MESI协议中，每个Cache line有4个状态，可用2个bit表示，它们分别是：

| 状态      | 说明                                                         |
| --------- | ------------------------------------------------------------ |
| Modify    | 这行数据有效，数据被修改了，和内存中的数据不一致，数据只存在于本Cache中 |
| Exclusive | 这行数据有效，数据和内存中的数据一致，数据只存在于本Cache中  |
| Share     | 这行数据有效，数据和内存中的数据一致，数据存在于很多Cache中。 |
| Invalid   | 这行数据无效                                                 |

**MESI用于保证多个CPU缓存之间缓存共享数据的一致性**，定义了缓存行的的四种状态，而CPU对缓存的四种操作可能会产生不一致的状态，因此缓存控制器监听到本地操作和远程操作时，需要对cache line做一定的修改，保证数据在多个缓存间的一致性。  

在MESI协议中，每个Cache的Cache控制器不仅知道自己的读写操作，而且也监听(snoop)其它Cache的读写操作。每个Cache line所处的状态根据本核和其它核的读写操作在4个状态间进行迁移。 

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\MESI协议状态迁移图.gif)

在上图中，Local Read表示本内核读本Cache中的值，Local Write表示本内核写本Cache中的值，Remote Read表示其它内核读其它Cache中的值，Remote Write表示其它内核写其它Cache中的值，箭头表示本Cache line状态的迁移，环形箭头表示状态不变。 

当内核需要访问的数据不在本Cache中，而其它Cache有这份数据的备份时，本Cache既可以从内存中导入数据，也可以从其它Cache中导入数据，不同的处理器会有不同的选择。MESI协议为了使自己更加通用，没有定义这些细节，只定义了状态之间的迁移，下面的描述假设本Cache从内存中导入数据。 

- I-无效状态

  | 事件           | 行为                                                         | 下一状态 |
  | -------------- | ------------------------------------------------------------ | -------- |
  | Local Read     | 如果其它Cache没有这份数据，本Cache从内存中取数据，Cache line状态变成E；  如果其它Cache有这份数据，且状态为M，则将数据更新到内存，本Cache再从内存中取数据，2个Cache 的Cache line状态都变成S；  如果其它Cache有这份数据，且状态为S或者E，本Cache从内存中取数据，这些Cache 的Cache line状态都变成S | E/S      |
  | Local Write| 从内存中取数据，在Cache中修改，状态变成M；  如果其它Cache有这份数据，且状态为M，则要先将数据更新到内存；  如果其它Cache有这份数据，则其它Cache的Cache line状态变成I | M        |
  | Remote Read| 既然是Invalid，别的核的操作与它无关                          | I        |
  | Remote Write | 既然是Invalid，别的核的操作与它无关                          | I        |

- E-独享状态

  | 事件         | 行为                          | 下一状态 |
  | ------------ | ----------------------------- | -------- |
  | Local Read   | 从Cache中取数据，状态不变     | E        |
  | Local Write  | 修改Cache中的数据，状态变成M  | M        |
  | Remote Read  | 数据和其它核共用，状态变成了S | S        |
  | Remote Write | 数据和其它核共用，状态变成了S | I        |

- S-共享状态

  | 事件         | 行为                                                         | 下一状态 |
  | ------------ | ------------------------------------------------------------ | -------- |
  | Local Read   | 从Cache中取数据，状态不变                                    | S        |
  | Local Write  | 修改Cache中的数据，状态变成M； 其它核共享的Cache line状态变成I | M        |
  | Remote Read  | 状态不变                                                     | S        |
  | Remote Write | 状态不变                                                     | I        |

- M-已修改状态

  | 事件         | 行为                                                         | 下一状态 |
  | ------------ | ------------------------------------------------------------ | -------- |
  | Local Read   | 从Cache中取数据，状态不变                                    | M        |
  | Local Write  | 修改Cache中的数据，状态不变                                  | M        |
  | Remote Read  | 这行数据被写到内存中，使其它核能使用到最新的数据，状态变成S  | S        |
  | Remote Write | 这行数据被写到内存中，使其它核能使用到最新的数据，由于其它核会修改这行数据， | I        |

### 1.2 **乱序执行优化**

通过改变原有执行顺序而减少时间的执行过程我们被称之为**乱序执行*,也称为*重排* 。随着处理器流水线技术和多核技术的发展,目前的高级处理器通过提高内部逻辑元件的利用率来提高运行速度，通常会采用乱序执行技术。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\乱序执行.PNG)

可以说乱序执行技术是处理器为提高运算速度而做出违背代码原有顺序的优化。在单核时代，处理器保证做出的优化不会导致执行结果远离预期目标，但在多核环境下却并非如此.。

首先多核时代，同时会有多个核执行指令，每个核的指令都可能被乱序；另外，处理器还引入了L1、L2等缓存机制，每个核都有自己的缓存，这就导致逻辑次序上后写入内存的数据未必真的最后写入。最终带来了这么一个问题：如果我们不做任何防护措施，处理器最终得出的结果和我们逻辑得出的结果大不相同。比如我们在一个核上执行数据的写入操作，并在最后写一个标记用来表示之前的数据已经准备好，然后从另一个核上通过判断这个标志来判定所需要的数据已经就绪，这种做法存在风险：标记位先被写入，但是之前的数据操作却并未完成(可能是未计算完成，也可能是数据没有从处理器缓存刷新到主存当中)，最终导致另一个核中使用了错误的数据。

所有可能发生乱序执行的情况如下：

- 现代处理器采用指令并行技术，在不存在数据依赖性的前提下，处理器可以改变语句对应的机器指令的执行顺序来提高处理器执行速度。
- 现代处理器采用内部缓存技术，导致数据的变化不能及时反映在主存所带来的乱序。
- 现代编译器为优化而重新安排语句的执行顺序。

### 1.3 JAVA内存模型(JMM)

Java内存模型的主要目标是定义程序中各个变量的访问规则，即在虚拟机中将变量存储到内存和从内存中取出变量这样底层细节。Java内存模型中规定了**所有的变量都存储在主内存中，每条线程还有自己的工作内存**（可以与前面将的处理器的高速缓存类比），线程的工作内存中保存了该线程使用到的变量到主内存副本拷贝，**线程对变量的所有操作（读取、赋值）都必须在工作内存中进行，而不能直接读写主内存中的变量 **。

> JMM规范规定一个线程如何、何时看到由其他线程修改过后的共享变量的值，及在必须时如何同步访问共享变量。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\5.jpg)

堆是运行时数据区，由垃圾回收管理，优势：动态分配内存大小，生存期不必实现告诉编译器，因为是运行时动态分配内存，垃圾收集器会自动回收不在使用的内存。缺点：由于需要在运行时动态分配内存，存取速度慢一些。栈的存取速度比堆快，仅次于计算机中的寄存器，栈中的数据可以共享，缺点是栈中数据大小与生存期必须时确定的，缺乏灵活性，栈中一般存放基本类型变量。JAVA要求调用栈和本地变量存放在线程栈上，对象存放在堆上。对象的方法和方法中的局部变量存放在线程栈上；一个对象的成员变量可能会随着对象自身存放在堆上，不管成员对象是原始类型还是引用类型；静态成员变量跟随定义一起存放在堆上；而存放在对上的对象可以被所持有对该对象引用的线程访问。当一个线程可以访问一个对象，那么就能访问其成员变量，若两个线程同时调用同一个对象的同一个方法访问对象的成员变量时，这两个线程都拥有**对成员变量的私有拷贝**。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\8.jpg)

**JVM同步的八种操作**

- lock(锁定)，作用于主内存的变量，把一个变量标识为一条线程独占状态；
- unlock(解锁)，作用于主内存的变量，把一个处于锁定状态的变量释放出来，释放后的变量才可以被其他线程锁定；
- read(读取)，作用于主内存的变量，把一个变量值从主内存传输到线程的工作内存中，以便随后的load动作使用；
- load(载入)，作用于工作内存的变量，它把rea操作从主内存中得到的变量值放入工作内存的变量副本中；
- use(使用)，作用于工作内存的变量，把工作内存中的一个变量值传递给执行引擎；
- assign(赋值)，作用于工作内存的变量，它把一个从执行引擎接收到的值赋值给工作内存的变量；
- store(存储)，作用于工作内存的变量，把工作内存中的一个变量的值传送到主内存中，以便随后的write操作；
- write(写入)，作用于工作内存的变量，它把store操作从工作内存中一个变量的值传送到主内存的变量中；

**同步规则**

1. 如果要把一个变量从主内存中复制到工作内存，就需要按顺序执行read和load操作，如果把变量从工作内存中同步回主内存中，就要顺序执行store和write操作。但Java内存模型只要求上述操作必须按顺序进行，而没有保证必须是连续执行(中间可以执行其他指令)；
2. 不允许read和load、store和write操作之一单独出现；
3. 不允许一个线程丢弃它的最近assign的操作，即变量在工作内存中改变了之后必须同步到主内存中；
4. 不允许一个线程无原因地(没有发生过任何assign)把数据从工作内存同步到主内存；
5. 一个新的变量只能在主内存中诞生，不允许在工作内存中直接使用一个未被初始化(load或assign)的变量，即对一个变量实施use和store操作之前，必须先执行过来 assign和load操作；
6. 一个变量在同一时刻只允许一条线程对其进行lock操作，但lock操作可以被同一条线程重复执行多次，多次执行lock后，只有执相同次数的 unlock操作，变量才会被解锁。lock和unlock必须成对出现；
7. 如果对一个变量执行lock操作，将会清空工作内存中此变量的值，在执行引擎使用这个变量前需要重新执行load或 assign操作初始化变量的值；
8. 如果一个变量事先没有被lock操作锁定，则不允许对它执行unlock操作，也不允许去unlock一个被其他线程锁定的变量；
9. 对一个变量执行unlock操作之前，必须先把此变量同步到主内存中(执行store和write操作)

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\9.jpg)

### 1.4 并发的优势与风险

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\10.jpg)

### 1.5 并发模拟工具

- Postman：http请求模拟工具

- Apache Bench（AB）：Apache附带的工具，测试网站性能

- JMeter：Apache组织开发的压力测试工具，通过创建线程组实现并发测试

  具体线程属性说明如下：

  - 线程数：虚拟用户数，标识模拟多少个用户访问服务。
  - Ramp-Up Period：虚拟用户增长时长，例如：测试一个考勤系统，实际登录时并不是大家同时登录，而是从某个时刻开始用户陆续开始登录，直到某一时刻得到峰值。一般评估出登录频率最高的时间长度，例如：8:55~9:00登录评率最多，这里应设置为:5*60=300秒。
  - 循环次数：一个虚拟用户循环进行多少次测试。

  ![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\JMeter线程组属性说明.JPG)

- 代码：Semaphore、CountDownLatch等类实现测试

## 2. 线程安全性

定义：当多个线程访问某个类时，不管运行时环境采用何种调度方式或者这些进程将如何交替执行，并且在主调代码中不需要任何额外的同步或协同，这个类都能表现出正确的行为,那么就称这个类是线程安全的。线程安全需要满足以下特性：

- **原子性**，提供了互斥访问,同一时刻只能有一个线程来对它进行操作；
- **可见性**，一个线程对主内存的修改可以及时的被其他线程观察到；
- **有序性**，一个线程观察其他线程中的指令执行顺序,由于指令重排序的存在,该观察结果一般杂乱无序；

### 2.1 **原子性-Atomic包**

以并发多线程AtomicIntefer计数器为例，AtomicXXX包中类实现核心是通过CAS完成，在Atomic包中，使用一个类**Unsafe**.getAndAddInt完成自增操作，其主要实现如下所示

```java
//如执行：2+1，则var2为当前值，var4为增量，var1为Atomic对象
public final int getAndAddInt(Object var1, long var2, int var4) {
    int var5;
    do {
        //通过底层方法获取当前值(主内存的值)，var2为工作内存中的值
        var5 = this.getIntVolatile(var1, var2); 
        //compareAndSwapInt目标是：若var2与var5一致，则更新var1的值为var5+var4
    } while(!this.compareAndSwapInt(var1, var2, var5, var5 + var4));
    return var5;
}
//native标识java底层方法，不是通过java实现的
public native int getIntVolatile(Object var1, long var2);
public final native boolean compareAndSwapInt(Object var1, long var2, int var4, int var5);
```

[atomic包中的**AtomicLong**类和**LongAdder**类实现功能一样，增加LongAdder的原因是什么？](https://github.com/aCoder2013/blog/issues/22)CAS (compare-and-swap)本质上是由现代CPU在硬件级实现的原子指令，允许进行无阻塞，多线程的数据操作同时兼顾了安全性以及效率。大部分情况下，CAS都能够提供不错的性能，但是在高竞争的情况下开销可能会成倍增长。由上述代码可以看出AtomicXXX类实现CAS时是通过while循环完成，当修改失败频率过高时，while循环消耗资源就会增加，并且Long类型写入是分两次写入内存中，因此无谓的消耗太多。

**java.util.concurrency.atomic.LongAdder**是Java8新增的一个类，提供了原子累计值的方法。根据文档的描述其性能要优于AtomicLong。首先它有一个基础的值base，在发生竞争的情况下，会有一个Cell数组用于将不同线程的操作离散到不同的节点上去(会根据需要扩容，最大为CPU核数)，`sum()`会将所有Cell数组中的value和base累加作为返回值。核心的思想就是将AtomicLong一个value的更新压力分散到多个value中去，从而降级更新热点，在低并发的时候通过对base的直接更新可以很好的保障和AtomicLong的性能基本保持一致，而在高并发的时候通过分散提高了性能。 **缺点**是LongAdder在统计的时候如果有并发更新，可能导致统计的数据有误差。 

> 在低竞争的情况下AtomicLong表现优于LongAdder，但是在高并发竞争的情况下LongAdder更好。

**AtomicReference & AtomicReferenceFieldUpdater**

AtomicReference用法与AtomicXXX对应类使用一样，例子如下所示：

```java
private static AtomicReference<Integer> count = new AtomicReference<>(0);
public static void main(String[] args) {
    count.compareAndSet(0, 2); // 设置为2
    count.compareAndSet(0, 1); // 设置失败
    count.compareAndSet(1, 3); // 设置失败
    count.compareAndSet(2, 4); // 设置为2
    count.compareAndSet(3, 5); // 设置失败
    log.info("count: {}", count);
}
```

```java
private static AtomicIntegerFieldUpdater<TestAtomicFieldUpdater> updater =
    AtomicIntegerFieldUpdater.newUpdater(TestAtomicFieldUpdater.class, "count");
//FieldUpdater使用时，对应的变量需要使用volatile修饰且非static变量
@Getter
private volatile int count = 100;
private static TestAtomicFieldUpdater fieldUpdater = new TestAtomicFieldUpdater();
public static void main(String[] args) {
    if (updater.compareAndSet(fieldUpdater, 100, 120)) {
        log.info("update count success: {}", fieldUpdater.getCount());
    } else {
        log.info("update count failed: {}", fieldUpdater.getCount());
    }
    if (updater.compareAndSet(fieldUpdater, 100, 120)) {
        log.info("update count success: {}", fieldUpdater.getCount());
    } else {
        log.info("update count failed: {}", fieldUpdater.getCount());
    }
}
```

**AtomicStampReference：解决CAS的ABA问题**

CAS有3个操作数，内存值V，旧的预期值A，要修改的新值B。当且仅当预期值A和内存值V相同时，将内存值V修改为B，否则什么都不做。 **CAS算法实现一个重要前提需要取出内存中某时刻的数据，而在下时刻比较并替换，那么在这个时间差类会导致数据的变化**。 

- 场景1，一个线程1从内存位置V中取出A，这时候另一个线程2也从内存中取出A，并且3进行了一些操作变成了B，然后2又将V位置的数据变成A，这时候线程1进行CAS操作发现内存中仍然是A，然后1操作成功。尽管CAS成功，但可能存在潜藏的问题。
- 场景2，一个用单向链表实现的堆栈，栈顶为A，这时线程1已经知道A.next为B，然后希望用CAS将栈顶替换为B，在1执行指令CAS(A，B)之前，线程2介入，将A、B出栈，再入栈D、C、A，而此时对象B此时处于游离状态，当轮到线程1执行CAS(A，B)操作时，检测发现栈顶仍为A，所以CAS成功，栈顶变为B，但实际上B.next为null，C和D组成的链表不再存在于堆栈中，平白无故就把C、D丢掉 了。

**解决方案**：乐观锁，用版本戳version来对记录或对象标记，避免并发操作带来的问题。在Java中的类AtomicStampedReference<E>也实现了这个作用，它通过包装[E,Integer]的元组来对对象标记版本戳stamp，在CAS操作时带上版本号，每修改一次版本号+1，不但比较对象是否相等，还要比较版本号是否一致，从而避免ABA问题。

```java
public boolean compareAndSet(V   expectedReference,
                             V   newReference,
                             int expectedStamp,
                             int newStamp) {
    Pair<V> current = pair;
    return
        expectedReference == current.reference &&
        expectedStamp == current.stamp &&
        ((newReference == current.reference &&
          newStamp == current.stamp) ||
         casPair(current, Pair.of(newReference, newStamp)));
}
```

### 2.2 原子性-synchronized

JDK的锁类型分为两种，保证作用对象内作用范围中，同一时刻一段代码只能执行一次：

- synchronized依赖JVM实现的同步锁；
- 代码层面的Lock，依赖特殊的CPU指令，实现类如ReentrantLock；

**synchronized的使用方法**

1. 修饰代码块：大括号括起来的代码，作用于调用的对象；
2. 修改方法：整个方法，作用于调用的对象；
3. 修饰静态方法：整个静态方法，作用于所有对象；
4. 修饰类：括号括起来的部分，作用于所有对象；

> synchronized修饰的方法在继承后，是不起作用的，子类若要使用同步方法需要添加synchronized关键字显示声明。

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

#### 2.2.1 **对象锁的同步与异步问题**

下面代码中method1和method2各自打印调用该方法的线程名称，不同的是method1上加了synchronized修饰。当两个线程分别同时访问method1和method2时打印的结果会不同：

1.  当同时访问method1时，先打印t1，然后打印t2。**t1线程先持有object对象的Lock锁，t2线程如果在这个时候调用对象中的同步（synchronized）方法则需等待，也就是同步**
2.  当两个线程同时分别访问method1，method2时，同时打印t1,t2。**t1线程先持有object对象的Lock锁，t2线程可以以异步的方式调用对象中的非synchronized修饰的方法**

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

#### 2.2.2 脏读问题

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

#### 2.2.3 Synchronized锁重入

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

#### 2.2.4 **出现异常，锁自动释放**

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

#### 2.2.5 使用synchronized需要注意的问题

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

### 2.3 可见性-volatile

导致共享变量在线程间不可见的原因：

- 线程交叉执行
- 重排序结合线程交叉执行
- 共享变量更新后的值没有在工作内存和主存间及时更新

JMM关于synchronized的两条规定保证变量的可见性：

1. 线程解锁前，必须把共享变量的最新值刷新到主存；
2. 线程加锁时，将清空工作内存中共享变量的值，从而使用共享变量时需要从主内存中重新读取最新的值(加锁和解锁为同一把锁)；

volatile的可见性通过加入**内存屏障**和**禁止重排序**优化来实现，规则如下：

1. 对volatile变量写操作时，会在写操作后加入一条store屏障指令，将本地内存中的共享变量值刷新到主内存中；

   ```mermaid
   graph LR
     A[普通读]
     B[普通写]
     C[StoreStore屏障]
     D[volatile写]
     E[StoreLoad屏障]
     A --> B
     B --> C
     C --> D
     D --> E
   ```

   - StoreStore：禁止上面的普通写与下面的volatile写重排序。
   - StoreLoad：防止上面的volatile写与下面可能有的volatile读写重排序。

2. 对volatile变量读操作时，会在读操作前加入一条load屏障指令，从主内存中读取共享变量；

   ```mermaid
   graph LR
     A[volatile读]
     B[LoadLoad屏障]
     C[LoadStore屏障]
     D[普通读]
     E[普通写]
     A --> B
     B --> C
     C --> D
     D --> E
   ```

   - LoadLoad：禁止下面所有普通读操作和上面的volatile读从排序。
   - LoadStore：禁止下面所有的写操作和上面的volatile读重排序。

#### 2.3.1 volatile的含义及使用

Volatile关键字的主要作用是**使变量在多线程间可见**。在Java中每个线程都会有一块工作内存区，其中存放着所有线程共享的主内存中的变量值的拷贝。当线程执行时，他在自己的工作内存区中操作这些变量。为了存取一个共享的变量，一个线程通常先获取锁定并清除它的内存工作区，把这些共享变量从所有线程的共享内存区中正确的装入大它自己的工作内存区中，当线程解锁时，保证该工作内存区中变量的值写回到共享内存中。

- 一个线程可以执行的操作有use、assign、load、store、lock、unlock；
- 主内存可以执行的操作有read、write、lock、unlock，其中每个操作都是原子的；

volatile的作用就是强制线程到主内存(共享内存)里去读取变量，而不去线程工作区里读取，从而实现了多个线程间的变量可见，也是满足线程安全的可见性。

**使用volatile必须具备两个条件：1. 对变量的写操作不依赖当前值；2. 该变量没有包含在具有其他变量的不变式中**。可以看出被写入 volatile 变量的这些有效值**独立于任何程序的状态**，包括变量的当前状态，通常volatile常用于状态标记量，例如线程初始化是否完成、double-check。

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

#### 2.3.2 原子性与可见性的区别

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
### 2.4 有序性-happens-before

Java内存模型中，允许编译器和处理器对指令进行重排序，但重排序过程不会影响到单线程程序的执行，却会影响到多线程并发执行的正确性。保证有序性的手段有：volatile、synchronized、Lock、happens-before。

什么是happens-before？举个例子：

```java
i = 1; // 操作 A 
j = i; // 操作 B
```

如果 操作A happens-before 于 操作B，那么就可以确定，操作B执行完之后，j 的值一定为 1；因为happens-before关系可以向程序员保证： 在操作B执行之前，操作A的执行后的影响[或者说结果](修改 i 的值)操作B是可以观察到的[或者说可见的]。**如果一个操作执行的结果需要对另一个操作可见，那么这两个操作之间必须要存在happens-before关系**，在这个例子就是A操作的结果要对B操作可见，那么必然存在A happens-before B，**使用happens-before的概念来阐述操作之间的内存可见性**

**先行发生原则(happens-before)**

1. 程序次序规则：一个线程内，按照代码顺序，书写在前面的操作先行发生于书写在后面的操作。(对单线程有效，多线程不一定)
2. 锁定规则：一个 unlock操作先行发生于后面对同一个锁的lock操作。
3. volatile变量规则：对一个变量的写操作先行发生于后面对这个变量的读操作。
4. 传递性：如果A happens-before B，且B happens-before C，那么A happens-before C 。
5. 线程启动规则：Thread对象的start方法先行发生于此线程的每一个动作。
6. 线程中断原则：对线程的interrupt方法的调用先行发生于被中断线程的代码检测到中断事件的发生。
7. 线程终结规则：线程中所有的操作都先行发生于线程的终止检测，可以通过Thread.join方法结束，Thread.isAlive的返回值手段检测到线程已终止执行。
8. 对象终结规则：一个对象的初始化完成先行发生于他的finalize方法的开始。

> 如果操作顺序不能够从happens-before原则中推导出来，那么就不能保证操作的有序性，虚拟机可以随意的对操作进行重排序。

## 3. 安全发布对象

**发布对象**：使一个对象能够被当前范围之外的代码所使用。与之对应的概念是**对象逸出**：一种错误的发布，当一个对象还没有构造完成时，就使它被其他线程所见。在日常开发中，经常要发布对象，比如通过类的非私有方法返回对象引用、通过共有静态变量发布对象。如果不正确的发布对象会导致两种错误：

1. 发布线程以外的任何线程都可以看到发布对象的过期的值；
2. 线程看到的被发布对象的引用是最新的，然而被发布对象的状态确实过期的；

因此一个对象要是可变对象，就必须要正其能够安全发布。

下面通过非私有方法发布对象是不安全的，因为我们无法假设其他线程会不会修改这个对象，从而会造成类中状态错误。当采用这种方法获取对象私有对象的引用，就可以在其他线程中直接修改数组中的值，这样当该线程要使用数组中的值时会出现问题，这种发布对象的方法不安全。

```java
@NotThreadSafe
@Slf4j
public class PublishObject {
    @Getter
    private String[] states = {"a", "b","c"};
    public static void main(String[] args) {
        PublishObject publishObject = new PublishObject();
        log.info("{}", publishObject.getStates());

        publishObject.getStates()[0] = "d";
        log.info("{}", publishObject.getStates());
    }
}
```

下面是对象逸出的例子，在对象未创建完成时，就访问了对象中的私有变量，

```java
@NotThreadSafe
@Slf4j
public class ObjectEscape {
    private int thisCanEscape = 0;
    public ObjectEscape() {
        // 若这里启动一个线程，会造成this对象逸出，建议线程先不要start而是采用专门的方法来统一启动线程，例如；工厂方法、私有构造函数完成对象创建和监听器的注册
        new InnerClass(); 
    }
    private class InnerClass {
        public InnerClass() {
            log.info("{}", ObjectEscape.this.thisCanEscape);
        }
    }
    public static void main(String[] args) {
        new Escape();
    }
}
```

**安全发布对象的方法**

1. 在静态初始化函数中初始化一个对象引用；
2. 将对象的引用保存到volatile类型或AtomicReference对象中；
3. 将对象的引用保存到某个正确构造对象的final类型域中；
4. 将对象的引用保存到一个由锁保护的域中；

**懒汉模式**

 ```java
@NotThreadSafe
@NotRecommend
public class Singleton1 {
    private Singleton1(){} //私有构造函数
    private static Singleton1 intance = null; //单例对象
    //静态工厂方法，添加synchronized方法后保证同步，但不推荐
    //通过不同确保只有线程顺序访问会带来性能问题。
    public static Singleton1 getInstance() {
        //懒汉模式：线程不安全
        if (intance == null) {
            intance = new Singleton1();
        }
        return intance;
    }
}
 ```

**饿汉模式**

```java
@ThreadSafe
public class Singleton2 {
    private Singleton2(){} //私有构造函数
    //单例对象，饿汉模式：线程安全，当类的初始化没有太多操作要做是可以，
    //当初始化需要过多操作处理，会导致类加载时过慢，可能会引起性能问题；
    //同时静态方法都会加载，若未被使用会造成资源浪费。
    private static Singleton2 intance = new Singleton2();
    /**
     饿汉模式的另一种写法
     要写在前面，静态域初始化与声明顺序有关，放在后面导致instance值为空
    private static Singleton2 intance = null;
    static {
        intance = new Singleton2();
    }*/
    //静态工厂方法
    public static Singleton2 getInstance() {
        return intance;
    }
}
```

**双重检测模式**

```java
@NotThreadSafe
public class Singleton3 {
    //私有构造函数
    private Singleton3(){}
    //单例对象 禁止指令重排
    private volatile static Singleton3 intance = null;
    /**
     * 为什么说是非线程安全的呢？
     * new Singleton3()操作要执行三步：
     * 1. 分配对象的内存空间memory allocation
     * 2. 初始化对象ctorInstance
     * 3. 设置instance指向刚刚分配的内存
     * 操作完成后instance就指向被分配的内存，在单线程总这个是没有问题的。
     * 因为JVM和CPU优化导致指令重排，导致再多线程中可能会出现下面的情况：
     * 因为2和3没有前后关联，因此可能顺序为132，当线程A、B处于下面位置时，
     * 当线程A执行到操作2后，线程B判断instance不为空直接返回instance对象，
     * 此时由于instance尚未进行初始化，因此线程B拿到对象引用进行其他操作
     * 就可能出现错误。
     *
     * 限制不让其指令重排：使用关键字volatile，double-check线程安全
     */
    public static Singleton3 getInstance() {
        if (intance == null) {                                   //线程B
            synchronized (Singleton3.class) {
                //double-check：双重同步锁
                if (intance == null)intance = new Singleton3();  //线程A
            }
        }
        return intance;
    }
}
```

**枚举模式**

```java
@ThreadSafe
@Recommend
public class Singleton4 {
    private Singleton4(){}
    public static Singleton4 getInstance() {
        return Singleton.INSTANCE.getInstance();
    }
    //枚举类实现单例模式，相比于懒汉模式安全性更易于保证，
    // 较饿汉模式在实际使用时才初始化不会造成资源模式
    private enum Singleton {
        INSTANCE;
        private Singleton4 singleton;
        //JVM保证这个方法绝对只被实例化一次
        Singleton() {
            singleton = new Singleton4();
        }
        public Singleton4 getInstance() {
            return singleton;
        }
    }
}
```

## 4. 不可变对象

不可变对象需要满足的条件：

- 对象创建后其状态就不能修改
- 对象所有域都是final类型
- 对象是正确创建的(在对象创建期间，this引用没有逸出)

通常创建不可变对象(可以参考String)采用的方式有将类声明为final，所有成员声明为私有的不允许直接访问成员，对变量不提供setter方法，将所有可变对象声明为final这样只能对它们赋值一次，通过构造器初始化所有成员进行深度拷贝，在getter方法中不直接返回对象本身而是克隆对象并返回对象的拷贝。

**final关键字**可以**修饰类**(不能被继承，同时final类中的所有成员方法隐式指定为final方法)、**修饰方法**(锁定方法不被继承类修改，早期final方法会被转为内嵌方法提高效率但若final方法过于庞大效果就不会太明显--现在已没有这个作用了，一个类的private方法会隐式转为final方法)和**修饰变量**(基本类型初始化后不能修改，引用类型变量初始化后不能再指向另外一个对象)。

**定义不可变对象的类和方法**

1. collections.unmodifiableXXX：Collection、List、Set、Map
2. Guava中ImmutableXXX：Collection、List、Set、Map，带初始化数据的方法，初始化后就无法修改

```java
private static Map<Integer, Integer> maps = Maps.newHashMap();
private final static ImmutableList list = ImmutableList.of(1,2,3);
private final static ImmutableSet set = ImmutableSet.copyOf(list);
private final static ImmutableMap<Integer, Integer> map =
    ImmutableMap.of(1,2,3,4);
private final static ImmutableMap<Integer, Integer> map1 = 
    ImmutableMap.<Integer, Integer>builder().put(2,3).put(3,4).build();
static {
    maps.put(2,3);
    maps.put(1,4);
    maps.put(6,5);
    maps = Collections.unmodifiableMap(maps);
}
```

## 5. 线程封闭

通过在某些情况下，将不会修改的类对象设计成不可变对象来让对象在多个线程间保证对象时线程安全的，归根到底是我们躲避并发问题。避免并发除了使用不可变对象，还有另一种方法**线程封闭**：把对象封装到一个线程中，只有这一个线程能够看到这个对象，那么这个对象就算不是线程安全的也不会出现任何线程安全方面的问题，因为该对象只能在一个线程中访问。实现线程封闭的方法有：

- **Ad-hoc线程封闭**，程序控制实现，最糟糕，可忽略；
- **堆栈封闭**，即局部变量，多线程执行方法时，方法中的局部变量都会被拷贝一份到线程工作栈中，因此局部变量不会被多个线程所共享的，因此无并发问题；**全局变量容易引发并发问题**
- ThreadLocal线程封闭，比较好的封闭方法，ThreadLocal内部维护了一个Map，键为每个线程的名称，值为要封闭的对象，每一个线程中的对象都对应于一个Map中的值。

线程局部变量(ThreadLocal)是一种**多线程间并发访问变量的解决方案**，与synchronized加锁方式不同，线程局部变量完全不提供锁，而使用以空间换时间的手段，为每个线程提供变量的独立副本，以保证线程安全。从性能上说，ThreadLocal不具有绝对的优势，在并发不是很高时，加锁的性能会更好，但作为一套与锁完全无关的线程安全解决方案，在高并发量或者竞争激烈的场景，使用ThreadLocal可以在一定程度上减少锁竞争。

使用场景：请求进来之后通过Filter拦截请求将线程信息保存在ThreadLocal变量中，当需要使用时从ThreadLocal变量中取出使用，若不使用了通过Interceptor将使用后的信息移除掉避免内存泄漏。

```java
public class RequestHolder {
    private final static ThreadLocal<Long> requestHolder = new ThreadLocal<>();
    public static void add(Long id) {
        requestHolder.set(id);
    }
    public static Long getId() {
        return requestHolder.get();
    }
    //若不移除，数据不会释放，会造成内存泄漏，requestHolder静态变量生命周期与项目一样，只有项目重启后存储的信息才会被释放
    public static void remove() {
        requestHolder.remove();
    }
}

public void doFilter(
    ServletRequest servletRequest, 
    ServletResponse servletResponse, 
    FilterChain filterChain)  {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    log.info("do filter,{},{}", Thread.currentThread().getId(), request.getServletPath());
    RequestHolder.add(Thread.currentThread().getId());
    filterChain.doFilter(servletRequest, servletResponse);
}

public class HttpInterceptor extends HandlerInterceptorAdapter {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("preHandle");
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        RequestHolder.remove();
        log.info("afterCompletion");
        return;
    }
}
```







## 6. 高并发处理的思路及手段 

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\3.jpg)

















