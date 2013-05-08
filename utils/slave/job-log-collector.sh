#!/bin/sh
#
# Copyright (C) 2012 Mellanox Technologies
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at:
#  
# http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
# either express or implied. See the License for the specific language 
# governing permissions and  limitations under the License.
#
# 
# Written by Avner BenHanoch
# Date: 2013-04-25

RM=/bin/rm

MASTER=${1:-rswbob12}
JOB=${2:-201304091252_0008}
CONTEXT=${3:-1000}
LOGDIR=${4:-.}
JPS=${5:-jps}


#VERBOSE="--verbose"
VERBOSE=""

TRACKER=tracker
if ! ls $LOGDIR/*$TRACKER* > /dev/null 2> /dev/null
then
	TRACKER=TRACKER # try "TRACKER" instead of "tracker", (for example, in case cloudera)
fi

ENDCHAR=x # Don't use g neither t, because they end *.log and *.out
SUFFIX=.job_$JOB.snippet.$ENDCHAR

FILES="$LOGDIR/*$TRACKER*"
SAFEFILES=$FILES[^${ENDCHAR}0-9]
OUTFILES=$FILES$SUFFIX
$RM -f $OUTFILES

SLAVEDIR=`dirname $0`
if ls $SAFEFILES  > /dev/null 2> /dev/null
then
	$SLAVEDIR/job-log-collector.awk JOB=$JOB CONTEXT=$CONTEXT SUFFIX=$SUFFIX $SAFEFILES
fi

TARFILE=/tmp/`hostname`_job_$JOB.all-logs.tar
$RM -f $TARFILE*

STRIP=`echo $LOGDIR | sed s/^.//` # without leading /, because tar doesn't see it


# 1st create an empty archive, than append all files to it - this way tar will survive even if one componenet does not exist
echo "creating: $TARFILE with all job's log files..."
tar  --create -f $TARFILE --files-from=/dev/null

tar $VERBOSE --dereference -C $LOGDIR --transform "s,^$STRIP,$JOB/`hostname`," --transform "s,.$ENDCHAR$,," --append -f $TARFILE  $LOGDIR/*$JOB* 2>&1 | grep -v "Removing leading" # takes the xml file, plus any snippet we created

#echo $TARFLAGS

#tar -C $LOGDIR --dereference --transform "s,^$STRIP,$JOB/`hostname`," --append -f $TARFILE  $LOGDIR/userlogs/*$JOB* 2>&1 | grep -v "Removing leading" 
tar $VERBOSE --dereference --transform "s,^$STRIP,$JOB/`hostname`," --append -f $TARFILE  $LOGDIR/userlogs/*$JOB* 2>&1 | grep -v "Removing leading" |  grep -v "File removed before we read it" #temp, remove noise in NFS tests
tar $VERBOSE --dereference --transform "s,^$STRIP,$JOB/`hostname`," --append -f $TARFILE  $LOGDIR/*$TRACKER*.out --exclude  $LOGDIR/*$TRACKER*.log.out 2> /dev/null || true
tar $VERBOSE --dereference --transform "s,^$STRIP,$JOB/`hostname`," --append -f $TARFILE  $LOGDIR/*$TRACKER*.out*[^${ENDCHAR}0-9] --exclude $LOGDIR/*$TRACKER*.log.out*[^${ENDCHAR}0-9]  2> /dev/null || true


# TODO - check that $P is valid !
# in case TaskTracker is from Cloudera CM => take also its cwd with conf and stdout/stderr
P=`$JPS -m | grep TaskTracker | awk '{print $1}'`
if [ -n "$P" ] && grep --silent "Autogenerated by Cloudera CM" /proc/$P/cwd/mapred-site.xml 2> /dev/null
then
	tar $VERBOSE --dereference --transform "s,^,$JOB/`hostname`-tasktracker-," --append -f $TARFILE /proc/$P/cwd/ 2> /dev/null || true
fi

# in case JobTracker is from Cloudera CM => take also its cwd with conf and stdout/stderr
P=`$JPS -m | grep JobTracker | awk '{print $1}'`
if [ -n "$P" ] && grep --silent "Autogenerated by Cloudera CM" /proc/$P/cwd/mapred-site.xml 2> /dev/null
then
	tar $VERBOSE --dereference --transform "s,^,$JOB/`hostname`-job-tracker-," --append -f $TARFILE /proc/$P/cwd/ 2> /dev/null || true
fi


$RM -f $OUTFILES

if [ "`hostname`" != "$MASTER" ]; then
	scp $TARFILE $MASTER:/tmp/ && echo "$TARFILE was scp'ed to $MASTER:/tmp/"
	$RM -f $TARFILE
fi
      
