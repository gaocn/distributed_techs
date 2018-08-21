## 高并发处理的思路及手段 

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\3.jpg)

### 1. 高并发场景之扩容

占用内存大小取决于工作内存里变量的多少，单个线程占用内存通常不会很大，但随着并发线程不断增加占用的内存也随之增加，最简答的扩容方法是增加系统内存(垂直扩容)，这样应用能够分配更多的内存来支撑并发的压力。复杂一点可能会考虑增加一台服务器分担一些压力(水平扩容)。

**垂直扩容**

在垂直扩展模型中，想要增加系统负荷就意味着要在系统现有的部件上下工夫，即通过提高系统部件的能力来实现。通过提高某个部件的性能（或速度）来提高负载能力的。（如数据库增加缓存处理，增加服务器CPU性能等） 。

**例子：**我们假设有3辆卡车，每辆车一次可以运25根木材，计算花费1小时的情况下可以运送到指定地点等待处理的木材数量。通过这些数字我们可以算出我们系统最大的负荷量：
$$
3辆卡车 * 25根木材 * 1小时=75根木材／小时
$$
如果我们选择垂直扩展模型，那么我们将怎么做来使我们每小时可以处理150根木材？我们需要至少做以下两件事中的一件：

1. 使每辆卡车的运输量增加一倍（50棵树每小时）：3辆卡车 * 50棵树 * 1小时 = 150棵树／每小时。
2. 使每辆卡车的运输时间减半（每辆卡车30分钟）：3辆卡车 * 25棵树 * 30分钟 = 150棵树／每小时。

我们没有增加系统的成员数，但是我们通过增加系统成员的生产效率来获得期望的负荷量。

**水平扩容**

在水平扩展模型中，我们不是通过增加单个系统成员的负荷而是简单的通过增加更多的系统成员来实现。 通过增加更多的系统成员（性能或速度一样）来提高负载能力的（如服务器集群）。

**例子：**在以上运送木材的例子中，通过增加卡车的数量来运送木材，因此当我们需要将负荷从75棵树每小时增加到150棵树每小时，那么只需要增加3辆卡车。

为什么数据库是最边缘的？因为数据库通常是共享资源，是几乎所有请求最终的连接点。

- **读操作扩展**

  如 果你的系统读操作非常多，那么通过关系型数据库如mysql或者PostgreSql来垂直扩展数据存储是一个不错的选择。结合你的关系型数据库通过使用 memcached或者CDN来构建一个健壮的缓存系统，那么你的系统将非常容易扩展。在这种模式中，如果数据库超负荷运行，那么将更多的数据放入缓存中来缓解系统的读压力。当没有更多的数据往缓存中放时，可以更换更快的数据存储硬件或者买更多核的处理器来获取更多的运行通道。摩尔定律使通过这种方法来垂直扩展变得和购买更好的硬件一样简单。

- **写操作扩展**

  如 果你的系统写操作非常多，那么你可能更希望考虑使用可水平扩展的数据存储方式，比如Riak，Cassandra或者HBase。和大多数关系型数据管理 系统不同，这种数据存储随着增长增加更多的节点。由于你的系统大部分时间是在写入，所以缓存曾并不能像在读操作比较频繁的系统中起到那么大作用。很多写频 繁的系统一开始使用垂直扩展的方式，但是很快发现并不能根本解决问题。为什么？因为硬盘数和处理器数在某一点达到平衡，在这个边界上再增加一个处理器或者 一个硬盘都会是每秒钟的I/O操作数成指数性增长。相反，如果对写频繁的系统采取水平扩展策略，那么你将达到一个拐点，在这个拐点之后如果在增加一个节点都远比使用更多的硬盘来的实惠。

**扩容引入的开销**

每种扩展策略下预想不到的开销。采用垂直扩展的系统将开销凡在单独的组件上。当我们去提升系统负荷时，这些单独的组件需要在管理上花费更多。拿我们运送木材的例子来说，如果需要使每辆卡车的货运量翻倍，那么我们需要更宽、更长、或者更高的车厢。也许有的路因为桥的高度对车辆高度有要求， 或者基于巷子宽度车宽不能太大，又或者由于机动车安全驾驶要求车厢不能太长。这里的限制就是对单个卡车做垂直扩展做的什么程度。同样的概念延伸到服务器垂直扩展：更多的处理器要求更多的空间，进而要求更多的服务器存储架。

采用水平扩展的系统将额外的开销放在系统中连接起来的共享组件上。当我们去提升系统负荷时，共享的开销和新增加的成员之间的协调性有关。在我们运送木材的例子中，当我们在路上增加更多卡车时，那么路就是共享资源也就 成了约束条件。这条路上适合同时跑多少量卡车？我们是否有足够的安全缓冲区使得所有的车可以同时装运木材？如果再来看我们水平扩展的数据库系统，那么经常 被忽略的开销就是服务器同时连接时的网络开销(译者注：网络为各个系统的共享资源)。当你为系统增加更多的节点时，共享资源的负荷也就越来越重，通常呈非线性改变。

### 2. 高并发场景之缓存

**使用缓存的场景** ：一般来说，现在网站或者app的整体流程可以用下图来表示。用户从浏览器或者app 发起请求 > 到网络转发 > 到服务 > 再到数据库，接着再返回把请求内容呈现给用户。但是随着访问量的增大 ，访问内容的增加，应用需要支撑更多的并发，同时应用服务器和数据库服务器所要做的计算越来越多，但是应用服务器的资源有限，文件内容的读写也是有限的 。 如何利用有限的资源提高更大的吞吐量 ，那就是**引入缓存** ，打破标准的流程，在每个环节中 ，请求可以从缓存中直接获取目标数据并返回 ，从而减少应用的计算量，提升应用的响应速度 ，让有限的资源服务更多的用户。如下图，缓存可以出现在 1 ~ 4 的各个环节中。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\19.jpg)

#### 2.1 缓存的特征、场景及组件

**缓存的特征**

- 命中率 = 命中数 / (命中数+ 没有命中数) 
  命中率越高 ，说明使用缓存的收益越好 ，应用性能越好，响应的时间越短 ，吞吐量越高 ,抗并发的能力越强
- 最大元素(空间) 
  代表缓存中可以存放的最大元素的数量 ，一旦缓存中元素的数量超过最大空间 ,是指缓存数据所在空间超过最大支持的空间，将会触发清空策略 . 根据不同的场景合理的设置最大元素值，可以在一定程度上提高缓存的命中率，从而更有效的使用缓存 .
- 清空策略
  - `FIFO` `first in first out`先进先出 
    最先进入缓存空间的数据 ，在缓存不够的情况下，或者缓存数量超过最大元素的情况下 ,会被优先清除掉 ，以腾出空间缓存新的数据，这个清除算法主要是比较缓存元素的创建时间.在数据实时性要求场景下可以使用该策略 ,优先保证最新数据可用 .
  - `LFU` `least frequently used`最少使用策略 
    该策略是根据元素的使用次数来判断 ，无论缓存元素是否过期 ，清除使用次数最少的元素来释放空间.这个策略的算法主要比较元素的命中次数.在保证高频数据有效性的场景下 ，可以使用此类策略.
  - `LRU` `Least Recently Used` 最近最少使用策略 
    它是指无论是否过期 ，根据元素最后一次被使用的时间戳 ，清除最远使用时间戳的元素 ，这个策略的算法主要比较元素的最近一次被 `get`使用时间，在热点数据的场景下较适用 ，优先保证热点数据的有效性 .
  - 过期时间 
    根据过期时间来判断 ，清理过期时间最长的元素，还可以根据过期时间来判读，来清理最近要过期的元素 .
  - 随机 
    随机清理元素

**缓存命中率的影响因素**

- 业务场景和业务需求 
  缓存适合 **读多写少** 的业务场景，否则使用其意义不大,命中率会很低。业务需求也决定了对实时性的要求，直接影响到缓存的过期时间和更新策略,实时性要求越低就越适合缓存。在相同 key 和相同请求数的情况下 ,缓存的时间越长，命中率就会越高。

- 缓存的设计(粒度和策略) 
  通常情况下 ，缓存的粒度越小，命中率就会越高。

- 缓存的容量和基础设施 
  缓存的容量有限，就容易引起缓存的失效和淘汰。目前多少的缓存框架都使用了 `LRU` 这个算法。同时缓存的技术选型也是很重要的，比如采用应用内置的本地缓存,就容易出现单机瓶颈 ，而采用分布式缓存 ,它就更容易扩展，所以要做好系统容量的规划 ,并考虑是否可以扩展 ，另外不同的缓存中间件，其效率和稳定性都是有差异的.除此之外，还有其他的一些会影响缓存命中率，比如某个缓存节点挂掉的时候，要避免缓存失效，并最大程度的降低影响。业内比较典型的做法就是 **一致性hash算法** ，或者通过节点冗余的方式来避免这个问题。

  有些朋友可能还有这样的理解误区：既然也无需求对于业务实时性要求很高，而缓存时间又会影响缓存命中率，那么系统就不要使用缓存了！其实忽略一个重要因素**并发**，通常在相同缓存时间和key的情况下，并发越高**缓存的收益**就越高，即使缓存的时间很短。

**如何提高缓存命中率**

从应用架构的角度 ，要尽可能的使得应用通过缓存来直接获得数据并避免缓存失效.当然这需要对业务需求、缓存粒度、缓存策略、技术选型等各个方面通盘考虑权衡的 ，尽可能聚焦在高频访问且时效性不高的热点业务上 ，通过缓存预加载，增加存储容量，调整缓存粒度，更新缓存等手段来提高命中率。对于时效性很高或缓存空间有限的情况下，内容跨度越大、访问很随机且访问量不高的应用来说，缓存命中率可能长期都很低，预热后的缓存还没来得及访问就可能过期了。

**缓存的应用场景和分类场景**

目前的应用服务框架中 ，是根据缓存与应用的耦合度分为**本地缓存**和分**布式缓存**。

* **本地缓存**  本地缓存是指缓存中的应用组件，它最大的优点是应用和缓存是在同一个进程的内部，请求缓存非常的快速，没有过多的网络开销等。在单应用中，不需要集群支持 各节点不需要互相通知的情景下，适合使用本地缓存。 它的**缺点**也是显而易见的，由于缓存和应用耦合度较高，多个应用无法共享缓存，各个应用都需要单独维护自己的缓存，对内存也是一种浪费，资源能节省就节省。在实际实现中 ，都是同成员变量，局部变量，静态变量来实现，也还有一些框架 比如 `Guava Cache`。
* **分布式缓存**  它是指应用分离的缓存组件或服务，最大的优点是自身是一个独立的应用，与本地应用是隔离的，多个应用可以直接共享缓存，比如常用的`Memcache、Redis`



https://www.cnblogs.com/bethunebtj/p/9159914.html

https://www.cnblogs.com/dinglang/p/6133501.html

https://blog.csdn.net/qq_32447301/article/details/79673341



#### 2.2 本地缓存之Guava Cache

Guava Cache是在内存中缓存数据，相比较于数据库或redis存储，访问内存中的数据会更加高效。Guava官网介绍，下面的这几种情况可以考虑使用Guava Cache：

1. 愿意消耗一些内存空间来提升速度。
2. 预料到某些键会被多次查询。
3. 缓存中存放的数据总量不会超出内存容量。

所以，可以将程序频繁用到的少量数据存储到Guava Cache中，以改善程序性能。

guava cache是一个本地缓存。有以下优点：

- 很好的封装了get、put操作，能够集成数据源。
  一般我们在业务中操作缓存，都会操作缓存和数据源两部分。如：put数据时，先插入DB，再删除原来的缓存；ge数据时，先查缓存，命中则返回，没有命中时，需要查询DB，再把查询结果放入缓存中。 guava cache封装了这么多步骤，只需要调用一次get/put方法即可。
- 线程安全的缓存，与ConcurrentMap相似，但前者增加了更多的元素失效策略，后者只能显示的移除元素。
- Guava Cache提供了三种基本的缓存回收方式：基于容量回收、定时回收和基于引用回收。定时回收有两种：按照写入时间，最早写入的最先回收；按照访问时间，最早访问的最早回收。
- 监控缓存加载/命中情况。

Guava Cache的架构设计灵感ConcurrentHashMap，在简单场景中可以通过HashMap实现简单数据缓存，但如果要实现缓存随时间改变、存储的数据空间可控则缓存工具还是很有必要的。下图为Guava Cache的结构图，它继承了ConcurrentHashMap的思路，使用多个Segment的细粒度锁在保证线程安全的同时支持高并发场景的需求，Cache存储的是键值对的集合，不同时是还需要处理缓存过期、动态加载等算法逻辑，需要额外信息实现这些操作，对此根据面向对象的思想，还需要做方法与数据的关联性封装，主要实现的缓存功能有：自动将节点加载至缓存结构中，当缓存的数据超过最大值时，使用LRU算法替换；它具备根据节点上一次被访问或写入时间计算缓存过期机制，缓存的key被封装在WeakReference引用中，缓存的value被封装在WeakReference或SoftReference引用中；还可以统计缓存使用过程中的命中率、异常率和命中率等统计数据。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\20.png)

**构建缓存对象**

接口Cache代表一块缓存，声明如下：

```java
public interface Cache<K, V> {
    //获取缓存中key对应的value，如果缓存没命中，返回null
    V getIfPresent(Object key);
    // 获取key对应的value，若缓存中没有，则调用LocalCache的load方法，从数据源中加载，并缓存
    V get(K key, Callable<? extends V> valueLoader) throws ExecutionException;
    // 批量操作，相当于循环调用get(key)方法
    ImmutableMap<K, V> getAllPresent(Iterable<?> keys);
    //  if cached, return; otherwise create, cache , and return
    void put(K key, V value);
    void putAll(Map<? extends K, ? extends V> m);
    //删除缓存
    void invalidate(Object key);
    // 清除所有的缓存
    void invalidateAll(Iterable<?> keys);
    void invalidateAll();
    // 获取缓存中元素的大概个数。为什么是大概呢？元素失效之时，并不会实时的更新size，所以这里的size可能会包含失效元素。
    long size();
    // 缓存的状态数据包括(未)命中个数，加载成功/失败个数，总共加载时间，删除个数等。
    CacheStats stats();
    // 将缓存存储到一个线程安全的map中。
    ConcurrentMap<K, V> asMap();
    void cleanUp();
}
```

可通过CacheBuilder类构建一个缓存对象，CacheBuilder类采用builder设计模式，它的每个方法都返回CacheBuilder本身，直到build方法被调用。构建一个缓存对象代码如下

```java
public class StudyGuavaCache {
    public static void main(String[] args) {
        Cache<String,String> cache = CacheBuilder.newBuilder()
            //设置cache的初始大小为10，要合理设置该值
		   .initialCapacity(10)
            //设置并发数为5，即同一时间最多只能有5个线程往cache执行写入操作
		   .concurrencyLevel(5)
            //设置cache中的数据在写入之后的存活时间为10秒
		   .expireAfterWrite(10, TimeUnit.SECONDS)
            .build();
        cache.put("word","Hello Guava Cache");
        System.out.println(cache.getIfPresent("word"));
    }
}
```

上面的代码通过**CacheBuilder.newBuilder().build()**这句代码创建了一个Cache缓存对象，并在缓存对象中存储了*key*为word，*value*为Hello Guava Cache的一条记录。可以看到Cache非常类似于JDK中的Map，但是相比于Map，Guava Cache提供了很多更强大的功能。

**设置最大存储**

Guava Cache可以在构建缓存对象时指定缓存所能够存储的最大记录数量。当Cache中的记录数量达到最大值后再调用put方法向其中添加对象，Guava会先从当前缓存的对象记录中选择一条删除掉，腾出空间后再将新的对象存储到Cache中。

1. 基于容量的清除(size-based eviction)，通过CacheBuilder.maximumSize(long)方法可以设置Cache的最大容量数，当缓存数量达到或接近该最大值时，Cache将清除掉那些最近最少使用的缓存。
2. 基于权重的清除，使用CacheBuilder.weigher(Weigher)指定一个权重函数，并且用CacheBuilder.maximumWeight(long)指定最大总重。比如每一项缓存所占据的内存空间大小都不一样，可以看作它们有不同的“权重”（weights）。

```java
public class StudyGuavaCache {
    public static void main(String[] args) {
        Cache<String,String> cache = CacheBuilder.newBuilder()
                .maximumSize(2)
                .build();
        cache.put("key1","value1");
        cache.put("key2","value2");
        cache.put("key3","value3");
        System.out.println("第一个值：" + cache.getIfPresent("key1"));
        System.out.println("第二个值：" + cache.getIfPresent("key2"));
        System.out.println("第三个值：" + cache.getIfPresent("key3"));
    }
}
```

上面代码在构造缓存对象时，通过CacheBuilder类的maximumSize方法指定Cache最多可以存储两个对象，然后调用Cache的put方法向其中添加了三个对象。程序执行结果如下图所示，可以看到第三条对象记录的插入，导致了第一条对象记录被删除。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache设置最大缓存条目.jpg)

**设置过期时间**

在构建Cache对象时，可以通过CacheBuilder类的expireAfterAccess和expireAfterWrite两个方法为缓存中的对象指定过期时间，过期的对象将会被缓存自动删除。

1. expireAfterWrite方法指定对象被写入到缓存后多久过期。
2. expireAfterAccess指定对象多久没有被访问后过期。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        Cache<String,String> cache = CacheBuilder.newBuilder()
                .maximumSize(2)
                .expireAfterWrite(3,TimeUnit.SECONDS)
                .build();
        cache.put("key1","value1");
        int time = 1;
        while(true) {
            System.out.println("第" + time++ + "次取到key1的值为：" + cache.getIfPresent("key1"));
            Thread.sleep(1000);
        }
    }
}
```

上面的代码在构造Cache对象时，通过CacheBuilder的expireAfterWrite方法指定put到Cache中的对象在3秒后会过期。在Cache对象中存储一条对象记录后，每隔1秒读取一次这条记录。程序运行结果如下图所示，可以看到，前三秒可以从Cache中获取到对象，超过三秒后，对象从Cache中被自动删除。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache设置缓存过期1.jpg)

下面是expireAfterAccess的例子

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        Cache<String,String> cache = CacheBuilder.newBuilder()
                .maximumSize(2)
                .expireAfterAccess(3,TimeUnit.SECONDS)
                .build();
        cache.put("key1","value1");
        int time = 1;
        while(true) {
            Thread.sleep(time*1000);
            System.out.println("睡眠" + time++ + "秒后取到key1的值为：" + cache.getIfPresent("key1"));
        }
    }
}
```

通过CacheBuilder的expireAfterAccess方法指定Cache中存储的对象如果超过3秒没有被访问就会过期。while中的代码每sleep一段时间就会访问一次Cache中存储的对象key1，每次访问key1之后下次sleep的时间会加长一秒。程序运行结果如下图所示，从结果中可以看出，当超过3秒没有读取key1对象之后，该对象会自动被Cache删除。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache设置缓存过期2.jpg)

也可以同时用expireAfterAccess和expireAfterWrite方法指定过期时间，这时只要对象满足两者中的一个条件就会被自动过期删除。

注意：缓存项只有在被检索时才会真正刷新（如果CacheLoader.refresh实现为异步，那么检索不会被刷新拖慢）。因此，如果你在缓存上同时声明expireAfterWrite和refreshAfterWrite，缓存并不会因为刷新盲目地定时重置，如果缓存项没有被检索，那刷新就不会真的发生，缓存项在过期时间后也变得可以回收。

**弱引用**

通过使用弱引用的键、或弱引用的值、或软引用的值，Guava Cache可以把缓存设置为允许垃圾回收：

- CacheBuilder.weakKeys()：使用弱引用存储键。当键没有其它（强或软）引用时，缓存项可以被垃圾回收。因为垃圾回收仅依赖恒等式，使用弱引用键的缓存用而不是equals比较键。 
-  CacheBuilder.weakValues()：使用弱引用存储值。当值没有其它（强或软）引用时，缓存项可以被垃圾回收。因为垃圾回收仅依赖恒等式，使用弱引用值的缓存用而不是equals比较值。 
-  CacheBuilder.softValues()：使用软引用存储值。软引用只有在响应内存需要时，才按照全局最近最少使用的顺序回收。考虑到使用软引用的性能影响，我们通常建议使用更有性能预测性的缓存大小限定（见上文，基于容量回收）。使用软引用值的缓存同样用==而不是equals比较值。

可以通过weakKeys和weakValues方法指定Cache只保存对缓存记录key和value的弱引用。这样当没有其他强引用指向key和value时，key和value对象就会被垃圾回收器回收。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        Cache<String,Object> cache = CacheBuilder.newBuilder()
                .maximumSize(2)
                .weakKeys()
                .weakValues()
                .build();
        Object value = new Object();
        cache.put("key1",value);

        value = new Object();//原对象不再有强引用
        System.gc();
        System.out.println(cache.getIfPresent("key1"));
    }
}
```

上面代码的打印结果是null。构建Cache时通过weakValues方法指定Cache只保存记录值的一个弱引用。当给value引用赋值一新的对象之后，就不再有任何一个强引用指向原对象。System.gc()触发垃圾回收后，原对象就被清除了。

**清理什么时候发生**

使用CacheBuilder构建的缓存不会”自动”执行清理和回收工作，也不会在某个缓存项过期后马上清理，也没有诸如此类的清理机制。相反，它会在写操作时顺带做少量的维护工作，或者偶尔在读操作时做——如果写操作实在太少的话。  

这样做的原因在于：如果要自动地持续清理缓存，就必须有一个线程，这个线程会和用户操作竞争共享锁。此外，某些环境下线程创建可能受限制，这样CacheBuilder就不可用了。 

 相反，我们把选择权交到你手里。如果你的缓存是高吞吐的，那就无需担心缓存的维护和清理等工作。如果你的 缓存只会偶尔有写操作，而你又不想清理工作阻碍了读操作，那么可以创建自己的维护线程，以固定的时间间隔调用Cache.cleanUp()。ScheduledExecutorService可以帮助你很好地实现这样的定时调度。

**显示清除**

可以调用Cache的invalidateAll或invalidate方法显示删除Cache中的记录。invalidate方法一次只能删除Cache中一个记录，接收的参数是要删除记录的key。invalidateAll方法可以批量删除Cache中的记录，当没有传任何参数时，invalidateAll方法将清除Cache中的全部记录。invalidateAll也可以接收一个Iterable类型的参数，参数中包含要删除记录的所有key值。下面代码对此做了示例。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        Cache<String,String> cache = CacheBuilder.newBuilder().build();
        Object value = new Object();
        cache.put("key1","value1");
        cache.put("key2","value2");
        cache.put("key3","value3");

        List<String> list = new ArrayList<String>();
        list.add("key1");
        list.add("key2");

        cache.invalidateAll(list);//批量清除list中全部key对应的记录
        System.out.println(cache.getIfPresent("key1"));
        System.out.println(cache.getIfPresent("key2"));
        System.out.println(cache.getIfPresent("key3"));
    }
}
```

代码中构造了一个集合list用于保存要删除记录的key值，然后调用invalidateAll方法批量删除key1和key2对应的记录，只剩下key3对应的记录没有被删除。

**移除监听器**

可以为Cache对象添加一个移除监听器，这样当有记录被删除时可以感知到这个事件。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        RemovalListener<String, String> listener = new RemovalListener<String, String>() {
            public void onRemoval(RemovalNotification<String, String> notification) {
                System.out.println("[" + notification.getKey() + ":" + notification.getValue() + "] is removed!");
            }
        };
        Cache<String,String> cache = CacheBuilder.newBuilder()
                .maximumSize(3)
                .removalListener(listener)
                .build();
        Object value = new Object();
        cache.put("key1","value1");
        cache.put("key2","value2");
        cache.put("key3","value3");
        cache.put("key4","value3");
        cache.put("key5","value3");
        cache.put("key6","value3");
        cache.put("key7","value3");
        cache.put("key8","value3");
    }
}
```

removalListener方法为Cache指定了一个移除监听器，这样当有记录从Cache中被删除时，监听器listener就会感知到这个事件。程序运行结果如下图所示。

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache移除监听器.jpg)

警告：默认情况下，监听器方法是在移除缓存时同步调用的。因为缓存的维护和请求响应通常是同时进行的，代价高昂的监听器方法在同步模式下会拖慢正常的缓存请求。在这种情况下，你可以使用RemovalListeners.asynchronous(RemovalListener, Executor)把监听器装饰为异步操作。

**自动加载**

Cache的get方法有两个参数，第一个参数是要从Cache中获取记录的key，第二个记录是一个Callable对象。当缓存中已经存在key对应的记录时，get方法直接返回key对应的记录。如果缓存中不包含key对应的记录，Guava会启动一个线程执行Callable对象中的call方法，call方法的返回值会作为key对应的值被存储到缓存中，并且被get方法返回。下面是一个多线程的例子：

```java
public class StudyGuavaCache {
    private static Cache<String,String> cache = CacheBuilder.newBuilder()
            .maximumSize(3)
            .build();
    public static void main(String[] args) throws InterruptedException {

        new Thread(new Runnable() {
            public void run() {
                System.out.println("thread1");
                try {
                    String value = cache.get("key", new Callable<String>() {
                        public String call() throws Exception {
                            System.out.println("load1"); //加载数据线程执行标志
                            Thread.sleep(1000); //模拟加载时间
                            return "auto load by Callable";
                        }
                    });
                    System.out.println("thread1 " + value);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }).start();
        new Thread(new Runnable() {
            public void run() {
                System.out.println("thread2");
                try {
                    String value = cache.get("key", new Callable<String>() {
                        public String call() throws Exception {
                            System.out.println("load2"); //加载数据线程执行标志
                            Thread.sleep(1000); //模拟加载时间
                            return "auto load by Callable";
                        }
                    });
                    System.out.println("thread2 " + value);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
```

这段代码中有两个线程共享同一个Cache对象，两个线程同时调用get方法获取同一个key对应的记录。由于key对应的记录不存在，所以两个线程都在get方法处阻塞。此处在call方法中调用Thread.sleep(1000)模拟程序从外存加载数据的时间消耗。代码的执行结果如下图：

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache自动加载.jpg)

从结果中可以看出，虽然是两个线程同时调用get方法，但只有一个get方法中的Callable会被执行(没有打印出load2)。Guava可以保证当有多个线程同时访问Cache中的一个key时，如果key对应的记录不存在，Guava只会启动一个线程执行get方法中Callable参数对应的任务加载数据存到缓存。当加载完数据后，任何线程中的get方法都会获取到key对应的值。

**统计信息**

可以对Cache的命中率、加载数据时间等信息进行统计。在构建Cache对象时，可以通过CacheBuilder的recordStats方法开启统计信息的开关。开关开启后Cache会自动对缓存的各种操作进行统计，调用Cache的stats方法可以查看统计后的信息。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws InterruptedException {
        Cache<String,String> cache = CacheBuilder.newBuilder()
                .maximumSize(3)
                .recordStats() //开启统计信息开关
                .build();
        cache.put("key1","value1");
        cache.put("key2","value2");
        cache.put("key3","value3");
        cache.put("key4","value4");

        cache.getIfPresent("key1");
        cache.getIfPresent("key2");
        cache.getIfPresent("key3");
        cache.getIfPresent("key4");
        cache.getIfPresent("key5");
        cache.getIfPresent("key6");

        System.out.println(cache.stats()); //获取统计信息
    }
}
```

程序执行结果如下图所示：

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache统计信息.jpg)

这些统计信息对于调整缓存设置是至关重要的，在性能要求高的应用中应该密切关注这些数据

**Loading Cache**

LoadingCache是Cache的子接口，相比较于Cache，当从LoadingCache中读取一个指定key的记录时，如果该记录不存在，则LoadingCache可以自动执行加载数据到缓存的操作。LoadingCache接口的定义如下：

```java
public interface LoadingCache<K, V> extends Cache<K, V>, Function<K, V> {
    V get(K key) throws ExecutionException;
    V getUnchecked(K key);
    ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException;
    V apply(K key);
    void refresh(K key);
    @Override
    ConcurrentMap<K, V> asMap();
}
```

与构建Cache类型的对象类似，LoadingCache类型的对象也是通过CacheBuilder进行构建，不同的是，在调用CacheBuilder的build方法时，必须传递一个CacheLoader类型的参数，CacheLoader的load方法需要我们提供实现。当调用LoadingCache的get方法时，如果缓存不存在对应key的记录，则CacheLoader中的load方法会被自动调用从外存加载数据，load方法的返回值会作为key对应的value存储到LoadingCache中，并从get方法返回。

```java
public class StudyGuavaCache {
    public static void main(String[] args) throws ExecutionException {
        CacheLoader<String, String> loader = new CacheLoader<String, String> () {
            public String load(String key) throws Exception {
                Thread.sleep(1000); //休眠1s，模拟加载数据
                System.out.println(key + " is loaded from a cacheLoader!");
                return key + "'s value";
            }
        };
        LoadingCache<String,String> loadingCache = CacheBuilder.newBuilder()
                .maximumSize(3)
                .build(loader);//在构建时指定自动加载器

        loadingCache.get("key1");
        loadingCache.get("key2");
        loadingCache.get("key3");
    }
}
```

程序执行结果如下图所示：

![](E:\GIT\distributed_techs\imgs\java并发编程相关图例\Guava Cache Loading Cache.jpg)

>请一定要记住GuavaCache的实现代码中没有启动任何线程！！Cache中的所有维护操作，包括清除缓存、写入缓存等，都是通过调用线程来操作的。这在需要低延迟服务场景中使用时尤其需要关注，可能会在某个调用的响应时间突然变大。
>
>GuavaCache毕竟是一款面向本地缓存的，轻量级的Cache，适合缓存少量数据。如果你想缓存上千万数据，可以为每个key设置不同的存活时间，并且高性能，那并不适合使用GuavaCache

#### 2.3 分布式缓存之Memcache





#### 2.4 分布式缓存之Redis



https://blog.csdn.net/Andy86869/article/details/81668355

https://blog.csdn.net/Andy86869/article/details/81668317





