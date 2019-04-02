## Java线程池原理及实现

### 一、为什么要使用线程池

1. 线程在Java中是一个对象，更是操作系统的资源，线程的创建、销毁需要时间，如果创建时间+销毁时间>执行任务时间，这样使用线程就不合适了。
2. Java对象占用堆内存，操作系统线程占用系统内存，根据JVM规范，一个线程默认最大栈大小为1M，这个栈空间是需要从系统内存中分配的，线程过多就会消耗很多内存。
3. 操作系统需要频繁切换线程上下文(实际线程的执行时采用CPU时间片轮询方式)，影响性能。

> 线程池就是为了方便控制线程数量



### 二、线程池原理

**线程池管理器**：用于创建并管理线程池，包括创建、销毁线程池、添加任务。

**工作线程**：线程池中的线程，在没有任务时处于等待状态，可以循环的执行任务。

**任务接口**：每个任务必须实现的接口，以供工作线程调度任务执行，它主要规定了任务的入口， 任务执行完后的收尾工作，任务的执行状态等。

**任务队列**：用于存放没有处理的任务提供一种缓冲机制。

**线程池API**

| 类型   | 名称                        | 描述                                                         |
| ------ | --------------------------- | ------------------------------------------------------------ |
| 接口   | Executor                    | 最上层接口，定义了 `执行任务的方法execute`                   |
| 接口   | ExecutorService             | 继承了Executor接口，拓展了Callable、Future、关闭方法         |
| 接口   | ScheduledExecutorService    | 继承了ExecutorService，增加了定时任务相关方法                |
| 实现类 | ThreadPoolExecutor          | 基础、标准的线程池实现                                       |
| 实现类 | ScheduledThreadPoolExecutor | 继承了ThreadPoolExecutor，实现了ScheduledExecutorService中定时任务相关的方法 |

**ExecutorService接口**

| 方法名                                             | 说明                                                         |
| -------------------------------------------------- | ------------------------------------------------------------ |
| awaitTermination(long timeout, TimeUnit)           | 监测ExecutorService是否已关闭，直到所有任务执行完成或超市发生或当前线程被中断 |
| invokeAll(Collection<? extends Callable<T>> tasks) | 执行给定的任务集合，执行完毕后返回结果                       |
| invokeAny(Collection<? extends Callable<T>> tasks) | 执行给定任务，任意一个任务执行成功后则返回结果，其他任务终止 |
| isShutdown()                                       | 如果线程池已关闭，则返回true                                 |
| isTerminated()                                     | 如果关闭后所有任务都已经完成，则返回true                     |
| shutdown()                                         | 优雅关闭线程池，之前提交的任务将被执行，但不会接受新任务     |
| shutdownNow()                                      | 尝试停止所有正在执行的任务，停止等待任务的处理，并返回等待执行任务的列表 |
| submit(Callable<T> task)                           | 提交一个用于执行的Callable返回任务，并返回Future，用于获取Callable执行结果 |
| submit(Runnable task)                              | 提交可运行任务以执行，并返回Future，执行结果为null           |
| submit(Runnable task, T result)                    | 提交可运行任务以执行，并返回Future，执行结果为传入的result   |

****

**ScheduledExecutorService接口**

| 方法                                                         | 说明                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| schedule(Callable<V> callable, long delay, TimeUnit)         | 创建并执行一个一次性任务，过了延迟时间就会被执行             |
| schedule(Runnable command, long delay, TimeUnit)             | 创建并执行一个一次性任务，过了延迟时间就会被执行             |
| scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit) | 创建并执行一个周期任务，过了初始延迟时间第一次被执行，后续以给定的周期时间执行，执行过程中发生异常任务停止。 |
| scheduleWithFixedRate(Runnable command, long initialDelay, long period, TimeUnit) | 创建并执行一个周期任务，过了初始延迟时间第一次被执行，后续以给定的周期时间执行，执行过程中发生异常任务停止。 |

scheduleAtFixedRate与scheduleWithFixedRate中的任务只可能会一个一个的执行，不会被同时执行。**当一次任务执行时间超过了周期时间**，两者的区别：

- scheduleAtFixedRate，下一次任务会等到该次任务执行后立即执行。
- scheduleWithFixedRate，下一次任务会在该次任务执行结束的时间基础上，计算执行延迟。例如：scheduleWithFixedRate(cmd, 0, 1, TimeUnit.second)，假设一个任务执行时间为3s，则在第3s秒任务执行完成后，下一次任务会在第4s时执行。

**ThreadPoolExecutor示例**

```java
/*
【任务execute的过程】
  1. 是否达到核心线程数量？否，则创建一个工作线程来执行任务；是，则跳到2。
  2. 工作队列是否已满？没满，则将新提交的任务存储在工作队列里；满了，则跳到3。
  3. 是否达到线程池最大数量？没达到，则创建一个新的工作线程来执行任务；达到，则跳到4。
  4. 执行拒绝策略来处理任务：DiscardPolicy、AbortPolicy、DiscardOldestPolicy、CallerRunsPolicy。
*/

/*
 * 核心线程数量5，最大线程数量为10，无界队列，超出核心线程数量的线程存活时间5s(5个线程会长存)，
 * 默认拒绝策略为DiscardPolicy
 */
new ThreadPoolExecutor(5, 10, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>())
    
/*
 * 核心线程数量5，最大线程数量为10，队列大小3，超出核心线程数量的线程存活时间5s，最大可支持13个任务
 *
 * 默认策略为AbortPolicy即会抛出RejectedExecutionException异常
 */
new ThreadPoolExecutor(5, 10, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(3))   
    
/*
 * 核心线程数量为0，最大线程数量为Integer.MAX_VALUE，SynchronousQueue队列，超过核心线程数量的存
 * 活时间为60s。
 * SynchronousQueue实际上不是一个真正的队列，因为它不会为队列中元素维护存储空间，与其他队列不同的
 * 是它维护一组线程，这些线程在等待着把元素加入或移出队列。当使用SynchronousQueue作为工作队列，
 * 客户端向线程池提交任务时，而线程池中又没有空闲线程能够从同步队列中取出一个任务，则offer方法调用
 * 失败(即任务没有被存入工作队列)，此时，ThreadPoolExecutor就会新建一个工作线程用于对这个入队列失
 * 败的的任务进行处理(假设此时线程池大小还未达到最大线程池数)。
 *原理：有工作线程就复用，没有就新增工作线程！适用于线程耗时小，任务数量无法预估的情况，想尽快执行
 * 任务不想等待。
 */
new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>())
    
/*
 * 定时队列，3秒后执行任务，一次性任务，核心线程数5，最大线程数量为Integer.MAX_VALUE，采用延迟队
 * 列DelayedWorkQueue，超出核心线程数量的线程存活时间为0秒
 */
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5)
executor.schedule(Runnable, 3000, TimeUnit.MILLISECOND)

/*
 * 周期性执行任务，第一次延迟2s执行，以后任务每隔1s执行一个，当上一次任务超时，则等待上一次任务执行
 * 结束后，立刻执行。
 */
executor.scheduleAtFixedRate(Runnable, 2000，1000, TimeUnit.MILLISECOND)
/*
 * 周期性执行任务，第一次延迟2s执行，以后任务每隔1s执行一个，当上一次任务超时，则等待上一次任务执行
 * 结束后，等待1s后执行。
 */
executor.scheduleWithFixedRate(Runnable, 2000，1000, TimeUnit.MILLISECOND)       
```

**`Executors`工厂类提供了四个创建线程池的方法 ** ：

1. newFixedThreadPool(int nThreads)创建一个固定大小、任务队列无界的线程池，核心线程数量等于最大线程数。
2. newCachedThreadPool()创建一个大小无界的缓冲线程池，任务队列为同步队列。任务加入到池中，如果池中有空闲线程，则用空闲线程执行，若没有则创建新线程执行。池中的线程空闲超过60秒将会被销毁释放。线程数随任务的多少变化，适用于执行耗时较小的异步任务(没法预估任务的数量)，池的核心线程数等于0，最大小线程数为Integer.MAX_VALUE。
3. newSingleThreadPool()只有一个线程来执行无界队列的单一线程池，该线程池确保任务按加入顺序依次执行，当唯一的线程因异常终止时，将创建一个新的线程来继续执行后续的任务。与newFixedThreadPool(1)的区别是：单一线程池大小在newSingleThreadPool方法中硬编码，不能炸改变。
4. newScheduledThreadPool(int corePoolSize)定时执行任务的线程池，核心线程由参数指定，最大线程为Integer.MAX_VALUE





