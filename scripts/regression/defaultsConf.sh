#!/bin/bash

	# the default hadoop version is currently hadoop-1.1.0-patched-v2
#export DEFAULT_CSV_FILE='/.autodirect/mtrswgwork/UDA/daily_regressions/configuration_files/regression-1.1.0.csv'
export DEFAULT_SVN_HADOOP='https://sirius.voltaire.com/repos/enterprise/uda/hadoops/hadoop-1.1.0-patched-v2'
export DEFAULT_GIT_HADOOPS_DIR='/.autodirect/mswg/git/accl/hadoops.git' #'http://webdev01:8080/git/accl/hadoops.git'
export DEFAULT_HADOOP_DIRNAME='hadoop-1.1.0-patched-v2' # hadoop-0.20.2-patched-v2
export DEFAULT_GIT_MASTER_DIR='ssh://r-webdev02:29418/accl/uda.git' #'/.autodirect/mswg/git/accl/uda' #'http://webdev01:8080/git/accl/uda.git'
export DEFAULT_RPM_JAR='uda-hadoop-1.x.jar'
#export DEFAULT_LZO_JAR='/.autodirect/mtrswgwork/UDA/daily_regressions/resources/hortonworks-hadoop-lzo-cf4e7cb/build/hadoop-lzo-0.4.15.jar'
	# --
export DEFAULT_CONF_FOLDER_DIR='/.autodirect/udagwork/uda_regression/tests_confs'
export DEFAULT_MY_HADOOP_HOME='/hadoop-1.0.1'
export DEFAULT_DATA_SET_TYPE='node'
export DEFAULT_NFS_RESULTS_DIR='/.autodirect/udagwork/uda_regression/results'                #'/.autodirect/mtrswgwork/UDA/daily_regressions/results'
export DEFAULT_SVN_REVISION=''
export DEFAULT_REPORT_MAILING_LIST='alexr,avnerb,idanwe,dinal,eladi,alongr,yuriya,oriz' #'alexr,avnerb,idanwe,katyak,amirh,dinal,eladi,alongr,yuriya,oriz'
export DEFAULT_REPORT_NAME='regression'
export DEFAULT_REPORT_SUBJECT='UDA Daily Regression Run Status'
export DEFAULT_REPORT_COMMENT=''
export DEFAULT_RAM_SIZE=48
export DEFAULT_REPORT_INPUT=""
export DEFAULT_HUGE_PAGES_COUNT=-1
export DEFAULT_CURRENT_RPM_DIR=""
export DEFAULT_INTERFACE="ib0"
export DEFAULT_CORES_DIR="/.autodirect/mtrswgwork/UDA/core_files_TEMP"

	# not user defined variables
export CURRENT_DATE_PATTERN='date +"%Y-%m-%d_%H.%M.%S"'
export ECHO_PATTERN='basename $0'
export SEC=7 # Safe Exit Code - using when need to exit without considering it as runtime error
export CEC=6  # Continue Exit Code - using when need to skip the exiting-script and continue running
export EEC1=10 # Error Exit Code 1
export EEC2=11 # Error Exit Code 2
export EEC3=12 # Error Exit Code 3
export SLEEPING_FOR_READING=5 # time to sleep that the user will see the printed message
export EXECUTION_MAX_ATTEMPTS=1 
export RESTART_HADOOP_MAX_ATTEMPTS=10
export NUMBER_OF_ATTEMPTS_LIVE_NODES=30
export WINDOES_DIR_PREFIX="\\mtrlabfs01"
export SYSCTL_PATH="/etc/sysctl.conf"
export RPMBUILD_DIR='rpmbuild/RPMS/x86_64'
export SVN_TRUNK='https://sirius.voltaire.com/repos/enterprise/uda/trunk'
export TRUNK_RPM_BUILD_RELATIVE_PATH='build/buildrpm.sh'
export MASTER_RPM_BUILD_RELATIVE_PATH='build/buildrpm.sh' 
export UDA_RESOURCES_DIR="/usr/lib64/uda"
export HADOOP_CLASSPATH='${HADOOP_CLASSPATH}:'"${UDA_RESOURCES_DIR}/"
export RELEASE_DIR='/.autodirect/mswg/release/uda/daily'
export RELEASE_RPM_RELATIVE_DIR='rpm'
export LOCAL_RESULTS_DIR='/data1/regression.collect'
export CORES_PATTERN="core.%p.%h.%t.%e"
export KERNEL_CORE_PATTERN_PROPERTY='kernel.core_pattern'
export JAVA_HOME='/usr/java/latest' #export JAVA_HOME='/usr/lib64/java/jdk1.6.0_25'
#export DATA_FOR_RAM_FLUSHING_DIR="/dataForRamFlushing"
export TERAGEN_DIR="/teragen"
export RANDOM_WRITE_DIR="/randomWrite"
export RANDOM_TEXT_WRITE_DIR="/randomTextWrite"
export TERASORT_DEFAULT_INPUT_DIR=$TERAGEN_DIR
export SORT_DEFAULT_INPUT_DIR=$RANDOM_WRITE_DIR
export WORDCOUNT_DEFAULT_INPUT_DIR=$RANDOM_TEXT_WRITE_DIR
export SORT_DIR="/sort"
export TERASORT_DIR="/terasort"
export WORDCOUNT_DIR="/wordcount"
export TERAVAL_DIR="/teraval"
export TEST_DFSIO_DIR="/benchmarks/TestDFSIO"
export TEST_DFSIO_READ_DIR="$TEST_DFSIO_DIR/io_read"
export TEST_DFSIO_WRITE_DIR="$TEST_DFSIO_DIR/io_write"
export PI_NUMERIC_ERROR="0.1"
export PI_HDFS_TEMP_DIR="/user/$USER"
export PI_REAL_VALUE="3.141592654"
export RANDOM_TEXT_WRITER_OUTPUT_SIZE=10
export TMP_DIR_LOCAL_DISK="/data1/regression_temps"
export SYSTEM_DEFAULT_SHM_MAX_SIZE=33554432
export DEFAULT_LOG_NUM_MTT=24
export DEFAULT_LOG_MTTS_PER_SEG=0
export GROUP_NAME="uda"
export HADOOP_EXAMPLES_JAR="hadoop*examples*.jar"
export HADOOP_TEST_JAR="hadoop*test*.jar"
export TERAGEN_GIGA_MULTIPLIER=10000000
export IB_MESSAGE_MULTIPLIER=1000000
export RANDOM_WRITE_GIGA_MULTIPLIER=1000000000
export RANDOM_TEXT_WRITE_GIGA_MULTIPLIER=$RANDOM_WRITE_GIGA_MULTIPLIER
export PATH="/.autodirect/mtrswgwork/UDA/bullseye/bin/:$PATH"
export DIRS_PERMISSIONS=777
export OPENSM_PROCESS_REGISTRATION="/usr/sbin/opensm --daemon"
export OPENSM_PATH="/etc/init.d/opensmd"
export OPENIBD_PATH="/etc/init.d/openibd"
export MLX_CONF_OPTIONS_LINE="options mlx4_core"
export MOFED_CONF_PATH="/etc/modprobe.d/mofed.conf"
export DEV_NULL_PATH="/dev/null"

##################
export UDA_DEFAULT_BRANCH="master"
export MASTER_PLUGINS_RELATIVE_PATH='plugins'
export INPUT_DATA_REPLICATIONS_COUNT=2
export DEFAULT_IB_MESSAGE_SIZE=0
export IS_JOb_STILL_RUNNING_SLEEP_TIME=60
export IS_JOB_STILL_RUNNING_FAILURE_TIMES=60
#export LZO_RESOURCE_FILES_PATH="/.autodirect/mtrswgwork/UDA/daily_regressions/resources/hortonworks-hadoop-lzo-cf4e7cb/build/native/Linux-amd64-64/.libs/"
export LZO_USR_INCLUDE_DIR="/usr/include/lzo"
export LZO_USR_LIB_DIR="/usr/lib64"
export LZO_USR_LIB_FILES="liblzo2.a liblzo2.la liblzo2.so liblzo2.so.2 liblzo2.so.2.0.0"
export UDA_LOG_LEVELS="ALL TRACE DEBUG INFO WARN ERROR FATAL NONE"
export PROC_CORE_PATTERN_PATH="/proc/sys/kernel/core_pattern"
export CODE_COVERAGE_EXCLUDE_PATH="/.autodirect/udagwork/uda_regression/bullseye/exclude"
export DF_BLOCKSIZE_SCALE='--block-size=1m'
export ECHO_BOLD=`tput bold`
export ECHO_NORMAL=`tput sgr0`
export SWAPOFF_PATH="/sbin/swapoff"
export SWAPON_PATH="/sbin/swapon"
export CPU_USAGE_SAMPLES_TO_AVERAGE_COUNT=5
#export CLEAN_HOSTNAMES_LIST_PATH="/.autodirect/udagwork/uda_regression/clean_hostnames_list.txt"
