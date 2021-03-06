#!/bin/bash

ramSizeChaceFlushing ()
{
	local nmaps=$((SLAVES_COUNT*MAX_MAPPERS))
	local dataSize=`echo "scale=0; $CACHE_FLUSHING_DATA_SIZE * $TERAGEN_GIGA_MULTIPLIER / 1" | bc` # DON'T REMOVE THAT "/ 1" ! the "scale=0" won't truncate the number without it 
	local cmd="$EXEC_JOB $HADOOP_EXAMPLES_JAR_EXP teragen -D${DFS_REPLICATIONS}=1 -D${MAP_TASKS}=${nmaps} ${dataSize}"
	bash $SCRIPTS_DIR/generateData.sh "$cmd" "$CACHE_FLUSHING_DATA_DIR" "1" "$CACHE_FLUSHING_COUNT" "clear"
    errorHandler $? "Teragen Failed"
}

DFSIOclear(){
	echo "$echoPrefix: TestDFSIO clear"
	eval "$EXEC_JOB $HADOOP_TEST_JAR_EXP $CMD_PROGRAM -clear"
}

DFSIOwrite(){
	echo "$echoPrefix: TestDFSIO write"
	executionFolder=${TEST_DIR_PREFIX}${sample}${TEST_DFSIO_WRITE_DIR_SUFFIX}
	collectionTempDir=$TMP_DIR/$executionFolder
	collectionDestDir=$currentLogsDir/$executionFolder
	mkdir -p $collectionTempDir
	execLogExports=$collectionTempDir/execLogExports.sh
	export USER_CMD="$EXEC_JOB $HADOOP_TEST_JAR_EXP $CMD_PROGRAM $CMD_UDA_ENABLE $CMD_D_PARAMS $CMD_TEST_DFSIO_PARAMS -write"
	bash $SCRIPTS_DIR/mr-dstatExcel.sh $collectionTempDir $collectionDestDir $executionFolder $execLogExports $TEST_DFSIO_WRITE_DIR
	#*****************#
	source $collectionDestDir/execLogExports.sh
	if [[ -n $SETUP_FAILURE ]];then
		exit $EEC2
	fi
	#*****************#
}

DFSIOread(){
	echo "$echoPrefix: TestDFSIO read"
	executionFolder=${TEST_DIR_PREFIX}${sample}${TEST_DFSIO_READ_DIR_SUFFIX}
	collectionTempDir=$TMP_DIR/$executionFolder
	collectionDestDir=$currentLogsDir/$executionFolder
	mkdir -p $collectionTempDir
	execLogExports=$collectionTempDir/execLogExports.sh
	export USER_CMD="$EXEC_JOB $HADOOP_TEST_JAR_EXP $CMD_PROGRAM $CMD_UDA_ENABLE $CMD_D_PARAMS $COMPRESSION_TEST_LEVEL $CMD_TEST_DFSIO_PARAMS -read"
	bash $SCRIPTS_DIR/mr-dstatExcel.sh $collectionTempDir $collectionDestDir $executionFolder $execLogExports $TEST_DFSIO_READ_DIR
}

currentLogsDir=$1

echoPrefix=`eval $ECHO_PATTERN`
mkdir -p $currentLogsDir
cd $MY_HADOOP_HOME

for sample in `seq 1 $NSAMPLES` ; do
	if ((`$HADOOP_FS -ls / | grep -c $TEST_DFSIO_DIR` == 1));then
		DFSIOclear
	fi
	DFSIOwrite
	if (($CACHE_FLUHSING == 1));then
		eval sudo $EXEC_SLAVES $SCRIPTS_DIR/cache_flush.sh
		ramSizeChaceFlushing
		eval sudo $EXEC_SLAVES $SCRIPTS_DIR/cache_flush.sh
	fi
	DFSIOread
	DFSIOclear
done
