/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Math;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/* below are added for rdma project */
import java.net.Socket;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.util.Vector;
import java.util.StringTokenizer;
import org.apache.hadoop.io.Text;
/* above are added for rdma project */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumFileSystem;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapred.IFile.*;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.mapred.SortedRanges.SkipRangeIterator;
import org.apache.hadoop.mapred.TaskTracker.TaskInProgress;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.Updater;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;

/** A Reduce task. */
class ReduceTask extends Task {

  static {                                        // register a ctor
    WritableFactories.setFactory
      (ReduceTask.class,
       new WritableFactory() {
         public Writable newInstance() { return new ReduceTask(); }
       });
  }
  


  private static final Log LOG = LogFactory.getLog(ReduceTask.class.getName());
  private int numMaps;
  private ReduceCopier reduceCopier;

  private CompressionCodec codec;


  { 
    getProgress().setStatus("reduce"); 
    setPhase(TaskStatus.Phase.SHUFFLE);        // phase to start with 
  }

  private Progress copyPhase;
  private Progress sortPhase;
  private Progress reducePhase;
  private Counters.Counter reduceShuffleBytes = 
    getCounters().findCounter(Counter.REDUCE_SHUFFLE_BYTES);
  private Counters.Counter reduceInputKeyCounter = 
    getCounters().findCounter(Counter.REDUCE_INPUT_GROUPS);
  private Counters.Counter reduceInputValueCounter = 
    getCounters().findCounter(Counter.REDUCE_INPUT_RECORDS);
  private Counters.Counter reduceOutputCounter = 
    getCounters().findCounter(Counter.REDUCE_OUTPUT_RECORDS);
  private Counters.Counter reduceCombineOutputCounter =
    getCounters().findCounter(Counter.COMBINE_OUTPUT_RECORDS);

  // A custom comparator for map output files. Here the ordering is determined
  // by the file's size and path. In case of files with same size and different
  // file paths, the first parameter is considered smaller than the second one.
  // In case of files with same size and path are considered equal.
  private Comparator<FileStatus> mapOutputFileComparator = 
    new Comparator<FileStatus>() {
      public int compare(FileStatus a, FileStatus b) {
        if (a.getLen() < b.getLen())
          return -1;
        else if (a.getLen() == b.getLen())
          if (a.getPath().toString().equals(b.getPath().toString()))
            return 0;
          else
            return -1; 
        else
          return 1;
      }
  };
  
  // A sorted set for keeping a set of map output files on disk
  private final SortedSet<FileStatus> mapOutputFilesOnDisk = 
    new TreeSet<FileStatus>(mapOutputFileComparator);

  public ReduceTask() {
    super();
  }

  public ReduceTask(String jobFile, TaskAttemptID taskId,
                    int partition, int numMaps) {
    super(jobFile, taskId, partition);
    this.numMaps = numMaps;
  }
  
  private CompressionCodec initCodec() {
    // check if map-outputs are to be compressed
    if (conf.getCompressMapOutput()) {
      Class<? extends CompressionCodec> codecClass =
        conf.getMapOutputCompressorClass(DefaultCodec.class);
      return ReflectionUtils.newInstance(codecClass, conf);
    } 

    return null;
  }

  @Override
  public TaskRunner createRunner(TaskTracker tracker, TaskInProgress tip) 
  throws IOException {
    return new ReduceTaskRunner(tip, tracker, this.conf);
  }

  @Override
  public boolean isMapTask() {
    return false;
  }

  public int getNumMaps() { return numMaps; }
  
  /**
   * Localize the given JobConf to be specific for this task.
   */
  @Override
  public void localizeConfiguration(JobConf conf) throws IOException {
    super.localizeConfiguration(conf);
    conf.setNumMapTasks(numMaps);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);

    out.writeInt(numMaps);                        // write the number of maps
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);

    numMaps = in.readInt();
  }
  
  // Get the input files for the reducer.
  private Path[] getMapFiles(FileSystem fs, boolean isLocal) 
  throws IOException {
    List<Path> fileList = new ArrayList<Path>();
    if (isLocal) {
      // for local jobs
      for(int i = 0; i < numMaps; ++i) {
        fileList.add(mapOutputFile.getInputFile(i, getTaskID()));
      }
    } else {
      // for non local jobs
      for (FileStatus filestatus : mapOutputFilesOnDisk) {
        fileList.add(filestatus.getPath());
      }
    }
    return fileList.toArray(new Path[0]);
  }

  private class ReduceValuesIterator<KEY,VALUE> 
          extends ValuesIterator<KEY,VALUE> {
    public ReduceValuesIterator (RawKeyValueIterator in,
                                 RawComparator<KEY> comparator, 
                                 Class<KEY> keyClass,
                                 Class<VALUE> valClass,
                                 Configuration conf, Progressable reporter)
      throws IOException {
      super(in, comparator, keyClass, valClass, conf, reporter);
    }

    @Override
    public VALUE next() {
      reduceInputValueCounter.increment(1);
      return moveToNext();
    }
    
    protected VALUE moveToNext() {
      return super.next();
    }
    
    public void informReduceProgress() {
      reducePhase.set(super.in.getProgress().get()); // update progress
      reporter.progress();
    }
  }

  private class SkippingReduceValuesIterator<KEY,VALUE> 
     extends ReduceValuesIterator<KEY,VALUE> {
     private SkipRangeIterator skipIt;
     private TaskUmbilicalProtocol umbilical;
     private Counters.Counter skipGroupCounter;
     private Counters.Counter skipRecCounter;
     private long grpIndex = -1;
     private Class<KEY> keyClass;
     private Class<VALUE> valClass;
     private SequenceFile.Writer skipWriter;
     private boolean toWriteSkipRecs;
     private boolean hasNext;
     private TaskReporter reporter;
     
     public SkippingReduceValuesIterator(RawKeyValueIterator in,
         RawComparator<KEY> comparator, Class<KEY> keyClass,
         Class<VALUE> valClass, Configuration conf, TaskReporter reporter,
         TaskUmbilicalProtocol umbilical) throws IOException {
       super(in, comparator, keyClass, valClass, conf, reporter);
       this.umbilical = umbilical;
       this.skipGroupCounter = 
         reporter.getCounter(Counter.REDUCE_SKIPPED_GROUPS);
       this.skipRecCounter = 
         reporter.getCounter(Counter.REDUCE_SKIPPED_RECORDS);
       this.toWriteSkipRecs = toWriteSkipRecs() &&  
         SkipBadRecords.getSkipOutputPath(conf)!=null;
       this.keyClass = keyClass;
       this.valClass = valClass;
       this.reporter = reporter;
       skipIt = getSkipRanges().skipRangeIterator();
       mayBeSkip();
     }
     
     void nextKey() throws IOException {
       super.nextKey();
       mayBeSkip();
     }
     
     boolean more() { 
       return super.more() && hasNext; 
     }
     
     private void mayBeSkip() throws IOException {
       hasNext = skipIt.hasNext();
       if(!hasNext) {
         LOG.warn("Further groups got skipped.");
         return;
       }
       grpIndex++;
       long nextGrpIndex = skipIt.next();
       long skip = 0;
       long skipRec = 0;
       while(grpIndex<nextGrpIndex && super.more()) {
         while (hasNext()) {
           VALUE value = moveToNext();
           if(toWriteSkipRecs) {
             writeSkippedRec(getKey(), value);
           }
           skipRec++;
         }
         super.nextKey();
         grpIndex++;
         skip++;
       }
       
       //close the skip writer once all the ranges are skipped
       if(skip>0 && skipIt.skippedAllRanges() && skipWriter!=null) {
         skipWriter.close();
       }
       skipGroupCounter.increment(skip);
       skipRecCounter.increment(skipRec);
       reportNextRecordRange(umbilical, grpIndex);
     }
     
     @SuppressWarnings("unchecked")
     private void writeSkippedRec(KEY key, VALUE value) throws IOException{
       if(skipWriter==null) {
         Path skipDir = SkipBadRecords.getSkipOutputPath(conf);
         Path skipFile = new Path(skipDir, getTaskID().toString());
         skipWriter = SequenceFile.createWriter(
               skipFile.getFileSystem(conf), conf, skipFile,
               keyClass, valClass, 
               CompressionType.BLOCK, reporter);
       }
       skipWriter.append(key, value);
     }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void run(JobConf job, final TaskUmbilicalProtocol umbilical)
    throws IOException, InterruptedException, ClassNotFoundException {
 
    /* for rdma measurement */
    long reduce_task_start = System.currentTimeMillis();

    this.umbilical = umbilical;
    job.setBoolean("mapred.skip.on", isSkipping());

    if (isMapOrReduce()) {
      copyPhase = getProgress().addPhase("copy");
      sortPhase  = getProgress().addPhase("sort");
      reducePhase = getProgress().addPhase("reduce");
    }
    // start thread that will handle communication with parent
    TaskReporter reporter = new TaskReporter(getProgress(), umbilical);
    reporter.startCommunicationThread();
    boolean useNewApi = job.getUseNewReducer();
    initialize(job, getJobID(), reporter, useNewApi);

    // check if it is a cleanupJobTask
    if (jobCleanup) {
      runJobCleanupTask(umbilical, reporter);
      return;
    }
    if (jobSetup) {
      runJobSetupTask(umbilical, reporter);
      return;
    }
    if (taskCleanup) {
      runTaskCleanupTask(umbilical, reporter);
      return;
    }
    
    // Initialize the codec
    codec = initCodec();

    /* for rdma measurement */
    long shuffle_merge_start = System.currentTimeMillis();

    boolean isLocal = "local".equals(job.get("mapred.job.tracker", "local"));
    if (!isLocal) {
      reduceCopier = new ReduceCopier(umbilical, job, reporter);
      if (!reduceCopier.fetchOutputs()) {
        if(reduceCopier.mergeThrowable instanceof FSError) {
          throw (FSError)reduceCopier.mergeThrowable;
        }
        throw new IOException("Task: " + getTaskID() + 
            " - The reduce copier failed", reduceCopier.mergeThrowable);
      }
    }
    copyPhase.complete();                         // copy is already complete
    setPhase(TaskStatus.Phase.SORT);
    statusUpdate(umbilical);

    /* for rdma measurement */
    long final_merge_start = System.currentTimeMillis();

    final FileSystem rfs = FileSystem.getLocal(job).getRaw();
    RawKeyValueIterator rIter = isLocal
      ? Merger.merge(job, rfs, job.getMapOutputKeyClass(),
          job.getMapOutputValueClass(), codec, getMapFiles(rfs, true),
          !conf.getKeepFailedTaskFiles(), job.getInt("io.sort.factor", 100),
          new Path(getTaskID().toString()), job.getOutputKeyComparator(),
          reporter, spilledRecordsCounter, null)
      : reduceCopier.createKVIterator(job, rfs, reporter);
        
    // free up the data structures
    mapOutputFilesOnDisk.clear();
 
    sortPhase.complete();                         // sort is complete
  
    /* for rdma measurement  */
    long final_merge_end = System.currentTimeMillis();
    long shuffle_merge_end = System.currentTimeMillis();
    long reduce_func_start = System.currentTimeMillis();

    setPhase(TaskStatus.Phase.REDUCE); 
    statusUpdate(umbilical);
    Class keyClass = job.getMapOutputKeyClass();
    Class valueClass = job.getMapOutputValueClass();
    RawComparator comparator = job.getOutputValueGroupingComparator();

    if (useNewApi) {
      runNewReducer(job, umbilical, reporter, rIter, comparator, 
                    keyClass, valueClass);
    } else {
      runOldReducer(job, umbilical, reporter, rIter, comparator, 
                    keyClass, valueClass);
    }
    
    done(umbilical, reporter);
    
    reduceCopier.close();

    /* for rdma measurement */
    long reduce_func_end = System.currentTimeMillis();
    long reduce_task_end = System.currentTimeMillis();
    long final_merge_time = final_merge_end - final_merge_start;
    long reduce_task_time = reduce_task_end - reduce_task_start;
    long reduce_func_time = reduce_func_end - reduce_func_start;
    long shuffle_merge_time = shuffle_merge_end - shuffle_merge_start;
    double shuffle_merge_prec = ((double)shuffle_merge_time/(double)reduce_task_time);
    double reduce_func_prec   = ((double)reduce_func_time  /(double)reduce_task_time);
    LOG.info("RDMA-MEASUREMENT: Reduce Task Start Time   [ " + reduce_task_start   + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Reduce Task End Time     [ " + reduce_task_end     + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Reduce Task Time         [ " + reduce_task_time    + " ]");
    LOG.info("RDMA-MEASUREMENT: Shuffle Merge Start Time [ " + shuffle_merge_start + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Shuffle Merge End Time   [ " + shuffle_merge_end   + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Shuffle Merge Time       [ " + shuffle_merge_time  + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Reudce Func Start Time   [ " + reduce_func_start   + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Reduce Func End Time     [ " + reduce_func_end     + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Reduce Func Time         [ " + reduce_func_time    + " ]"); 
    LOG.info("RDMA-MEASUREMENT: Final Merge Time         [ " + final_merge_time    + " ]"); 
  }

  @SuppressWarnings("unchecked")
  private <INKEY,INVALUE,OUTKEY,OUTVALUE>
  void runOldReducer(JobConf job,
                     TaskUmbilicalProtocol umbilical,
                     final TaskReporter reporter,
                     RawKeyValueIterator rIter,
                     RawComparator<INKEY> comparator,
                     Class<INKEY> keyClass,
                     Class<INVALUE> valueClass) throws IOException {
    Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE> reducer = 
      ReflectionUtils.newInstance(job.getReducerClass(), job);
    // make output collector
    String finalName = getOutputName(getPartition());

    FileSystem fs = FileSystem.get(job);

    final RecordWriter<OUTKEY,OUTVALUE> out = 
      job.getOutputFormat().getRecordWriter(fs, job, finalName, reporter);  
    
    OutputCollector<OUTKEY,OUTVALUE> collector = 
      new OutputCollector<OUTKEY,OUTVALUE>() {
        public void collect(OUTKEY key, OUTVALUE value)
          throws IOException {
          out.write(key, value);
          reduceOutputCounter.increment(1);
          // indicate that progress update needs to be sent
          reporter.progress();
        }
      };
    
    // apply reduce function
    try {
      //increment processed counter only if skipping feature is enabled
      boolean incrProcCount = SkipBadRecords.getReducerMaxSkipGroups(job)>0 &&
        SkipBadRecords.getAutoIncrReducerProcCount(job);
      
      ReduceValuesIterator<INKEY,INVALUE> values = isSkipping() ? 
          new SkippingReduceValuesIterator<INKEY,INVALUE>(rIter, 
              comparator, keyClass, valueClass, 
              job, reporter, umbilical) :
          new ReduceValuesIterator<INKEY,INVALUE>(rIter, 
          job.getOutputValueGroupingComparator(), keyClass, valueClass, 
          job, reporter);
      values.informReduceProgress();
      while (values.more()) {
        reduceInputKeyCounter.increment(1);
        reducer.reduce(values.getKey(), values, collector, reporter);
        if(incrProcCount) {
          reporter.incrCounter(SkipBadRecords.COUNTER_GROUP, 
              SkipBadRecords.COUNTER_REDUCE_PROCESSED_GROUPS, 1);
        }
        values.nextKey();
        values.informReduceProgress();
      }

      //Clean up: repeated in catch block below
      reducer.close();
      out.close(reporter);
      //End of clean up.
    } catch (IOException ioe) {
      try {
        reducer.close();
      } catch (IOException ignored) {}
        
      try {
        out.close(reporter);
      } catch (IOException ignored) {}
      
      throw ioe;
    }
  }

  static class NewTrackingRecordWriter<K,V> 
      extends org.apache.hadoop.mapreduce.RecordWriter<K,V> {
    private final org.apache.hadoop.mapreduce.RecordWriter<K,V> real;
    private final org.apache.hadoop.mapreduce.Counter outputRecordCounter;
  
    NewTrackingRecordWriter(org.apache.hadoop.mapreduce.RecordWriter<K,V> real,
                            org.apache.hadoop.mapreduce.Counter recordCounter) {
      this.real = real;
      this.outputRecordCounter = recordCounter;
    }

    @Override
    public void close(TaskAttemptContext context) throws IOException,
    InterruptedException {
      real.close(context);
    }

    @Override
    public void write(K key, V value) throws IOException, InterruptedException {
      real.write(key,value);
      outputRecordCounter.increment(1);
    }
  }

  @SuppressWarnings("unchecked")
  private <INKEY,INVALUE,OUTKEY,OUTVALUE>
  void runNewReducer(JobConf job,
                     final TaskUmbilicalProtocol umbilical,
                     final TaskReporter reporter,
                     RawKeyValueIterator rIter,
                     RawComparator<INKEY> comparator,
                     Class<INKEY> keyClass,
                     Class<INVALUE> valueClass
                     ) throws IOException,InterruptedException, 
                              ClassNotFoundException {
    // wrap value iterator to report progress.
    final RawKeyValueIterator rawIter = rIter;
    rIter = new RawKeyValueIterator() {
      public void close() throws IOException {
        rawIter.close();
      }
      public DataInputBuffer getKey() throws IOException {
        return rawIter.getKey();
      }
      public Progress getProgress() {
        return rawIter.getProgress();
      }
      public DataInputBuffer getValue() throws IOException {
        return rawIter.getValue();
      }
      public boolean next() throws IOException {
        boolean ret = rawIter.next();
        reducePhase.set(rawIter.getProgress().get());
        reporter.progress();
        return ret;
      }
    };
    // make a task context so we can get the classes
    org.apache.hadoop.mapreduce.TaskAttemptContext taskContext =
      new org.apache.hadoop.mapreduce.TaskAttemptContext(job, getTaskID());
    // make a reducer
    org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE> reducer =
      (org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE>)
        ReflectionUtils.newInstance(taskContext.getReducerClass(), job);
    org.apache.hadoop.mapreduce.RecordWriter<OUTKEY,OUTVALUE> output =
      (org.apache.hadoop.mapreduce.RecordWriter<OUTKEY,OUTVALUE>)
        outputFormat.getRecordWriter(taskContext);
     org.apache.hadoop.mapreduce.RecordWriter<OUTKEY,OUTVALUE> trackedRW = 
       new NewTrackingRecordWriter<OUTKEY, OUTVALUE>(output, reduceOutputCounter);
    job.setBoolean("mapred.skip.on", isSkipping());
    org.apache.hadoop.mapreduce.Reducer.Context 
         reducerContext = createReduceContext(reducer, job, getTaskID(),
                                               rIter, reduceInputKeyCounter,
                                               reduceInputValueCounter, 
                                               trackedRW, committer,
                                               reporter, comparator, keyClass,
                                               valueClass);
    reducer.run(reducerContext);
    output.close(reducerContext);
  }

  class ReduceCopier<K, V> implements MRConstants {

    /** Reference to the umbilical object */
    private TaskUmbilicalProtocol umbilical;
    private final TaskReporter reporter;
    
    /** Reference to the task object */
    
    /** Number of ms before timing out a copy */
    private static final int STALLED_COPY_TIMEOUT = 3 * 60 * 1000;
    
    /** Max events to fetch in one go from the tasktracker */
    private static final int MAX_EVENTS_TO_FETCH = 10000;

    /**
     * our reduce task instance
     */
    private ReduceTask reduceTask;
    
    /**
     * the list of map outputs currently being copied
     */
    private List<MapOutputLocation> scheduledCopies;
    
    /**
     *  the results of dispatched copy attempts
     */
    private List<CopyResult> copyResults;
    
    /**
     *  the number of outputs to copy in parallel
     */
    private int numCopiers;
    
    /**
     *  a number that is set to the max #fetches we'd schedule and then
     *  pause the schduling
     */
    private int maxInFlight;
    
    /**
     * the amount of time spent on fetching one map output before considering 
     * it as failed and notifying the jobtracker about it.
     */
    private int maxBackoff;
    
    /**
     * busy hosts from which copies are being backed off
     * Map of host -> next contact time
     */
    private Map<String, Long> penaltyBox;
    
    /**
     * the set of unique hosts from which we are copying
     */
    private Set<String> uniqueHosts;
    
    /**
     * A reference to the RamManager for writing the map outputs to.
     */
    
    private ShuffleRamManager ramManager;
    
    /**
     * A reference to the local file system for writing the map outputs to.
     */
    private FileSystem localFileSys;

    private FileSystem rfs;
    /**
     * Number of files to merge at a time
     */
    private int ioSortFactor;
    
    /**
     * A reference to the throwable object (if merge throws an exception)
     */
    private volatile Throwable mergeThrowable;
    
    /** 
     * A flag to indicate when to exit localFS merge
     */
    private volatile boolean exitLocalFSMerge = false;

    /** 
     * A flag to indicate when to exit getMapEvents thread 
     */
    private volatile boolean exitGetMapEvents = false;
    
    /**
     * When we accumulate maxInMemOutputs number of files in ram, we merge/spill
     */
    private final int maxInMemOutputs;

    /**
     * Usage threshold for in-memory output accumulation.
     */
    private final float maxInMemCopyPer;

    /**
     * Maximum memory usage of map outputs to merge from memory into
     * the reduce, in bytes.
     */
    private final long maxInMemReduce;

    /**
     * The threads for fetching the files.
     */
    private List<MapOutputCopier> copiers = null;
 
    /**
     * The object for metrics reporting.
     */
    private ShuffleClientMetrics shuffleClientMetrics = null;
    
    /**
     * the minimum interval between tasktracker polls
     */
    private static final long MIN_POLL_INTERVAL = 1000;
    
    /**
     * a list of map output locations for fetch retrials 
     */
    private List<MapOutputLocation> retryFetches =
      new ArrayList<MapOutputLocation>();
    
    /** 
     * The set of required map outputs
     */
    private Set <TaskID> copiedMapOutputs = 
      Collections.synchronizedSet(new TreeSet<TaskID>());
    
    /** 
     * The set of obsolete map taskids.
     */
    private Set <TaskAttemptID> obsoleteMapIds = 
      Collections.synchronizedSet(new TreeSet<TaskAttemptID>());
    
    private Random random = null;

    /**
     * the max of all the map completion times
     */
    private int maxMapRuntime;
    
    /**
     * Maximum number of fetch-retries per-map.
     */
    private volatile int maxFetchRetriesPerMap;
    
    /**
     * Combiner runner, if a combiner is needed
     */
    private CombinerRunner combinerRunner;

    /**
     * Resettable collector used for combine.
     */
    private CombineOutputCollector combineCollector = null;

    /**
     * Maximum percent of failed fetch attempt before killing the reduce task.
     */
    private static final float MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT = 0.5f;

    /**
     * Minimum percent of progress required to keep the reduce alive.
     */
    private static final float MIN_REQUIRED_PROGRESS_PERCENT = 0.5f;

    /**
     * Maximum percent of shuffle execution time required to keep the reducer alive.
     */
    private static final float MAX_ALLOWED_STALL_TIME_PERCENT = 0.5f;
    
    /**
     * Minimum number of map fetch retries.
     */
    private static final int MIN_FETCH_RETRIES_PER_MAP = 2;

    /**
     * Maximum no. of unique maps from which we failed to fetch map-outputs
     * even after {@link #maxFetchRetriesPerMap} retries; after this the
     * reduce task is failed.
     */
    private int maxFailedUniqueFetches = 5;

    /**
     * The maps from which we fail to fetch map-outputs 
     * even after {@link #maxFetchRetriesPerMap} retries.
     */
    Set<TaskID> fetchFailedMaps = new TreeSet<TaskID>(); 
    
    /**
     * A map of taskId -> no. of failed fetches
     */
    Map<TaskAttemptID, Integer> mapTaskToFailedFetchesMap = 
      new HashMap<TaskAttemptID, Integer>();    

    /**
     * Initial backoff interval (milliseconds)
     */
    private static final int BACKOFF_INIT = 4000; 
    
    /**
     * The interval for logging in the shuffle
     */
    private static final int MIN_LOG_TIME = 60000;

    /** 
     * List of in-memory map-outputs.
     */
    private final List<MapOutput> mapOutputsFilesInMemory =
      Collections.synchronizedList(new LinkedList<MapOutput>());
    
    /**
     * The map for (Hosts, List of MapIds from this Host) maintaining
     * map output locations
     */
    private final Map<String, List<MapOutputLocation>> mapLocations = 
      new ConcurrentHashMap<String, List<MapOutputLocation>>();
    
    /**
     * This is the channel used to transfer the data between RDMA C++
     * and Hadoop ReduceTask
     */
    private DataSocket rdmaChannel;

    /**
     * mapred.rdma.setting represents how users uses rdma
     * 0: disable rdma (everything keep original status)
     * 1: use rdma with rdma-merger (only at the final stage, 
     *    return the merged segment back to hadoop)
     * 2: use rdma with hadoop-merger (whenever one map is fetched
          by rdma, return it back).
     */
    private int rdmaSetting;

    /**
     * This class contains the methods that should be used for metrics-reporting
     * the specific metrics for shuffle. This class actually reports the
     * metrics for the shuffle client (the ReduceTask), and hence the name
     * ShuffleClientMetrics.
     */
    class ShuffleClientMetrics implements Updater {
      private MetricsRecord shuffleMetrics = null;
      private int numFailedFetches = 0;
      private int numSuccessFetches = 0;
      private long numBytes = 0;
      private int numThreadsBusy = 0;
      ShuffleClientMetrics(JobConf conf) {
        MetricsContext metricsContext = MetricsUtil.getContext("mapred");
        this.shuffleMetrics = 
          MetricsUtil.createRecord(metricsContext, "shuffleInput");
        this.shuffleMetrics.setTag("user", conf.getUser());
        this.shuffleMetrics.setTag("jobName", conf.getJobName());
        this.shuffleMetrics.setTag("jobId", ReduceTask.this.getJobID().toString());
        this.shuffleMetrics.setTag("taskId", getTaskID().toString());
        this.shuffleMetrics.setTag("sessionId", conf.getSessionId());
        metricsContext.registerUpdater(this);
      }
      public synchronized void inputBytes(long numBytes) {
        this.numBytes += numBytes;
      }
      public synchronized void failedFetch() {
        ++numFailedFetches;
      }
      public synchronized void successFetch() {
        ++numSuccessFetches;
      }
      public synchronized void threadBusy() {
        ++numThreadsBusy;
      }
      public synchronized void threadFree() {
        --numThreadsBusy;
      }
      public void doUpdates(MetricsContext unused) {
        synchronized (this) {
          shuffleMetrics.incrMetric("shuffle_input_bytes", numBytes);
          shuffleMetrics.incrMetric("shuffle_failed_fetches", 
                                    numFailedFetches);
          shuffleMetrics.incrMetric("shuffle_success_fetches", 
                                    numSuccessFetches);
          if (numCopiers != 0) {
            shuffleMetrics.setMetric("shuffle_fetchers_busy_percent",
                100*((float)numThreadsBusy/numCopiers));
          } else {
            shuffleMetrics.setMetric("shuffle_fetchers_busy_percent", 0);
          }
          numBytes = 0;
          numSuccessFetches = 0;
          numFailedFetches = 0;
        }
        shuffleMetrics.update();
      }
    }

    /** Represents the result of an attempt to copy a map output */
    private class CopyResult {
      
      // the map output location against which a copy attempt was made
      private final MapOutputLocation loc;
      
      // the size of the file copied, -1 if the transfer failed
      private final long size;
      
      //a flag signifying whether a copy result is obsolete
      private static final int OBSOLETE = -2;
      
      CopyResult(MapOutputLocation loc, long size) {
        this.loc = loc;
        this.size = size;
      }
      
      public boolean getSuccess() { return size >= 0; }
      public boolean isObsolete() { 
        return size == OBSOLETE;
      }
      public long getSize() { return size; }
      public String getHost() { return loc.getHost(); }
      public MapOutputLocation getLocation() { return loc; }
    }
    
    private int nextMapOutputCopierId = 0;
    
    /**
     * Abstraction to track a map-output.
     */
    private class MapOutputLocation {
      TaskAttemptID taskAttemptId;
      TaskID taskId;
      String ttHost;
      URL taskOutput;
      
      public MapOutputLocation(TaskAttemptID taskAttemptId, 
                               String ttHost, URL taskOutput) {
        this.taskAttemptId = taskAttemptId;
        this.taskId = this.taskAttemptId.getTaskID();
        this.ttHost = ttHost;
        this.taskOutput = taskOutput;
      }
      
      public TaskAttemptID getTaskAttemptId() {
        return taskAttemptId;
      }
      
      public TaskID getTaskId() {
        return taskId;
      }
      
      public String getHost() {
        return ttHost;
      }
      
      public URL getOutputLocation() {
        return taskOutput;
      }
    }
    
    /** Describes the output of a map; could either be on disk or in-memory. */
    private class MapOutput {
      final TaskID mapId;
      final TaskAttemptID mapAttemptId;
      
      final Path file;
      final Configuration conf;
      
      byte[] data;
      final boolean inMemory;
      long compressedSize;
      
      public MapOutput(TaskID mapId, TaskAttemptID mapAttemptId, 
                       Configuration conf, Path file, long size) {
        this.mapId = mapId;
        this.mapAttemptId = mapAttemptId;
        
        this.conf = conf;
        this.file = file;
        this.compressedSize = size;
        
        this.data = null;
        
        this.inMemory = false;
      }
      
      public MapOutput(TaskID mapId, TaskAttemptID mapAttemptId, byte[] data, int compressedLength) {
        this.mapId = mapId;
        this.mapAttemptId = mapAttemptId;
        
        this.file = null;
        this.conf = null;
        
        this.data = data;
        this.compressedSize = compressedLength;
        
        this.inMemory = true;
      }
      
      public void discard() throws IOException {
        if (inMemory) {
          data = null;
        } else {
          FileSystem fs = file.getFileSystem(conf);
          fs.delete(file, true);
        }
      }
    }
    
    class ShuffleRamManager implements RamManager {
      /* Maximum percentage of the in-memory limit that a single shuffle can 
       * consume*/ 
      private static final float MAX_SINGLE_SHUFFLE_SEGMENT_FRACTION = 0.25f;
      
      /* Maximum percentage of shuffle-threads which can be stalled 
       * simultaneously after which a merge is triggered. */ 
      private static final float MAX_STALLED_SHUFFLE_THREADS_FRACTION = 0.75f;
      
      private final long maxSize;
      private final long maxSingleShuffleLimit;
      
      private long size = 0;
      
      private Object dataAvailable = new Object();
      private long fullSize = 0;
      private int numPendingRequests = 0;
      private int numRequiredMapOutputs = 0;
      private int numClosed = 0;
      private boolean closed = false;
      
      public ShuffleRamManager(Configuration conf) throws IOException {
        final float maxInMemCopyUse =
          conf.getFloat("mapred.job.shuffle.input.buffer.percent", 0.70f);
        if (maxInMemCopyUse > 1.0 || maxInMemCopyUse < 0.0) {
          throw new IOException("mapred.job.shuffle.input.buffer.percent" +
                                maxInMemCopyUse);
        }
        // Allow unit tests to fix Runtime memory
        maxSize = (int)(conf.getInt("mapred.job.reduce.total.mem.bytes",
            (int)Math.min(Runtime.getRuntime().maxMemory(), Integer.MAX_VALUE))
          * maxInMemCopyUse);
        maxSingleShuffleLimit = (long)(maxSize * MAX_SINGLE_SHUFFLE_SEGMENT_FRACTION);
        LOG.info("ShuffleRamManager: MemoryLimit=" + maxSize + 
                 ", MaxSingleShuffleLimit=" + maxSingleShuffleLimit);
      }
      
      public synchronized boolean reserve(int requestedSize, InputStream in) 
      throws InterruptedException {
        // Wait till the request can be fulfilled...
        while ((size + requestedSize) > maxSize) {
          
          // Close the input...
          if (in != null) {
            try {
              in.close();
            } catch (IOException ie) {
              LOG.info("Failed to close connection with: " + ie);
            } finally {
              in = null;
            }
          } 

          // Track pending requests
          synchronized (dataAvailable) {
            ++numPendingRequests;
            dataAvailable.notify();
          }

          // Wait for memory to free up
          wait();
          
          // Track pending requests
          synchronized (dataAvailable) {
            --numPendingRequests;
          }
        }
        
        size += requestedSize;
        
        return (in != null);
      }
      
      public synchronized void unreserve(int requestedSize) {
        size -= requestedSize;
        
        synchronized (dataAvailable) {
          fullSize -= requestedSize;
          --numClosed;
        }
        
        // Notify the threads blocked on RamManager.reserve
        notifyAll();
      }
      
      public boolean waitForDataToMerge() throws InterruptedException {
        boolean done = false;
        synchronized (dataAvailable) {
                 // Start in-memory merge if manager has been closed or...
          while (!closed
                 &&
                 // In-memory threshold exceeded and at least two segments
                 // have been fetched
                 (getPercentUsed() < maxInMemCopyPer || numClosed < 2)
                 &&
                 // More than "mapred.inmem.merge.threshold" map outputs
                 // have been fetched into memory
                 (maxInMemOutputs <= 0 || numClosed < maxInMemOutputs)
                 && 
                 // More than MAX... threads are blocked on the RamManager
                 // or the blocked threads are the last map outputs to be
                 // fetched. If numRequiredMapOutputs is zero, either
                 // setNumCopiedMapOutputs has not been called (no map ouputs
                 // have been fetched, so there is nothing to merge) or the
                 // last map outputs being transferred without
                 // contention, so a merge would be premature.
                 (numPendingRequests < 
                      numCopiers*MAX_STALLED_SHUFFLE_THREADS_FRACTION && 
                  (0 == numRequiredMapOutputs ||
                   numPendingRequests < numRequiredMapOutputs))) {
            dataAvailable.wait();
          }
          done = closed;
        }
        return done;
      }
      
      public void closeInMemoryFile(int requestedSize) {
        synchronized (dataAvailable) {
          fullSize += requestedSize;
          ++numClosed;
          dataAvailable.notify();
        }
      }
      
      public void setNumCopiedMapOutputs(int numRequiredMapOutputs) {
        synchronized (dataAvailable) {
          this.numRequiredMapOutputs = numRequiredMapOutputs;
          dataAvailable.notify();
        }
      }
      
      public void close() {
        synchronized (dataAvailable) {
          closed = true;
          LOG.info("Closed ram manager");
          dataAvailable.notify();
        }
      }
      
      private float getPercentUsed() {
        return (float)fullSize/maxSize;
      }

      boolean canFitInMemory(long requestedSize) {
        return (requestedSize < Integer.MAX_VALUE && 
                requestedSize < maxSingleShuffleLimit);
      }
    }

    /** Copies map outputs as they become available */
    private class MapOutputCopier extends Thread {
      // basic/unit connection timeout (in milliseconds)
      private final static int UNIT_CONNECT_TIMEOUT = 30 * 1000;
      // default read timeout (in milliseconds)
      private final static int DEFAULT_READ_TIMEOUT = 3 * 60 * 1000;

      private MapOutputLocation currentLocation = null;
      private int id = nextMapOutputCopierId++;
      private Reporter reporter;
      
      // Decompression of map-outputs
      private CompressionCodec codec = null;
      private Decompressor decompressor = null;
      
      public MapOutputCopier(JobConf job, Reporter reporter) {
        setName("MapOutputCopier " + reduceTask.getTaskID() + "." + id);
        LOG.debug(getName() + " created");
        this.reporter = reporter;
        
        if (job.getCompressMapOutput()) {
          Class<? extends CompressionCodec> codecClass =
            job.getMapOutputCompressorClass(DefaultCodec.class);
          codec = ReflectionUtils.newInstance(codecClass, job);
          decompressor = CodecPool.getDecompressor(codec);
        }
      }
      
      /**
       * Fail the current file that we are fetching
       * @return were we currently fetching?
       */
      public synchronized boolean fail() {
        if (currentLocation != null) {
          finish(-1);
          return true;
        } else {
          return false;
        }
      }
      
      /**
       * Get the current map output location.
       */
      public synchronized MapOutputLocation getLocation() {
        return currentLocation;
      }
      
      private synchronized void start(MapOutputLocation loc) {
        currentLocation = loc;
      }
      
      private synchronized void finish(long size) {
        if (currentLocation != null) {
          LOG.debug(getName() + " finishing " + currentLocation + " =" + size);
          synchronized (copyResults) {
            copyResults.add(new CopyResult(currentLocation, size));
            copyResults.notify();
          }
          currentLocation = null;
        }
      }
      
      /** Loop forever and fetch map outputs as they become available.
       * The thread exits when it is interrupted by {@link ReduceTaskRunner}
       */
      @Override
      public void run() {
        while (true) {        
          try {
            MapOutputLocation loc = null;
            long size = -1;
            
            synchronized (scheduledCopies) {
              while (scheduledCopies.isEmpty()) {
                scheduledCopies.wait();
              }
              loc = scheduledCopies.remove(0);
            }
            
            try {
              shuffleClientMetrics.threadBusy();
              start(loc);
              size = copyOutput(loc);
              shuffleClientMetrics.successFetch();
            } catch (IOException e) {
              LOG.warn(reduceTask.getTaskID() + " copy failed: " +
                       loc.getTaskAttemptId() + " from " + loc.getHost());
              LOG.warn(StringUtils.stringifyException(e));
              shuffleClientMetrics.failedFetch();
              
              // Reset 
              size = -1;
            } finally {
              shuffleClientMetrics.threadFree();
              finish(size);
            }
          } catch (InterruptedException e) { 
            break; // ALL DONE
          } catch (FSError e) {
            LOG.error("Task: " + reduceTask.getTaskID() + " - FSError: " + 
                      StringUtils.stringifyException(e));
            try {
              umbilical.fsError(reduceTask.getTaskID(), e.getMessage());
            } catch (IOException io) {
              LOG.error("Could not notify TT of FSError: " + 
                      StringUtils.stringifyException(io));
            }
          } catch (Throwable th) {
            String msg = getTaskID() + " : Map output copy failure : " 
                         + StringUtils.stringifyException(th);
            reportFatalError(getTaskID(), th, msg);
          }
        }
        
        if (decompressor != null) {
          CodecPool.returnDecompressor(decompressor);
        }
          
      }
      
      /** Copies a a map output from a remote host, via HTTP. 
       * @param currentLocation the map output location to be copied
       * @return the path (fully qualified) of the copied file
       * @throws IOException if there is an error copying the file
       * @throws InterruptedException if the copier should give up
       */
      private long copyOutput(MapOutputLocation loc
                              ) throws IOException, InterruptedException {
        // check if we still need to copy the output from this location
        if (copiedMapOutputs.contains(loc.getTaskId()) || 
            obsoleteMapIds.contains(loc.getTaskAttemptId())) {
          return CopyResult.OBSOLETE;
        } 
 
        // a temp filename. If this file gets created in ramfs, we're fine,
        // else, we will check the localFS to find a suitable final location
        // for this path
        TaskAttemptID reduceId = reduceTask.getTaskID();
        Path filename = new Path("/" + TaskTracker.getIntermediateOutputDir(
                                 reduceId.getJobID().toString(),
                                 reduceId.toString()) 
                                 + "/map_" +
                                 loc.getTaskId().getId() + ".out");
        
        // Copy the map output to a temp file whose name is unique to this attempt 
        Path tmpMapOutput = new Path(filename+"-"+id);
        
        // Copy the map output
        MapOutput mapOutput = getMapOutput(loc, tmpMapOutput,
                                           reduceId.getTaskID().getId());
        if (mapOutput == null) {
          throw new IOException("Failed to fetch map-output for " + 
                                loc.getTaskAttemptId() + " from " + 
                                loc.getHost());
        }
        
        // The size of the map-output
        long bytes = mapOutput.compressedSize;
        
        // lock the ReduceTask while we do the rename
        synchronized (ReduceTask.this) {
          if (copiedMapOutputs.contains(loc.getTaskId())) {
            mapOutput.discard();
            return CopyResult.OBSOLETE;
          }

          // Special case: discard empty map-outputs
          if (bytes == 0) {
            try {
              mapOutput.discard();
            } catch (IOException ioe) {
              LOG.info("Couldn't discard output of " + loc.getTaskId());
            }
            
            // Note that we successfully copied the map-output
            noteCopiedMapOutput(loc.getTaskId());
            
            return bytes;
          }
          
          // Process map-output
          if (mapOutput.inMemory) {
            // Save it in the synchronized list of map-outputs
            mapOutputsFilesInMemory.add(mapOutput);
          } else {
            // Rename the temporary file to the final file; 
            // ensure it is on the same partition
            tmpMapOutput = mapOutput.file;
            filename = new Path(tmpMapOutput.getParent(), filename.getName());
            if (!localFileSys.rename(tmpMapOutput, filename)) {
              localFileSys.delete(tmpMapOutput, true);
              bytes = -1;
              throw new IOException("Failed to rename map output " + 
                  tmpMapOutput + " to " + filename);
            }

            synchronized (mapOutputFilesOnDisk) {        
              addToMapOutputFilesOnDisk(localFileSys.getFileStatus(filename));
            }
          }

          // Note that we successfully copied the map-output
          noteCopiedMapOutput(loc.getTaskId());
        }
        
        return bytes;
      }
      
      /**
       * Save the map taskid whose output we just copied.
       * This function assumes that it has been synchronized on ReduceTask.this.
       * 
       * @param taskId map taskid
       */
      private void noteCopiedMapOutput(TaskID taskId) {
        copiedMapOutputs.add(taskId);
        ramManager.setNumCopiedMapOutputs(numMaps - copiedMapOutputs.size());
      }

      /**
       * Get the map output into a local file (either in the inmemory fs or on the 
       * local fs) from the remote server.
       * We use the file system so that we generate checksum files on the data.
       * @param mapOutputLoc map-output to be fetched
       * @param filename the filename to write the data into
       * @param connectionTimeout number of milliseconds for connection timeout
       * @param readTimeout number of milliseconds for read timeout
       * @return the path of the file that got created
       * @throws IOException when something goes wrong
       */
      private MapOutput getMapOutput(MapOutputLocation mapOutputLoc, 
                                     Path filename, int reduce)
      throws IOException, InterruptedException {
        // Connect
        URLConnection connection = 
          mapOutputLoc.getOutputLocation().openConnection();
        InputStream input = getInputStream(connection, STALLED_COPY_TIMEOUT,
                                           DEFAULT_READ_TIMEOUT); 
        
        // Validate header from map output
        TaskAttemptID mapId = null;
        try {
          mapId =
            TaskAttemptID.forName(connection.getHeaderField(FROM_MAP_TASK));
        } catch (IllegalArgumentException ia) {
          LOG.warn("Invalid map id ", ia);
          return null;
        }
        TaskAttemptID expectedMapId = mapOutputLoc.getTaskAttemptId();
        if (!mapId.equals(expectedMapId)) {
          LOG.warn("data from wrong map:" + mapId +
              " arrived to reduce task " + reduce +
              ", where as expected map output should be from " + expectedMapId);
          return null;
        }
        
        long decompressedLength = 
          Long.parseLong(connection.getHeaderField(RAW_MAP_OUTPUT_LENGTH));  
        long compressedLength = 
          Long.parseLong(connection.getHeaderField(MAP_OUTPUT_LENGTH));

        if (compressedLength < 0 || decompressedLength < 0) {
          LOG.warn(getName() + " invalid lengths in map output header: id: " +
              mapId + " compressed len: " + compressedLength +
              ", decompressed len: " + decompressedLength);
          return null;
        }
        int forReduce =
          (int)Integer.parseInt(connection.getHeaderField(FOR_REDUCE_TASK));
        
        if (forReduce != reduce) {
          LOG.warn("data for the wrong reduce: " + forReduce +
              " with compressed len: " + compressedLength +
              ", decompressed len: " + decompressedLength +
              " arrived to reduce task " + reduce);
          return null;
        }
        LOG.info("header: " + mapId + ", compressed len: " + compressedLength +
                 ", decompressed len: " + decompressedLength);

        //We will put a file in memory if it meets certain criteria:
        //1. The size of the (decompressed) file should be less than 25% of 
        //    the total inmem fs
        //2. There is space available in the inmem fs
        
        // Check if this map-output can be saved in-memory
        boolean shuffleInMemory = ramManager.canFitInMemory(decompressedLength); 

        // Shuffle
        MapOutput mapOutput = null;
        if (shuffleInMemory) { 
          LOG.info("Shuffling " + decompressedLength + " bytes (" + 
              compressedLength + " raw bytes) " + 
              "into RAM from " + mapOutputLoc.getTaskAttemptId());

          mapOutput = shuffleInMemory(mapOutputLoc, connection, input,
                                      (int)decompressedLength,
                                      (int)compressedLength);
        } else {
          LOG.info("Shuffling " + decompressedLength + " bytes (" + 
              compressedLength + " raw bytes) " + 
              "into Local-FS from " + mapOutputLoc.getTaskAttemptId());

          mapOutput = shuffleToDisk(mapOutputLoc, input, filename, 
              compressedLength);
        }
            
        return mapOutput;
      }

      /** 
       * The connection establishment is attempted multiple times and is given up 
       * only on the last failure. Instead of connecting with a timeout of 
       * X, we try connecting with a timeout of x < X but multiple times. 
       */
      private InputStream getInputStream(URLConnection connection, 
                                         int connectionTimeout, 
                                         int readTimeout) 
      throws IOException {
        int unit = 0;
        if (connectionTimeout < 0) {
          throw new IOException("Invalid timeout "
                                + "[timeout = " + connectionTimeout + " ms]");
        } else if (connectionTimeout > 0) {
          unit = (UNIT_CONNECT_TIMEOUT > connectionTimeout)
                 ? connectionTimeout
                 : UNIT_CONNECT_TIMEOUT;
        }
        // set the read timeout to the total timeout
        connection.setReadTimeout(readTimeout);
        // set the connect timeout to the unit-connect-timeout
        connection.setConnectTimeout(unit);
        while (true) {
          try {
            return connection.getInputStream();
          } catch (IOException ioe) {
            // update the total remaining connect-timeout
            connectionTimeout -= unit;

            // throw an exception if we have waited for timeout amount of time
            // note that the updated value if timeout is used here
            if (connectionTimeout == 0) {
              throw ioe;
            }

            // reset the connect timeout for the last try
            if (connectionTimeout < unit) {
              unit = connectionTimeout;
              // reset the connect time out for the final connect
              connection.setConnectTimeout(unit);
            }
          }
        }
      }

      private MapOutput shuffleInMemory(MapOutputLocation mapOutputLoc,
                                        URLConnection connection, 
                                        InputStream input,
                                        int mapOutputLength,
                                        int compressedLength)
      throws IOException, InterruptedException {
        // Reserve ram for the map-output
        boolean createdNow = ramManager.reserve(mapOutputLength, input);
      
        // Reconnect if we need to
        if (!createdNow) {
          // Reconnect
          try {
            connection = mapOutputLoc.getOutputLocation().openConnection();
            input = getInputStream(connection, STALLED_COPY_TIMEOUT, 
                                   DEFAULT_READ_TIMEOUT);
          } catch (IOException ioe) {
            LOG.info("Failed reopen connection to fetch map-output from " + 
                     mapOutputLoc.getHost());
            
            // Inform the ram-manager
            ramManager.closeInMemoryFile(mapOutputLength);
            ramManager.unreserve(mapOutputLength);
            
            throw ioe;
          }
        }

        IFileInputStream checksumIn = 
          new IFileInputStream(input,compressedLength);

        input = checksumIn;       
      
        // Are map-outputs compressed?
        if (codec != null) {
          decompressor.reset();
          input = codec.createInputStream(input, decompressor);
        }
      
        // Copy map-output into an in-memory buffer
        byte[] shuffleData = new byte[mapOutputLength];
        MapOutput mapOutput = 
          new MapOutput(mapOutputLoc.getTaskId(), 
                        mapOutputLoc.getTaskAttemptId(), shuffleData, compressedLength);
        
        int bytesRead = 0;
        try {
          int n = input.read(shuffleData, 0, shuffleData.length);
          while (n > 0) {
            bytesRead += n;
            shuffleClientMetrics.inputBytes(n);

            // indicate we're making progress
            reporter.progress();
            n = input.read(shuffleData, bytesRead, 
                           (shuffleData.length-bytesRead));
          }

          LOG.info("Read " + bytesRead + " bytes from map-output for " +
                   mapOutputLoc.getTaskAttemptId());

          input.close();
        } catch (IOException ioe) {
          LOG.info("Failed to shuffle from " + mapOutputLoc.getTaskAttemptId(), 
                   ioe);

          // Inform the ram-manager
          ramManager.closeInMemoryFile(mapOutputLength);
          ramManager.unreserve(mapOutputLength);
          
          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            LOG.info("Failed to discard map-output from " + 
                     mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;
          
          // Close the streams
          IOUtils.cleanup(LOG, input);

          // Re-throw
          throw ioe;
        }

        // Close the in-memory file
        ramManager.closeInMemoryFile(mapOutputLength);

        // Sanity check
        if (bytesRead != mapOutputLength) {
          // Inform the ram-manager
          ramManager.unreserve(mapOutputLength);
          
          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            // IGNORED because we are cleaning up
            LOG.info("Failed to discard map-output from " + 
                     mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;

          throw new IOException("Incomplete map output received for " +
                                mapOutputLoc.getTaskAttemptId() + " from " +
                                mapOutputLoc.getOutputLocation() + " (" + 
                                bytesRead + " instead of " + 
                                mapOutputLength + ")"
          );
        }

        // TODO: Remove this after a 'fix' for HADOOP-3647
        if (mapOutputLength > 0) {
          DataInputBuffer dib = new DataInputBuffer();
          dib.reset(shuffleData, 0, shuffleData.length);
          LOG.info("Rec #1 from " + mapOutputLoc.getTaskAttemptId() + " -> (" + 
                   WritableUtils.readVInt(dib) + ", " + 
                   WritableUtils.readVInt(dib) + ") from " + 
                   mapOutputLoc.getHost());
        }
        
        return mapOutput;
      }
      
      private MapOutput shuffleToDisk(MapOutputLocation mapOutputLoc,
                                      InputStream input,
                                      Path filename,
                                      long mapOutputLength) 
      throws IOException {
        // Find out a suitable location for the output on local-filesystem
        Path localFilename = 
          lDirAlloc.getLocalPathForWrite(filename.toUri().getPath(), 
                                         mapOutputLength, conf);

        MapOutput mapOutput = 
          new MapOutput(mapOutputLoc.getTaskId(), mapOutputLoc.getTaskAttemptId(), 
                        conf, localFileSys.makeQualified(localFilename), 
                        mapOutputLength);


        // Copy data to local-disk
        OutputStream output = null;
        long bytesRead = 0;
        try {
          output = rfs.create(localFilename);
          
          byte[] buf = new byte[64 * 1024];
          int n = input.read(buf, 0, buf.length);
          while (n > 0) {
            bytesRead += n;
            shuffleClientMetrics.inputBytes(n);
            output.write(buf, 0, n);

            // indicate we're making progress
            reporter.progress();
            n = input.read(buf, 0, buf.length);
          }

          LOG.info("Read " + bytesRead + " bytes from map-output for " +
              mapOutputLoc.getTaskAttemptId());

          output.close();
          input.close();
        } catch (IOException ioe) {
          LOG.info("Failed to shuffle from " + mapOutputLoc.getTaskAttemptId(), 
                   ioe);

          // Discard the map-output
          try {
            mapOutput.discard();
          } catch (IOException ignored) {
            LOG.info("Failed to discard map-output from " + 
                mapOutputLoc.getTaskAttemptId(), ignored);
          }
          mapOutput = null;

          // Close the streams
          IOUtils.cleanup(LOG, input, output);

          // Re-throw
          throw ioe;
        }

        // Sanity check
        if (bytesRead != mapOutputLength) {
          try {
            mapOutput.discard();
          } catch (Exception ioe) {
            // IGNORED because we are cleaning up
            LOG.info("Failed to discard map-output from " + 
                mapOutputLoc.getTaskAttemptId(), ioe);
          } catch (Throwable t) {
            String msg = getTaskID() + " : Failed in shuffle to disk :" 
                         + StringUtils.stringifyException(t);
            reportFatalError(getTaskID(), t, msg);
          }
          mapOutput = null;

          throw new IOException("Incomplete map output received for " +
                                mapOutputLoc.getTaskAttemptId() + " from " +
                                mapOutputLoc.getOutputLocation() + " (" + 
                                bytesRead + " instead of " + 
                                mapOutputLength + ")"
          );
        }

        return mapOutput;

      }
      
    } // MapOutputCopier
    
    private void configureClasspath(JobConf conf)
      throws IOException {
      
      // get the task and the current classloader which will become the parent
      Task task = ReduceTask.this;
      ClassLoader parent = conf.getClassLoader();   
      
      // get the work directory which holds the elements we are dynamically
      // adding to the classpath
      File workDir = new File(task.getJobFile()).getParentFile();
      ArrayList<URL> urllist = new ArrayList<URL>();
      
      // add the jars and directories to the classpath
      String jar = conf.getJar();
      if (jar != null) {      
        File jobCacheDir = new File(new Path(jar).getParent().toString());

        File[] libs = new File(jobCacheDir, "lib").listFiles();
        if (libs != null) {
          for (int i = 0; i < libs.length; i++) {
            urllist.add(libs[i].toURL());
          }
        }
        urllist.add(new File(jobCacheDir, "classes").toURL());
        urllist.add(jobCacheDir.toURL());
        
      }
      urllist.add(workDir.toURL());
      
      // create a new classloader with the old classloader as its parent
      // then set that classloader as the one used by the current jobconf
      URL[] urls = urllist.toArray(new URL[urllist.size()]);
      URLClassLoader loader = new URLClassLoader(urls, parent);
      conf.setClassLoader(loader);
    }
    
    public ReduceCopier(TaskUmbilicalProtocol umbilical, JobConf conf,
                        TaskReporter reporter
                        )throws ClassNotFoundException, IOException {
      
      configureClasspath(conf);
      this.reporter = reporter;
      this.shuffleClientMetrics = new ShuffleClientMetrics(conf);
      this.umbilical = umbilical;      
      this.reduceTask = ReduceTask.this;

      this.scheduledCopies = new ArrayList<MapOutputLocation>(100);
      this.copyResults = new ArrayList<CopyResult>(100);    
      this.numCopiers = conf.getInt("mapred.reduce.parallel.copies", 5);
      this.maxInFlight = 4 * numCopiers;
      this.maxBackoff = conf.getInt("mapred.reduce.copy.backoff", 300);
      Counters.Counter combineInputCounter = 
        reporter.getCounter(Task.Counter.COMBINE_INPUT_RECORDS);
      this.combinerRunner = CombinerRunner.create(conf, getTaskID(),
                                                  combineInputCounter,
                                                  reporter, null);
      if (combinerRunner != null) {
        combineCollector = 
          new CombineOutputCollector(reduceCombineOutputCounter);
      }
      
      this.ioSortFactor = conf.getInt("io.sort.factor", 10);
      // the exponential backoff formula
      //    backoff (t) = init * base^(t-1)
      // so for max retries we get
      //    backoff(1) + .... + backoff(max_fetch_retries) ~ max
      // solving which we get
      //    max_fetch_retries ~ log((max * (base - 1) / init) + 1) / log(base)
      // for the default value of max = 300 (5min) we get max_fetch_retries = 6
      // the order is 4,8,16,32,64,128. sum of which is 252 sec = 4.2 min
      
      // optimizing for the base 2
      this.maxFetchRetriesPerMap = Math.max(MIN_FETCH_RETRIES_PER_MAP, 
             getClosestPowerOf2((this.maxBackoff * 1000 / BACKOFF_INIT) + 1));
      this.maxFailedUniqueFetches = Math.min(numMaps, 
                                             this.maxFailedUniqueFetches);
      this.maxInMemOutputs = conf.getInt("mapred.inmem.merge.threshold", 1000);
      this.maxInMemCopyPer =
        conf.getFloat("mapred.job.shuffle.merge.percent", 0.66f);
      final float maxRedPer =
        conf.getFloat("mapred.job.reduce.input.buffer.percent", 0f);
      if (maxRedPer > 1.0 || maxRedPer < 0.0) {
        throw new IOException("mapred.job.reduce.input.buffer.percent" +
                              maxRedPer);
      }
      this.maxInMemReduce = (int)Math.min(
          Runtime.getRuntime().maxMemory() * maxRedPer, Integer.MAX_VALUE);

      // Setup the RamManager
      ramManager = new ShuffleRamManager(conf);

      localFileSys = FileSystem.getLocal(conf);

      rfs = ((LocalFileSystem)localFileSys).getRaw();

      // hosts -> next contact time
      this.penaltyBox = new LinkedHashMap<String, Long>();
      
      // hostnames
      this.uniqueHosts = new HashSet<String>();
      
      // Seed the random number generator with a reasonably globally unique seed
      long randomSeed = System.nanoTime() + 
                        (long)Math.pow(this.reduceTask.getPartition(),
                                       (this.reduceTask.getPartition()%10)
                                      );
      this.random = new Random(randomSeed);
      this.maxMapRuntime = 0;

      //The followings are for rdma project
      this.rdmaSetting = conf.getInt("mapred.rdma.setting", 0);
      if (this.rdmaSetting > 0) {
          this.rdmaChannel = new DataSocket(conf,reporter, numMaps);
      }
    }
    
    private boolean busyEnough(int numInFlight) {
      return numInFlight > maxInFlight;
    }
  
    /* intercept original hadoop */ 
    private boolean rdmaFetch() {
      if (this.rdmaSetting == 1) {
        GetMapEventsThread getMapEventsThread = null;
        // start the map events thread
        getMapEventsThread = new GetMapEventsThread();
        getMapEventsThread.start();         
        this.rdmaChannel.start();
        
        LOG.info("ReduceCopier: Wait for fetching");
        synchronized(ReduceCopier.this) {
          try {
            ReduceCopier.this.wait(); 
          } catch (InterruptedException e) {
          }       
        }
        LOG.info("ReduceCopier: Fetching is done"); 
        // all done, inform the copiers to exit
        exitGetMapEvents= true;
        try {
          //here only stop the thread, but don't close it, 
          //because we need this channel to return the values later.
          getMapEventsThread.join();
          LOG.info("getMapsEventsThread joined.");
        } catch (InterruptedException ie) {
          LOG.info("getMapsEventsThread/rdmaChannelThread threw an exception: " +
                   StringUtils.stringifyException(ie));
        }
        return true;
      }  
      return false;
    }   
 
    public boolean fetchOutputs() throws IOException {
      if (rdmaFetch()) { return true; }
      
      int totalFailures = 0;
      int            numInFlight = 0, numCopied = 0;
      DecimalFormat  mbpsFormat = new DecimalFormat("0.00");
      final Progress copyPhase = 
        reduceTask.getProgress().phase();

      LocalFSMerger      localFSMergerThread = null;
      InMemFSMergeThread inMemFSMergeThread = null;


      GetMapEventsThread getMapEventsThread = null;
      
      for (int i = 0; i < numMaps; i++) {
        copyPhase.addPhase();       // add sub-phase per file
      }
     
      copiers = new ArrayList<MapOutputCopier>(numCopiers); 
      // start all the copying threads
      for (int i=0; i < numCopiers; i++) {
        MapOutputCopier copier = new MapOutputCopier(conf, reporter);
        copiers.add(copier);
        copier.start();
      }
      //start the on-disk-merge thread
      localFSMergerThread = new LocalFSMerger((LocalFileSystem)localFileSys);
      //start the in memory merger thread
      inMemFSMergeThread = new InMemFSMergeThread();
      localFSMergerThread.start();
      inMemFSMergeThread.start();
     
      // start the map events thread
      getMapEventsThread = new GetMapEventsThread();
      getMapEventsThread.start();

      // start the clock for bandwidth measurement
      long startTime = System.currentTimeMillis();
      long currentTime = startTime;
      long lastProgressTime = startTime;
      long lastOutputTime = 0;

        // loop until we get all required outputs
        while (copiedMapOutputs.size() < numMaps && mergeThrowable == null) {
          
          currentTime = System.currentTimeMillis();
          boolean logNow = false;
          if (currentTime - lastOutputTime > MIN_LOG_TIME) {
            lastOutputTime = currentTime;
            logNow = true;
          }
          if (logNow) {
            LOG.info(reduceTask.getTaskID() + " Need another " 
                   + (numMaps - copiedMapOutputs.size()) + " map output(s) "
                   + "where " + numInFlight + " is already in progress");
          }

          // Put the hash entries for the failed fetches.
          Iterator<MapOutputLocation> locItr = retryFetches.iterator();

          while (locItr.hasNext()) {
            MapOutputLocation loc = locItr.next(); 
            List<MapOutputLocation> locList = 
              mapLocations.get(loc.getHost());
            
            // Check if the list exists. Map output location mapping is cleared 
            // once the jobtracker restarts and is rebuilt from scratch.
            // Note that map-output-location mapping will be recreated and hence
            // we continue with the hope that we might find some locations
            // from the rebuild map.
            if (locList != null) {
              // Add to the beginning of the list so that this map is 
              //tried again before the others and we can hasten the 
              //re-execution of this map should there be a problem
              locList.add(0, loc);
            }
          }

          if (retryFetches.size() > 0) {
            LOG.info(reduceTask.getTaskID() + ": " +  
                  "Got " + retryFetches.size() +
                  " map-outputs from previous failures");
          }
          // clear the "failed" fetches hashmap
          retryFetches.clear();

          // now walk through the cache and schedule what we can
          int numScheduled = 0;
          int numDups = 0;
          
          synchronized (scheduledCopies) {
  
            // Randomize the map output locations to prevent 
            // all reduce-tasks swamping the same tasktracker
            List<String> hostList = new ArrayList<String>();
            hostList.addAll(mapLocations.keySet()); 
            
            Collections.shuffle(hostList, this.random);
              
            Iterator<String> hostsItr = hostList.iterator();

            while (hostsItr.hasNext()) {
            
              String host = hostsItr.next();

              List<MapOutputLocation> knownOutputsByLoc = 
                mapLocations.get(host);

              // Check if the list exists. Map output location mapping is 
              // cleared once the jobtracker restarts and is rebuilt from 
              // scratch.
              // Note that map-output-location mapping will be recreated and 
              // hence we continue with the hope that we might find some 
              // locations from the rebuild map and add then for fetching.
              if (knownOutputsByLoc == null || knownOutputsByLoc.size() == 0) {
                continue;
              }
              
              //Identify duplicate hosts here
              if (uniqueHosts.contains(host)) {
                 numDups += knownOutputsByLoc.size(); 
                 continue;
              }

              Long penaltyEnd = penaltyBox.get(host);
              boolean penalized = false;
            
              if (penaltyEnd != null) {
                if (currentTime < penaltyEnd.longValue()) {
                  penalized = true;
                } else {
                  penaltyBox.remove(host);
                }
              }
              
              if (penalized)
                continue;

              synchronized (knownOutputsByLoc) {
              
                locItr = knownOutputsByLoc.iterator();
            
                while (locItr.hasNext()) {
              
                  MapOutputLocation loc = locItr.next();
              
                  // Do not schedule fetches from OBSOLETE maps
                  if (obsoleteMapIds.contains(loc.getTaskAttemptId())) {
                    locItr.remove();
                    continue;
                  }

                  uniqueHosts.add(host);
                  scheduledCopies.add(loc);
                  locItr.remove();  // remove from knownOutputs
                  numInFlight++; numScheduled++;

                  break; //we have a map from this host
                }
              }
            }
            scheduledCopies.notifyAll();
          }

          if (numScheduled > 0 || logNow) {
            LOG.info(reduceTask.getTaskID() + " Scheduled " + numScheduled +
                   " outputs (" + penaltyBox.size() +
                   " slow hosts and" + numDups + " dup hosts)");
          }

          if (penaltyBox.size() > 0 && logNow) {
            LOG.info("Penalized(slow) Hosts: ");
            for (String host : penaltyBox.keySet()) {
              LOG.info(host + " Will be considered after: " + 
                  ((penaltyBox.get(host) - currentTime)/1000) + " seconds.");
            }
          }

          // if we have no copies in flight and we can't schedule anything
          // new, just wait for a bit
          try {
            if (numInFlight == 0 && numScheduled == 0) {
              // we should indicate progress as we don't want TT to think
              // we're stuck and kill us
              reporter.progress();
              Thread.sleep(5000);
            }
          } catch (InterruptedException e) { } // IGNORE
          
          while (numInFlight > 0 && mergeThrowable == null) {
            LOG.debug(reduceTask.getTaskID() + " numInFlight = " + 
                      numInFlight);
            //the call to getCopyResult will either 
            //1) return immediately with a null or a valid CopyResult object,
            //                 or
            //2) if the numInFlight is above maxInFlight, return with a 
            //   CopyResult object after getting a notification from a 
            //   fetcher thread, 
            //So, when getCopyResult returns null, we can be sure that
            //we aren't busy enough and we should go and get more mapcompletion
            //events from the tasktracker
            CopyResult cr = getCopyResult(numInFlight);

            if (cr == null) {
              break;
            }
            
            if (cr.getSuccess()) {  // a successful copy
              numCopied++;
              lastProgressTime = System.currentTimeMillis();
              reduceShuffleBytes.increment(cr.getSize());
                
              long secsSinceStart = 
                (System.currentTimeMillis()-startTime)/1000+1;
              float mbs = ((float)reduceShuffleBytes.getCounter())/(1024*1024);
              float transferRate = mbs/secsSinceStart;
                
              copyPhase.startNextPhase();
              copyPhase.setStatus("copy (" + numCopied + " of " + numMaps 
                                  + " at " +
                                  mbpsFormat.format(transferRate) +  " MB/s)");
                
              // Note successful fetch for this mapId to invalidate
              // (possibly) old fetch-failures
              fetchFailedMaps.remove(cr.getLocation().getTaskId());
            } else if (cr.isObsolete()) {
              //ignore
              LOG.info(reduceTask.getTaskID() + 
                       " Ignoring obsolete copy result for Map Task: " + 
                       cr.getLocation().getTaskAttemptId() + " from host: " + 
                       cr.getHost());
            } else {
              retryFetches.add(cr.getLocation());
              
              // note the failed-fetch
              TaskAttemptID mapTaskId = cr.getLocation().getTaskAttemptId();
              TaskID mapId = cr.getLocation().getTaskId();
              
              totalFailures++;
              Integer noFailedFetches = 
                mapTaskToFailedFetchesMap.get(mapTaskId);
              noFailedFetches = 
                (noFailedFetches == null) ? 1 : (noFailedFetches + 1);
              mapTaskToFailedFetchesMap.put(mapTaskId, noFailedFetches);
              LOG.info("Task " + getTaskID() + ": Failed fetch #" + 
                       noFailedFetches + " from " + mapTaskId);
              
              // did the fetch fail too many times?
              // using a hybrid technique for notifying the jobtracker.
              //   a. the first notification is sent after max-retries 
              //   b. subsequent notifications are sent after 2 retries.   
              if ((noFailedFetches >= maxFetchRetriesPerMap) 
                  && ((noFailedFetches - maxFetchRetriesPerMap) % 2) == 0) {
                synchronized (ReduceTask.this) {
                  taskStatus.addFetchFailedMap(mapTaskId);
                  LOG.info("Failed to fetch map-output from " + mapTaskId + 
                           " even after MAX_FETCH_RETRIES_PER_MAP retries... "
                           + " reporting to the JobTracker");
                }
              }
              // note unique failed-fetch maps
              if (noFailedFetches == maxFetchRetriesPerMap) {
                fetchFailedMaps.add(mapId);
                  
                // did we have too many unique failed-fetch maps?
                // and did we fail on too many fetch attempts?
                // and did we progress enough
                //     or did we wait for too long without any progress?
               
                // check if the reducer is healthy
                boolean reducerHealthy = 
                    (((float)totalFailures / (totalFailures + numCopied)) 
                     < MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT);
                
                // check if the reducer has progressed enough
                boolean reducerProgressedEnough = 
                    (((float)numCopied / numMaps) 
                     >= MIN_REQUIRED_PROGRESS_PERCENT);
                
                // check if the reducer is stalled for a long time
                // duration for which the reducer is stalled
                int stallDuration = 
                    (int)(System.currentTimeMillis() - lastProgressTime);
                // duration for which the reducer ran with progress
                int shuffleProgressDuration = 
                    (int)(lastProgressTime - startTime);
                // min time the reducer should run without getting killed
                int minShuffleRunDuration = 
                    (shuffleProgressDuration > maxMapRuntime) 
                    ? shuffleProgressDuration 
                    : maxMapRuntime;
                boolean reducerStalled = 
                    (((float)stallDuration / minShuffleRunDuration) 
                     >= MAX_ALLOWED_STALL_TIME_PERCENT);
                
                // kill if not healthy and has insufficient progress
                if ((fetchFailedMaps.size() >= maxFailedUniqueFetches ||
                     fetchFailedMaps.size() == (numMaps - copiedMapOutputs.size()))
                    && !reducerHealthy 
                    && (!reducerProgressedEnough || reducerStalled)) { 
                  LOG.fatal("Shuffle failed with too many fetch failures " + 
                            "and insufficient progress!" +
                            "Killing task " + getTaskID() + ".");
                  umbilical.shuffleError(getTaskID(), 
                                         "Exceeded MAX_FAILED_UNIQUE_FETCHES;"
                                         + " bailing-out.");
                }
              }
                
              // back off exponentially until num_retries <= max_retries
              // back off by max_backoff/2 on subsequent failed attempts
              currentTime = System.currentTimeMillis();
              int currentBackOff = noFailedFetches <= maxFetchRetriesPerMap 
                                   ? BACKOFF_INIT 
                                     * (1 << (noFailedFetches - 1)) 
                                   : (this.maxBackoff * 1000 / 2);
              penaltyBox.put(cr.getHost(), currentTime + currentBackOff);
              LOG.warn(reduceTask.getTaskID() + " adding host " +
                       cr.getHost() + " to penalty box, next contact in " +
                       (currentBackOff/1000) + " seconds");
            }
            uniqueHosts.remove(cr.getHost());
            numInFlight--;
          }
        }
        
        // all done, inform the copiers to exit
        exitGetMapEvents= true;
        try {
          getMapEventsThread.join();
          LOG.info("getMapsEventsThread joined.");
        } catch (InterruptedException ie) {
          LOG.info("getMapsEventsThread threw an exception: " +
              StringUtils.stringifyException(ie));
        }
         
        synchronized (copiers) {
          synchronized (scheduledCopies) {
            for (MapOutputCopier copier : copiers) {
              copier.interrupt();
            }
            copiers.clear();
          }
        }
        
        // copiers are done, exit and notify the waiting merge threads
        synchronized (mapOutputFilesOnDisk) {
          exitLocalFSMerge = true;
          mapOutputFilesOnDisk.notify();
        }
        
        ramManager.close();
        
        //Do a merge of in-memory files (if there are any)
        if (mergeThrowable == null) {
          try {
            // Wait for the on-disk merge to complete
            localFSMergerThread.join();
            LOG.info("Interleaved on-disk merge complete: " + 
                     mapOutputFilesOnDisk.size() + " files left.");
            
            //wait for an ongoing merge (if it is in flight) to complete
            inMemFSMergeThread.join();
            LOG.info("In-memory merge complete: " + 
                     mapOutputsFilesInMemory.size() + " files left.");
          } catch (InterruptedException ie) {
            LOG.warn(reduceTask.getTaskID() +
                    " Final merge of the inmemory files threw an exception: " + 
                     StringUtils.stringifyException(ie));
            // check if the last merge generated an error
            if (mergeThrowable != null) {
              mergeThrowable = ie;
            }
            return false;
          }
        }
      return mergeThrowable == null && copiedMapOutputs.size() == numMaps;
    }

    private long createInMemorySegments(
        List<Segment<K, V>> inMemorySegments, long leaveBytes)
        throws IOException {
      long totalSize = 0L;
      synchronized (mapOutputsFilesInMemory) {
        // fullSize could come from the RamManager, but files can be
        // closed but not yet present in mapOutputsFilesInMemory
        long fullSize = 0L;
        for (MapOutput mo : mapOutputsFilesInMemory) {
          fullSize += mo.data.length;
        }
        while(fullSize > leaveBytes) {
          MapOutput mo = mapOutputsFilesInMemory.remove(0);
          totalSize += mo.data.length;
          fullSize -= mo.data.length;
          Reader<K, V> reader = 
            new InMemoryReader<K, V>(ramManager, mo.mapAttemptId,
                                     mo.data, 0, mo.data.length);
          Segment<K, V> segment = 
            new Segment<K, V>(reader, true);
          inMemorySegments.add(segment);
        }
      }
      return totalSize;
    }
    
    /**
     * For rdma setting
     */
     @SuppressWarnings("unchecked")
    private RawKeyValueIterator createKVIterator(
        JobConf job, FileSystem fs, Reporter reporter) throws IOException {
      
      //using rdma merger
      if (this.rdmaSetting == 1) {
        RawKeyValueIterator rIter = 
          this.rdmaChannel.createKVIterator_rdma(job,fs,reporter);
        return rIter;
      } 
      else {
        return createKVIterator_hadoop(job, fs, reporter);        
      }
    }
   
    private void close() throws IOException{
      if (this.rdmaSetting == 1) {
        this.rdmaChannel.close();
      }
    }
    /**
     * Create a RawKeyValueIterator from copied map outputs. All copying
     * threads have exited, so all of the map outputs are available either in
     * memory or on disk. We also know that no merges are in progress, so
     * synchronization is more lax, here.
     *
     * The iterator returned must satisfy the following constraints:
     *   1. Fewer than io.sort.factor files may be sources
     *   2. No more than maxInMemReduce bytes of map outputs may be resident
     *      in memory when the reduce begins
     *
     * If we must perform an intermediate merge to satisfy (1), then we can
     * keep the excluded outputs from (2) in memory and include them in the
     * first merge pass. If not, then said outputs must be written to disk
     * first.
     *
     */
    @SuppressWarnings("unchecked")
    private RawKeyValueIterator createKVIterator_hadoop(
        JobConf job, FileSystem fs, Reporter reporter) throws IOException {

      // merge config params
      Class<K> keyClass = (Class<K>)job.getMapOutputKeyClass();
      Class<V> valueClass = (Class<V>)job.getMapOutputValueClass();
      boolean keepInputs = job.getKeepFailedTaskFiles();
      final Path tmpDir = new Path(getTaskID().toString());
      final RawComparator<K> comparator =
        (RawComparator<K>)job.getOutputKeyComparator();

      // segments required to vacate memory
      List<Segment<K,V>> memDiskSegments = new ArrayList<Segment<K,V>>();
      long inMemToDiskBytes = 0;
      if (mapOutputsFilesInMemory.size() > 0) {
        TaskID mapId = mapOutputsFilesInMemory.get(0).mapId;
        inMemToDiskBytes = createInMemorySegments(memDiskSegments,
            maxInMemReduce);
        final int numMemDiskSegments = memDiskSegments.size();
        if (numMemDiskSegments > 0 &&
              ioSortFactor > mapOutputFilesOnDisk.size()) {
          // must spill to disk, but can't retain in-mem for intermediate merge
          final Path outputPath = mapOutputFile.getInputFileForWrite(mapId,
                            reduceTask.getTaskID(), inMemToDiskBytes);
          final RawKeyValueIterator rIter = Merger.merge(job, fs,
              keyClass, valueClass, memDiskSegments, numMemDiskSegments,
              tmpDir, comparator, reporter, spilledRecordsCounter, null);
          final Writer writer = new Writer(job, fs, outputPath,
              keyClass, valueClass, codec, null);
          try {
            Merger.writeFile(rIter, writer, reporter, job);
            addToMapOutputFilesOnDisk(fs.getFileStatus(outputPath));
          } catch (Exception e) {
            if (null != outputPath) {
              fs.delete(outputPath, true);
            }
            throw new IOException("Final merge failed", e);
          } finally {
            if (null != writer) {
              writer.close();
            }
          }
          LOG.info("Merged " + numMemDiskSegments + " segments, " +
                   inMemToDiskBytes + " bytes to disk to satisfy " +
                   "reduce memory limit");
          inMemToDiskBytes = 0;
          memDiskSegments.clear();
        } else if (inMemToDiskBytes != 0) {
          LOG.info("Keeping " + numMemDiskSegments + " segments, " +
                   inMemToDiskBytes + " bytes in memory for " +
                   "intermediate, on-disk merge");
        }
      }

      // segments on disk
      List<Segment<K,V>> diskSegments = new ArrayList<Segment<K,V>>();
      long onDiskBytes = inMemToDiskBytes;
      Path[] onDisk = getMapFiles(fs, false);
      for (Path file : onDisk) {
        onDiskBytes += fs.getFileStatus(file).getLen();
        diskSegments.add(new Segment<K, V>(job, fs, file, codec, keepInputs));
      }
      LOG.info("Merging " + onDisk.length + " files, " +
               onDiskBytes + " bytes from disk");
      Collections.sort(diskSegments, new Comparator<Segment<K,V>>() {
        public int compare(Segment<K, V> o1, Segment<K, V> o2) {
          if (o1.getLength() == o2.getLength()) {
            return 0;
          }
          return o1.getLength() < o2.getLength() ? -1 : 1;
        }
      });
      
      // build final list of segments from merged backed by disk + in-mem
      List<Segment<K,V>> finalSegments = new ArrayList<Segment<K,V>>();
      long inMemBytes = createInMemorySegments(finalSegments, 0);
      LOG.info("Merging " + finalSegments.size() + " segments, " +
               inMemBytes + " bytes from memory into reduce");
      if (0 != onDiskBytes) {
        final int numInMemSegments = memDiskSegments.size();
        diskSegments.addAll(0, memDiskSegments);
        memDiskSegments.clear();
        RawKeyValueIterator diskMerge = Merger.merge(
            job, fs, keyClass, valueClass, codec, diskSegments,
            ioSortFactor, numInMemSegments, tmpDir, comparator,
            reporter, false, spilledRecordsCounter, null);
        diskSegments.clear();
        if (0 == finalSegments.size()) {
          return diskMerge;
        }
        finalSegments.add(new Segment<K,V>(
              new RawKVIteratorReader(diskMerge, onDiskBytes), true));
      }
      return Merger.merge(job, fs, keyClass, valueClass,
                   finalSegments, finalSegments.size(), tmpDir,
                   comparator, reporter, spilledRecordsCounter, null);
    }

    class RawKVIteratorReader extends IFile.Reader<K,V> {

      private final RawKeyValueIterator kvIter;

      public RawKVIteratorReader(RawKeyValueIterator kvIter, long size)
          throws IOException {
        super(null, null, size, null, spilledRecordsCounter);
        this.kvIter = kvIter;
      }

      public boolean next(DataInputBuffer key, DataInputBuffer value)
          throws IOException {
        if (kvIter.next()) {
          final DataInputBuffer kb = kvIter.getKey();
          final DataInputBuffer vb = kvIter.getValue();
          final int kp = kb.getPosition();
          final int klen = kb.getLength() - kp;
          key.reset(kb.getData(), kp, klen);
          final int vp = vb.getPosition();
          final int vlen = vb.getLength() - vp;
          value.reset(vb.getData(), vp, vlen);
          bytesRead += klen + vlen;
          return true;
        }
        return false;
      }

      public long getPosition() throws IOException {
        return bytesRead;
      }

      public void close() throws IOException {
        kvIter.close();
      }
    }

    private CopyResult getCopyResult(int numInFlight) {  
      synchronized (copyResults) {
        while (copyResults.isEmpty()) {
          try {
            //The idea is that if we have scheduled enough, we can wait until
            //we hear from one of the copiers.
            if (busyEnough(numInFlight)) {
              copyResults.wait();
            } else {
              return null;
            }
          } catch (InterruptedException e) { }
        }
        return copyResults.remove(0);
      }    
    }
    
    private void addToMapOutputFilesOnDisk(FileStatus status) {
      synchronized (mapOutputFilesOnDisk) {
        mapOutputFilesOnDisk.add(status);
        mapOutputFilesOnDisk.notify();
      }
    }
    
    
    
    /** Starts merging the local copy (on disk) of the map's output so that
     * most of the reducer's input is sorted i.e overlapping shuffle
     * and merge phases.
     */
    private class LocalFSMerger extends Thread {
      private LocalFileSystem localFileSys;

      public LocalFSMerger(LocalFileSystem fs) {
        this.localFileSys = fs;
        setName("Thread for merging on-disk files");
        setDaemon(true);
      }

      @SuppressWarnings("unchecked")
      public void run() {
        try {
          LOG.info(reduceTask.getTaskID() + " Thread started: " + getName());
          while(!exitLocalFSMerge){
            synchronized (mapOutputFilesOnDisk) {
              while (!exitLocalFSMerge &&
                  mapOutputFilesOnDisk.size() < (2 * ioSortFactor - 1)) {
                LOG.info(reduceTask.getTaskID() + " Thread waiting: " + getName());
                mapOutputFilesOnDisk.wait();
              }
            }
            if(exitLocalFSMerge) {//to avoid running one extra time in the end
              break;
            }
            List<Path> mapFiles = new ArrayList<Path>();
            long approxOutputSize = 0;
            int bytesPerSum = 
              reduceTask.getConf().getInt("io.bytes.per.checksum", 512);
            LOG.info(reduceTask.getTaskID() + "We have  " + 
                mapOutputFilesOnDisk.size() + " map outputs on disk. " +
                "Triggering merge of " + ioSortFactor + " files");
            // 1. Prepare the list of files to be merged. This list is prepared
            // using a list of map output files on disk. Currently we merge
            // io.sort.factor files into 1.
            synchronized (mapOutputFilesOnDisk) {
              for (int i = 0; i < ioSortFactor; ++i) {
                FileStatus filestatus = mapOutputFilesOnDisk.first();
                mapOutputFilesOnDisk.remove(filestatus);
                mapFiles.add(filestatus.getPath());
                approxOutputSize += filestatus.getLen();
              }
            }
            
            // sanity check
            if (mapFiles.size() == 0) {
                return;
            }
            
            // add the checksum length
            approxOutputSize += ChecksumFileSystem
                                .getChecksumLength(approxOutputSize,
                                                   bytesPerSum);
  
            // 2. Start the on-disk merge process
            Path outputPath = 
              lDirAlloc.getLocalPathForWrite(mapFiles.get(0).toString(), 
                                             approxOutputSize, conf)
              .suffix(".merged");
            Writer writer = 
              new Writer(conf,rfs, outputPath, 
                         conf.getMapOutputKeyClass(), 
                         conf.getMapOutputValueClass(),
                         codec, null);
            RawKeyValueIterator iter  = null;
            Path tmpDir = new Path(reduceTask.getTaskID().toString());
            try {
              iter = Merger.merge(conf, rfs,
                                  conf.getMapOutputKeyClass(),
                                  conf.getMapOutputValueClass(),
                                  codec, mapFiles.toArray(new Path[mapFiles.size()]), 
                                  true, ioSortFactor, tmpDir, 
                                  conf.getOutputKeyComparator(), reporter,
                                  spilledRecordsCounter, null);
              
              Merger.writeFile(iter, writer, reporter, conf);
              writer.close();
            } catch (Exception e) {
              localFileSys.delete(outputPath, true);
              throw new IOException (StringUtils.stringifyException(e));
            }
            
            synchronized (mapOutputFilesOnDisk) {
              addToMapOutputFilesOnDisk(localFileSys.getFileStatus(outputPath));
            }
            
            LOG.info(reduceTask.getTaskID() +
                     " Finished merging " + mapFiles.size() + 
                     " map output files on disk of total-size " + 
                     approxOutputSize + "." + 
                     " Local output file is " + outputPath + " of size " +
                     localFileSys.getFileStatus(outputPath).getLen());
            }
        } catch (Exception e) {
          LOG.warn(reduceTask.getTaskID()
                   + " Merging of the local FS files threw an exception: "
                   + StringUtils.stringifyException(e));
          if (mergeThrowable == null) {
            mergeThrowable = e;
          }
        } catch (Throwable t) {
          String msg = getTaskID() + " : Failed to merge on the local FS" 
                       + StringUtils.stringifyException(t);
          reportFatalError(getTaskID(), t, msg);
        }
      }
    }

    private class InMemFSMergeThread extends Thread {
      
      public InMemFSMergeThread() {
        setName("Thread for merging in memory files");
        setDaemon(true);
      }
      
      public void run() {
        LOG.info(reduceTask.getTaskID() + " Thread started: " + getName());
        try {
          boolean exit = false;
          do {
            exit = ramManager.waitForDataToMerge();
            if (!exit) {
              doInMemMerge();
            }
          } while (!exit);
        } catch (Exception e) {
          LOG.warn(reduceTask.getTaskID() +
                   " Merge of the inmemory files threw an exception: "
                   + StringUtils.stringifyException(e));
          ReduceCopier.this.mergeThrowable = e;
        } catch (Throwable t) {
          String msg = getTaskID() + " : Failed to merge in memory" 
                       + StringUtils.stringifyException(t);
          reportFatalError(getTaskID(), t, msg);
        }
      }
      
      @SuppressWarnings("unchecked")
      private void doInMemMerge() throws IOException{
        if (mapOutputsFilesInMemory.size() == 0) {
          return;
        }
        
        //name this output file same as the name of the first file that is 
        //there in the current list of inmem files (this is guaranteed to
        //be absent on the disk currently. So we don't overwrite a prev. 
        //created spill). Also we need to create the output file now since
        //it is not guaranteed that this file will be present after merge
        //is called (we delete empty files as soon as we see them
        //in the merge method)

        //figure out the mapId 
        TaskID mapId = mapOutputsFilesInMemory.get(0).mapId;

        List<Segment<K, V>> inMemorySegments = new ArrayList<Segment<K,V>>();
        long mergeOutputSize = createInMemorySegments(inMemorySegments, 0);
        int noInMemorySegments = inMemorySegments.size();

        Path outputPath = mapOutputFile.getInputFileForWrite(mapId, 
                          reduceTask.getTaskID(), mergeOutputSize);

        Writer writer = 
          new Writer(conf, rfs, outputPath,
                     conf.getMapOutputKeyClass(),
                     conf.getMapOutputValueClass(),
                     codec, null);

        RawKeyValueIterator rIter = null;
        try {
          LOG.info("Initiating in-memory merge with " + noInMemorySegments + 
                   " segments...");
          
          rIter = Merger.merge(conf, rfs,
                               (Class<K>)conf.getMapOutputKeyClass(),
                               (Class<V>)conf.getMapOutputValueClass(),
                               inMemorySegments, inMemorySegments.size(),
                               new Path(reduceTask.getTaskID().toString()),
                               conf.getOutputKeyComparator(), reporter,
                               spilledRecordsCounter, null);
          
          if (combinerRunner == null) {
            Merger.writeFile(rIter, writer, reporter, conf);
          } else {
            combineCollector.setWriter(writer);
            combinerRunner.combine(rIter, combineCollector);
          }
          writer.close();

          LOG.info(reduceTask.getTaskID() + 
              " Merge of the " + noInMemorySegments +
              " files in-memory complete." +
              " Local file is " + outputPath + " of size " + 
              localFileSys.getFileStatus(outputPath).getLen());
        } catch (Exception e) { 
          //make sure that we delete the ondisk file that we created 
          //earlier when we invoked cloneFileAttributes
          localFileSys.delete(outputPath, true);
          throw (IOException)new IOException
                  ("Intermediate merge failed").initCause(e);
        }

        // Note the output of the merge
        FileStatus status = localFileSys.getFileStatus(outputPath);
        synchronized (mapOutputFilesOnDisk) {
          addToMapOutputFilesOnDisk(status);
        }
      }
    }

    private class GetMapEventsThread extends Thread {
      
      private IntWritable fromEventId = new IntWritable(0);
      private static final long SLEEP_TIME = 1000;
      
      public GetMapEventsThread() {
        setName("Thread for polling Map Completion Events");
        setDaemon(true);
      }
      
      @Override
      public void run() {
      
        LOG.info(reduceTask.getTaskID() + " Thread started: " + getName());
        
        do {
          try {
            int numNewMaps = getMapCompletionEvents();
            if (numNewMaps > 0) {
              LOG.info(reduceTask.getTaskID() + ": " +  
                  "Got " + numNewMaps + " new map-outputs"); 
            }
            Thread.sleep(SLEEP_TIME);
          } 
          catch (InterruptedException e) {
            LOG.warn(reduceTask.getTaskID() +
                " GetMapEventsThread returning after an " +
                " interrupted exception");
            return;
          }
          catch (Throwable t) {
            String msg = reduceTask.getTaskID()
                         + " GetMapEventsThread Ignoring exception : " 
                         + StringUtils.stringifyException(t);
            reportFatalError(getTaskID(), t, msg);
          }
        } while (!exitGetMapEvents);

        LOG.info("GetMapEventsThread exiting");
      
      }
      
      /** 
       * Queries the {@link TaskTracker} for a set of map-completion events 
       * from a given event ID.
       * @throws IOException
       */  
      private int getMapCompletionEvents() throws IOException {
        
        int numNewMaps = 0;
        
        MapTaskCompletionEventsUpdate update = 
          umbilical.getMapCompletionEvents(reduceTask.getJobID(), 
                                           fromEventId.get(), 
                                           MAX_EVENTS_TO_FETCH,
                                           reduceTask.getTaskID());
        TaskCompletionEvent events[] = update.getMapTaskCompletionEvents();
          
        // Check if the reset is required.
        // Since there is no ordering of the task completion events at the 
        // reducer, the only option to sync with the new jobtracker is to reset 
        // the events index
        if (update.shouldReset()) {
          fromEventId.set(0);
          obsoleteMapIds.clear(); // clear the obsolete map
          mapLocations.clear(); // clear the map locations mapping
        }
        
        // Update the last seen event ID
        fromEventId.set(fromEventId.get() + events.length);
        
        // Process the TaskCompletionEvents:
        // 1. Save the SUCCEEDED maps in knownOutputs to fetch the outputs.
        // 2. Save the OBSOLETE/FAILED/KILLED maps in obsoleteOutputs to stop 
        //    fetching from those maps.
        // 3. Remove TIPFAILED maps from neededOutputs since we don't need their
        //    outputs at all.
        for (TaskCompletionEvent event : events) {
          switch (event.getTaskStatus()) {
            case SUCCEEDED:
            {
              URI u = URI.create(event.getTaskTrackerHttp());
              String host = u.getHost();
              TaskAttemptID taskId = event.getTaskAttemptId();
              int duration = event.getTaskRunTime();
              if (duration > maxMapRuntime) {
                maxMapRuntime = duration; 
                // adjust max-fetch-retries based on max-map-run-time
                maxFetchRetriesPerMap = Math.max(MIN_FETCH_RETRIES_PER_MAP, 
                  getClosestPowerOf2((maxMapRuntime / BACKOFF_INIT) + 1));
              }
              URL mapOutputLocation = new URL(event.getTaskTrackerHttp() + 
                                      "/mapOutput?job=" + taskId.getJobID() +
                                      "&map=" + taskId + 
                                      "&reduce=" + getPartition());
              List<MapOutputLocation> loc = mapLocations.get(host);
              if (loc == null) {
                loc = Collections.synchronizedList
                  (new LinkedList<MapOutputLocation>());
                mapLocations.put(host, loc);
              }
              MapOutputLocation mapOutput = 
                new MapOutputLocation(taskId, host, mapOutputLocation);
              if (rdmaSetting == 1) {
                rdmaChannel.sendFetchReq(mapOutput);
              } else {
                loc.add(mapOutput);
              }
              numNewMaps++;
            }
            break;
            case FAILED:
            case KILLED:
            case OBSOLETE:
            {
              obsoleteMapIds.add(event.getTaskAttemptId());
              LOG.info("Ignoring obsolete output of " + event.getTaskStatus() + 
                       " map-task: '" + event.getTaskAttemptId() + "'");
            }
            break;
            case TIPFAILED:
            {
              copiedMapOutputs.add(event.getTaskAttemptId().getTaskID());
              LOG.info("Ignoring output of failed map TIP: '" +  
                   event.getTaskAttemptId() + "'");
            }
            break;
          }
        }
        return numNewMaps;
      }
    }

    /* Communicating with netlev reduce task */ 
    private class DataSocket<K,V> extends Thread {
      private Socket            mSocket       = null;
      private DataOutputStream  mToMerger     = null;
      private DataInputStream   mFromMerger   = null;
      private JobConf           mJobConf      = null;
      private TaskReporter      mTaskReporter = null;    
      private Progress          mProgress     = null;
      private Vector<String>    mParams       = new Vector<String>();
      private int               mMapsNeed     = 0;      
      private int               mReqNums      = 0;
      private final int         socket_timeout= 360 * 1000; /* 6 minutes */
      private final int         mReportCount  = 20;
      private final int         SOCKET_BUF_SIZE = 1 << 22; /* 4 MB */
      private J2CQueue<K,V>     j2c_queue     = null;
      
       
      /* kv buf status */
      private final int         kv_buf_recv_ready = 1;
      private final int         kv_buf_redc_ready = 2;
      /* kv buf related vars */ 
      private final int         kv_buf_size = 1 << 20;   /* 1 MB */
      private final int         kv_buf_num = 2;
      private KVBuf[]           kv_bufs = null;
      private KVBufReceiver     kv_buf_receiver = null;

      private void init_kv_bufs() {
        kv_bufs = new KVBuf[kv_buf_num];
        for (int idx = 0; idx < kv_buf_num; ++idx) {
          kv_bufs[idx] = new KVBuf(kv_buf_size);
        } 
      }

      // avner
      private void launchCppSide(JobConf fConf) throws IOException {

          String driver = fConf.get("mapred.rdma.netmerger");
          LOG.info("J2CNexus: Launching " + driver + " Process");
          List<String> cmd = new ArrayList<String>();
         
          /* cmd */
          cmd.add(driver);
          
          /* arguments */
          cmd.add("-c");
          cmd.add(fConf.get("mapred.taskTracker.rdma.server.port"));
          cmd.add("-r");
          cmd.add(fConf.get("mapred.rdma.cma.port"));      
          cmd.add("-l");     
          cmd.add(fConf.get("mapred.netmerger.listener.port"));
          cmd.add("-a");
          cmd.add(fConf.get("mapred.netmerger.merge.approach"));
          cmd.add("-m");
          cmd.add("1");
          cmd.add("-g");
          cmd.add(fConf.get("mapred.rdma.log.dir","default"));
          cmd.add("-b");
          cmd.add(fConf.get("mapred.netmerger.rdma.num.buffers"));
          cmd.add("-s");
          cmd.add(fConf.get("mapred.rdma.buf.size"));
          cmd.add("-t");
          cmd.add(fConf.get("mapred.uda.log.tracelevel"));

    	  String[] stringarray = null;
    	  int rc = 0;
          LOG.info("J2CNexus:going to execute child: " + cmd);    	  
    	  stringarray = cmd.toArray(new String[0]);
	      try {
	    	  rc = UdaLoader.start(stringarray);
	      
	      } catch (UnsatisfiedLinkError e) {
	          LOG.warn("J2CNexus:Exception when launching child");    	  
	          LOG.warn(StringUtils.stringifyException(e));
	          throw (e);
	      }


        }

      
      public DataSocket(JobConf jobConf, TaskReporter reporter,
                        int numMaps) throws IOException {
        
    	launchCppSide(jobConf);
        /* init variables */
        init_kv_bufs(); 
        this.kv_buf_receiver = new KVBufReceiver();
        this.j2c_queue = new J2CQueue<K, V>();
        this.mJobConf = jobConf;
        this.mTaskReporter = reporter;
        this.mMapsNeed = numMaps;
        int listener = jobConf.getInt("mapred.netmerger.listener.port", 9012);
        InetAddress addr = InetAddress.getByName("localhost");
        SocketAddress sockaddr = new InetSocketAddress(addr, listener);
        this.mSocket = new Socket();
        this.mSocket.setReceiveBufferSize(SOCKET_BUF_SIZE);
        this.mSocket.connect(sockaddr);
        
        this.mSocket.setSoTimeout(socket_timeout);
        this.mToMerger = new DataOutputStream(mSocket.getOutputStream());
        this.mFromMerger = new DataInputStream(mSocket.getInputStream());
 
        /* make sure netlev reduce task is completely launched*/
        int launch_ret = WritableUtils.readVInt(this.mFromMerger);
        if (launch_ret == RDMACmd.NETLEV_REDUCE_LAUNCHED) {
          /* just simply block for now */
        }

        /* send init message */
        TaskAttemptID reduceId = reduceTask.getTaskID();
        mParams.clear();
        mParams.add(Integer.toString(numMaps));
        mParams.add(reduceId.getJobID().toString());
        mParams.add(reduceId.toString());
        
        String [] dirs = this.mJobConf.getLocalDirs();
        ArrayList<String> dirsCanBeCreated = new ArrayList<String>();
        //checking if the directories can be created
        for (int i=0; i<dirs.length; i++ ){
        try {
            DiskChecker.checkDir(new File(dirs[i].trim()));
            //saving only the directories that can be created
            dirsCanBeCreated.add(dirs[i].trim());
          } catch(DiskErrorException e) {  }
        }
        //sending the directories
        int numDirs = dirsCanBeCreated.size();
        mParams.add(Integer.toString(numDirs));
        for (int i=0; i<numDirs; i++ ){
        	mParams.add(dirsCanBeCreated.get(i));
        }
               
        String msg = RDMACmd.formCmd(RDMACmd.INIT_COMMAND, mParams);
        Text.writeString(mToMerger, msg);
        mToMerger.flush(); 
        this.mProgress = new Progress(); 
        this.mProgress.set((float)(1/2));

        setName("Thread for communicating with netmerger");
        setDaemon(true); 
      }

      public void sendFetchReq(MapOutputLocation loc) throws IOException {
        /* "host:jobid:mapid:reduce" */
        mParams.clear();
        mParams.add(loc.getHost());
        mParams.add(loc.getTaskAttemptId().getJobID().toString());
        mParams.add(loc.getTaskAttemptId().toString());
        mParams.add(Integer.toString(getPartition()));
        String msg = RDMACmd.formCmd(RDMACmd.FETCH_COMMAND, mParams); 
        Text.writeString(mToMerger, msg);
        mToMerger.flush();
        /* LOG.info("Send down to rdma " + (++mReqNums)); */
      }

      public void close() throws IOException {
        mParams.clear();
        String msg = RDMACmd.formCmd(RDMACmd.EXIT_COMMAND, mParams);
        Text.writeString(mToMerger, msg);
        mToMerger.flush();
        this.j2c_queue.close();
        this.mFromMerger.close();
        this.mToMerger.close();
        this.mSocket.close();
      }

      public <K extends Object, V extends Object>
      RawKeyValueIterator createKVIterator_rdma(
          JobConf job, FileSystem fs, Reporter reporter) 
          throws IOException {
        this.j2c_queue.initialize();
        kv_buf_receiver.start();
        return this.j2c_queue; 
      }
      
      public void run() {
        try {
          LOG.info("DataSocket: start running, numMaps = " 
                   + this.mMapsNeed);
          /* count the finished fetch map */
          int count = 0;
          while (true) {
            try {
              int ret = WritableUtils.readVInt(mFromMerger);
              if (ret == RDMACmd.FETCH_OVER_COMMAND) {
                count += mReportCount;
                mTaskReporter.progress();
                if (count >= this.mMapsNeed) {
                  break;
                }
              }
            } catch (SocketTimeoutException e) {
              mTaskReporter.progress();
              /* LOG.info("J2CQueue: fetch failed"); */
              /* break; */
              continue;
            } 
          }
                    
          /* wake up ReduceCopier */
          synchronized(ReduceCopier.this) {
            ReduceCopier.this.notify();
          }
	} catch (IOException e) {
          LOG.info("DataSocket: Error in IOException");
      	}
      }

      /* kv buf object, j2c_queue uses 
         the kv object inside.
       */
      private class KVBuf<K, V> {
        private byte[] kv_buf;
        private int act_len;
        private int status;        
        public DataInputBuffer kv;
        
        public KVBuf(int size) {
          kv_buf = new byte[size];
          kv = new DataInputBuffer();
          kv.reset(kv_buf,0);
          status = kv_buf_recv_ready;
        }
      }

      private class KVBufReceiver<K, V>
                        extends Thread {
        boolean stop;
        public KVBufReceiver() {
          stop = false;
        }
        
        private void read_from_stream(byte[] ary, int len) 
                                        throws IOException {
          int byte_len = len;
	  int offset = 0;
	  while (byte_len > 0) {
            int r;
	    while (true) {
              try {
                r = mFromMerger.read(ary, offset, byte_len);
                break;
              } catch(SocketTimeoutException e) {
                mTaskReporter.progress();
              }
            }
	    offset += r;
	    byte_len -= r;
	  }
        }
 
	public long readVLong_rdma(DataInput stream) throws IOException {
          byte[] ary = new byte[1];
          read_from_stream(ary, 1);
	  byte firstByte = ary[0];
	  int len = WritableUtils.decodeVIntSize(firstByte);
	  if (len == 1) {
	    return firstByte;
	  }
	  long i = 0;
	  for (int idx = 0; idx < len-1; idx++) {
            read_from_stream(ary, 1);
	    byte b = ary[0];
	    i = i << 8;
	    i = i | (b & 0xFF);
	  }
	  return (WritableUtils.isNegativeVInt(firstByte) ? (i ^ -1L) : i);
	}
        
        private boolean read_data(KVBuf buf) throws IOException {
          /* get merged size */ 
          int len = (int) readVLong_rdma(mFromMerger);
          if (len < 0 || len > kv_buf_size) {
            return false;
          }
          buf.act_len = len;
          read_from_stream(buf.kv_buf, len); 
	  buf.kv.reset(buf.kv_buf, 0, len); 
          return true;
        }

        public void run() {
          int cur_kv_idx = 0;
          try {
	    while (!stop) {
	      KVBuf buf = kv_bufs[cur_kv_idx];
	     
	      synchronized (buf) {
		if (buf.status != kv_buf_recv_ready) {
		  buf.wait();
		}
		if (stop) {
		  break;
		}
		read_data(buf);
		buf.status = kv_buf_redc_ready;
		++cur_kv_idx;
		if (cur_kv_idx >= kv_buf_num) {
		  cur_kv_idx = 0;
		}
		buf.notifyAll();
	      }
	    }
          } catch (InterruptedException e) {
            return;
          } catch (IOException e) {
            LOG.info("IOException in read data");
          }
          LOG.info("KV receiver stopped");
        }
      }

      private class J2CQueue<K extends Object, V extends Object> 
        implements RawKeyValueIterator {  
        
        private int  key_len;
        private int  val_len;
        private int  cur_kv_idx;
        private int  cur_dat_len;
        private int  time_count;
        private DataInputBuffer key;
        private DataInputBuffer val;
        private DataInputBuffer cur_kv = null;

        public J2CQueue() {
          cur_kv_idx = -1;
          cur_dat_len= 0;
          key_len  = 0;
          val_len  = 0;
          key = new DataInputBuffer();
          val = new DataInputBuffer();
        } 

        private boolean move_to_next_kv() {
          if (cur_kv_idx >= 0) {
            KVBuf finished_buf = kv_bufs[cur_kv_idx];
            synchronized (finished_buf) {
              finished_buf.status = kv_buf_recv_ready;
              finished_buf.notifyAll();
            }
          }
          
          ++cur_kv_idx;
          if (cur_kv_idx >= kv_buf_num) {
            cur_kv_idx = 0;
          }
          KVBuf next_buf = kv_bufs[cur_kv_idx];
         
          try { 
	    synchronized (next_buf) {
	      if (next_buf.status != kv_buf_redc_ready) {
		next_buf.wait();
	      }
	      cur_kv = next_buf.kv;
	      cur_dat_len = next_buf.act_len;
	      key_len = 0;
	      val_len = 0;
	    } 
          } catch (InterruptedException e) {
          }
          return true;
        }

        public void initialize() {
          time_count = 0;
        } 
 
        public DataInputBuffer getKey() 
                      throws IOException {
          return key;
        }
       
        public DataInputBuffer getValue() 
                      throws IOException {
          return val;
        }

        public boolean next() throws IOException {
	  if (key_len < 0 || val_len < 0) {
	    return false;
	  }        

	  if (cur_kv == null
           || cur_kv.getPosition() >= (cur_dat_len - 1)) {
	     move_to_next_kv(); 
	  }  

	  if (time_count > 1000) {
	    mTaskReporter.progress();
	    time_count = 0;
	  }
	  time_count++; 

          try {
	    key_len = WritableUtils.readVInt(cur_kv);
	    val_len = WritableUtils.readVInt(cur_kv); 
          } catch (java.io.EOFException e) {
            return false;
          }

	  if (key_len < 0 || val_len < 0) {
	    return false;
	  }

	  /* get key */
	  this.key.reset(cur_kv.getData(), 
                         cur_kv.getPosition(), 
                         key_len);
	  cur_kv.skip(key_len);
	  
	  /* get val */
	  this.val.reset(cur_kv.getData(), 
                         cur_kv.getPosition(), 
                         val_len);
	  cur_kv.skip(val_len);
	 
	  return true;
        }

        public void close() throws IOException {
          kv_buf_receiver.stop = true;
          for (int i = 0; i < kv_buf_num; ++i) {
            KVBuf buf = kv_bufs[i];
            synchronized (buf) {
              buf.notifyAll();
            }
          }
        }
         
        public Progress getProgress() {
          return mProgress;
        }
      
      }
    }
    /* The above is for netlev reduce task java side */
  }

  /**
   * Return the exponent of the power of two closest to the given
   * positive value, or zero if value leq 0.
   * This follows the observation that the msb of a given value is
   * also the closest power of two, unless the bit following it is
   * set.
   */
  private static int getClosestPowerOf2(int value) {
    if (value <= 0)
      throw new IllegalArgumentException("Undefined for " + value);
    final int hob = Integer.highestOneBit(value);
    return Integer.numberOfTrailingZeros(hob) +
      (((hob >>> 1) & value) == 0 ? 0 : 1);
  }

}
