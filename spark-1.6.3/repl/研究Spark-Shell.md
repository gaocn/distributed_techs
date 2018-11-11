Shark-Shell是一个应用程序，使用JPS可以看到一个SparkSubmit进程，即启动SparkShell的时候是以SparkSubmit为入口构建的一个JVM进程。可以通过
查看SharkShell程序的调用栈，主线程就是SparkSubmit进程的入口所调用的程序。

![]()

"${SPARK_HOME}"/bin/spark-submit --class org.apache.spark.repl.Main --name "Spark shell" "$@"


1、手动动态的在Spark的一个程序中运行多个程序？