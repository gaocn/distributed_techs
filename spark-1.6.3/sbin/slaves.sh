#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Run a shell command on all slave hosts.
#
# Environment Variables
#
#   SPARK_SLAVES    File naming remote hosts.
#     Default is ${SPARK_CONF_DIR}/slaves.
#   SPARK_CONF_DIR  Alternate conf dir. Default is ${SPARK_HOME}/conf.
#   SPARK_SLAVE_SLEEP Seconds to sleep between spawning remote commands.
#   SPARK_SSH_OPTS Options passed to ssh when running remote commands.
##

usage="Usage: slaves.sh [--config <conf-dir>] command..."

# if no args specified, show usage
if [ $# -le 0 ]; then
  echo $usage
  exit 1
fi

if [ -z "${SPARK_HOME}" ]; then
  export SPARK_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

# 1、用source dot(.)执行脚本不产生子进程，脚本运行完成后变量在当前shell中可见；
# 2、直接执行脚本文件产生子进程，子进程运行后脚本中的变量在当前shell中不可见；
. "${SPARK_HOME}/sbin/spark-config.sh"

# If the slaves file is specified in the command line,
# then it takes precedence over the definition in
# spark-env.sh. Save it here.
if [[ -f "$SPARK_SLAVES" ]]; then
  HOSTLIST=`cat "$SPARK_SLAVES"`
fi

# Check if --config is passed as an argument. It is an optional parameter.
# Exit if the argument is not a directory.
if [[ "$1" == "--config" ]]
then
  shift
  conf_dir="$1"
  if [[ ! -d "$conf_dir" ]]
  then
    echo "ERROR : $conf_dir is not a directory"
    echo $usage
    exit 1
  else
    export SPARK_CONF_DIR="$conf_dir"
  fi
  shift
fi

. "${SPARK_HOME}/bin/load-spark-env.sh"

if [[ "$HOSTLIST" = "" ]]; then
  if [[ "$SPARK_SLAVES" = "" ]]; then
    if [[ -f "${SPARK_CONF_DIR}/slaves" ]]; then
      HOSTLIST=`cat "${SPARK_CONF_DIR}/slaves"`
    else
      HOSTLIST=localhost
    fi
  else
    HOSTLIST=`cat "${SPARK_SLAVES}"`
  fi
fi



# By default disable strict host key checking
if [[ "$SPARK_SSH_OPTS" = "" ]]; then
  SPARK_SSH_OPTS="-o StrictHostKeyChecking=no"
fi

#
#1、$"string":表示string会根据当前locale进行翻译，若locale为C或POSIX则$符号可以忽略；
#2、${parameter/parttern/string}:模式替换，对变量parameter中所有的值进行模式替换。
#	（1）若pattern以/开始则所有匹配的模式都会被替换为string；
#	（2）默认只替换第一个匹配的字符串；
#	（3）若pattern以#开始则必须要从parameter值的开始位置匹配；
#	（4）若patter以%开始则必须要从parameter值的末尾位置开始匹配；
#3、若string为空会将匹配的字符串删除，此时可以将pattern后面的/去掉；
#4、若parameter为@或*，则会对每个位置参数或数组元素进行模式替换；
#  例如：
#       parameter=" ha wo ni"
#       echo $"${parameter// /\\}"
#       Result：\ha\wo\ni
#
#       c\d ${SPARK_HOME} 可以执行成功
#       c d ${SPARK_HOME} 报错：command not found
#

for slave in `echo "$HOSTLIST"|sed  "s/#.*$//;/^$/d"`; do
  if [[ -n "${SPARK_SSH_FOREGROUND}" ]]; then
    ssh $SPARK_SSH_OPTS "$slave" $"${@// /\\ }" \
      2>&1 | sed "s/^/$slave: /"
  else
    ssh $SPARK_SSH_OPTS "$slave" $"${@// /\\ }" \
      2>&1 | sed "s/^/$slave: /" &
  fi
  if [[ "$SPARK_SLAVE_SLEEP" != "" ]]; then
    sleep $SPARK_SLAVE_SLEEP
  fi
done

# wait [进程号 或 作业号]
# 如果wait后面不带任何的进程号或作业号，那么wait会阻塞当前进程的执行，
# 直至当前进程的所有子进程都执行结束后，才继续执行。
wait
