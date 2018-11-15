Shark-Shell是一个应用程序，使用JPS可以看到一个SparkSubmit进程，即启动SparkShell的时候是以SparkSubmit为入口构建的一个JVM进程。可以通过查看SharkShell程序的调用栈，主线程就是SparkSubmit进程的入口所调用的程序。
在main函数的程序栈中可以看到执行的是`repl.Main`的代码，采用反射方式调用其中的代码运行在Job中。
```
"${SPARK_HOME}"/bin/spark-submit --class org.apache.spark.repl.Main --name "Spark shell" "$@"
```
而spark-submit脚本执行的程序为：
```
# exec可以启动进程
exec "${SPARK_HOME}"/bin/spark-class org.apache.spark.deploy.SparkSubmit "$@"
```
可以看到`SparkSubmit`类即为JVM进程的入口类。

>问题：手动动态的在Spark的一个程序中运行多个程序？ 如：可以在SparkShell中动态运行多种不同的JOB。应用场景：基因匹配、人脸识别、交通道路的监控，Spark是基于JVM，可以用一个程序接收基因、人脸或交通的信息，在接收数据后再启动相应的Spark应用程序（Runtime.exec...），只不过应用程序是同一个只不过是输入数据不同。

## `spark-shell`、`spark-submit`与`spark-class`的关系
spark-shell的调用会通过spark-submit来实现，而spark-submit的调用会通过spark-class的调用来实现。
```
#1、spark-shell`
"${SPARK_HOME}"/bin/spark-submit --class org.apache.spark.repl.Main --name "Spark shell" "$@"
#2、spark-submit
exec "${SPARK_HOME}"/bin/spark-class org.apache.spark.deploy.SparkSubmit "$@"
#3、spark-class
CMD=()
# scala本身的运行时依赖java的
while IFS= read -d '' -r ARG; do
  CMD+=("$ARG")
done < <("$RUNNER" -cp "$LAUNCH_CLASSPATH" org.apache.spark.launcher.Main "$@")
exec "${CMD[@]}"
```
最终是通过调用`org.apache.spark.launcher.Main`的应用程序执行

## REPL: Read Evaluation Print Loop
为什么Scala有内置REPL，Spark也需要自己的REPL？因为创建的类对象在Scala REPL中会直接执行，而Spark框架需要对类进行加工后才能执行（而不是直接创建对象），是lazy级别的，可以对类进行优化调度，另一个原因是命令不是本地运行而是在集群中运行与Scala中REPL中不同。Scala中的REPL每一次执行都会被封装成对象，然后对对象进行编译生成字节码，由于类加载器加载后去执行，与Spark的REPL需求不同。
**REPL相关类具体参考**：http://spark.apache.org/docs/0.8.1/api/repl/org/apache/spark/repl/package.html
https://blog.csdn.net/wang_wbq/article/details/82318534

1. SparkILoop: The Scala interactive shell.
2. SparkIMain: An interpreter for Scala code
3. SparkISettings: Settings for the interpreter
4. SparkJLineReader: Reads from the console using JLine
5. ExecutorClassLoader: A ClassLoader that reads classes from a Hadoop FileSystem or HTTP URI, used to load classes defined by the interpreter when the REPL is used



