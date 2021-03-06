/*
** Copyright (C) 2012 Auburn University
** Copyright (C) 2012 Mellanox Technologies
** 
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at:
**  
** http://www.apache.org/licenses/LICENSE-2.0
** 
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
** either express or implied. See the License for the specific language 
** governing permissions and  limitations under the License.
**
**
*/

#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <sys/time.h>
#include "MergeQueue.h"
#include "MergeManager.h"
#include "StreamRW.h"
#include "reducer.h"
#include "IOUtility.h"
#include "C2JNexus.h"
#include "UdaBridge.h"
#include "AIOHandler.h"
#include "bullseye.h"


#define LPQ_STAGE_MEM_SIZE (1<<20)
#define LCOV_HYBRID_MERGE_DEAD_CODE 0


        



/* report progress every 256 map outputs*/
#define PROGRESS_REPORT_LIMIT 20

// -----------------------------------------------------------------------------
void *merge_do_fetching_phase (reduce_task_t *task, SegmentMergeQueue *merge_queue, int num_maps/*-to-fetch*/)
{
    MergeManager *manager = task->merge_man;
    int target_maps_count = manager->total_count + num_maps;
    int maps_sent_to_fetch = 0;
    memory_pool_t *mem_pool = &(task->getMergingSm()->mop_pool);
    log(lsDEBUG, ">> function started task->num_maps=%d target_maps_count=%d", task->num_maps, target_maps_count);

    static JNIEnv *s_fetcherJniEnv = UdaBridge_threadGetEnv();
    static std::vector<client_part_req *> fetch_vector;

	do {
		//sending fetch requests
		log(lsDEBUG, "sending first chunk fetch requests");
		list_shuffle_in_vector<client_part_req *>(fetch_vector, manager->fetch_list,
			&manager->lock); // move list items to back of vector and shuffle vector
		size_t n = fetch_vector.size();
		for (size_t i = 0; i < n && maps_sent_to_fetch < num_maps; ++i) {

			if (mem_pool->free_descs.next != &mem_pool->free_descs) { // the list represents a pair of buffers && (mem_pool->free_descs.next->next != &mem_pool->free_descs)){
				log(lsTRACE, "there are free RDMA buffers");
				client_part_req *fetch_req = fetch_vector.back();
				 fetch_vector.pop_back();
				if (fetch_req) {
					log(lsDEBUG, "request as received from java jobid=%s, mapid=%s, reduceid=%s, hostname=%s", fetch_req->info->params[1], fetch_req->info->params[2], fetch_req->info->params[3], fetch_req->info->params[0]);
					manager->allocate_rdma_buffers(fetch_req);
					manager->start_fetch_req(fetch_req);
					maps_sent_to_fetch ++;
				}
				else {
					log(lsERROR, "no fetch request, although there should be");
				}
			}else{
				if (maps_sent_to_fetch < num_maps){
					throw new UdaException("there are not enough free RDMA buffers to start an LPQ");
					return NULL;
					// TODO: wait for buffers
				}
				else {
					log(lsINFO, "there are no free RDMA buffers; however, there are enough fetches sent to start an LPQ");
					// TODO: should not reach here with new code
					break;
				}
			}
		}

		while (! manager->fetched_mops.empty() ) {
			log(lsDEBUG, "hadling fetched mops");
			MapOutput *mop = NULL;

			pthread_mutex_lock(&manager->lock);
		    log(lsTRACE, "after pthread_mutex_lock");
			mop = manager->fetched_mops.front();
			manager->fetched_mops.pop_front();
			pthread_mutex_unlock(&manager->lock);

			log(lsTRACE, "found new segment in list");
			/* not in queue yet */
			if (manager->mops_in_queue.find(mop->mop_id)
				== manager->mops_in_queue.end()) {

				manager->mops_in_queue.insert(mop->mop_id);
				Segment *segment = new Segment(mop);

				if (task->isCompressionOff()){
					segment->send_request(); // send req for second buffer

				}

				// the above send was called from the Segment's Ctor before, but now because it is a virtual method it canot be called from CTOR
				merge_queue->insert(segment);

				/* report */
				manager->total_count++;
				manager->progress_count++;
				log(lsDEBUG, "   === F segment was inserted: manager->total_count=%d, task->num_maps=%d", manager->total_count, task->num_maps);

				if (manager->progress_count == PROGRESS_REPORT_LIMIT
				 || manager->total_count == task->num_maps) {
					log(lsDEBUG, "JNI sending fetchOverMessage...");
					UdaBridge_invoke_fetchOverMessage_callback(s_fetcherJniEnv);

					manager->progress_count = 0;
				}

				if (manager->total_count == target_maps_count) {
					break;
				}
			}
		}

		if (manager->total_count == target_maps_count) break;

		pthread_mutex_lock(&manager->lock);
		if (! manager->fetched_mops.empty() || ! manager->fetch_list.empty()) {
			pthread_mutex_unlock(&manager->lock);
			continue;
		}
	    log(lsTRACE, "before pthread_cond_wait");
		pthread_cond_wait(&manager->cond, &manager->lock);
		pthread_mutex_unlock(&manager->lock);

	} while (!task->merge_thread.stop);

    log(lsDEBUG, "<< function finished");
    return NULL;
}

void *merge_do_merging_phase (reduce_task_t *task, SegmentMergeQueue *merge_queue)
{
	/* merging phase */
	JNIEnv *mergerJniEnv = UdaBridge_threadGetEnv();

	// register our staging_buf as DirectByteBuffer for sharing with Java
	mem_desc_t  *desc = merge_queue->staging_bufs[0];  // we only need 1 staging_bufs: TODO: remove the array
	jobject jbuf = UdaBridge_registerDirectByteBuffer(mergerJniEnv, desc->buff, desc->buf_len);
	log(lsDEBUG, "GOT: desc=%p, jbuf=%p, address=%p, capacity=%d", desc, jbuf, desc->buff, desc->buf_len);


	bool b = false;
	while (!task->merge_thread.stop && !b) {

		log(lsDEBUG, "calling write_kv_to_mem desc->buf_len=%d", desc->buf_len);
		b = write_kv_to_mem(merge_queue, desc->buff, desc->buf_len, desc->act_len);

    	log(lsDEBUG, "MERGER: invoking java callback: desc=%p, desc->jbuf=%p, address=%p, capacity=%d act_len=%d", desc, jbuf, desc->buff, desc->buf_len, desc->act_len);
		UdaBridge_invoke_dataFromUda_callback(mergerJniEnv, jbuf, desc->act_len);
	}

	log(lsDEBUG, "invoking DeleteWeakGlobalRef: desc=%p, jbuf=%p, address=%p, capacity=%d", desc, jbuf, desc->buff, desc->buf_len);
	mergerJniEnv->DeleteWeakGlobalRef((jweak)jbuf);
	log(lsDEBUG, "After DeleteWeakGlobalRef");

	log(lsINFO, "----- merger thread completed ------");
    return NULL;
}

void *merge_online (reduce_task_t *task)
{
	log(lsINFO, "Merge online"); 
	merge_do_fetching_phase(task, task->merge_man->merge_queue, task->num_maps);

	log(lsDEBUG, "Enter into merging phase");
	merge_do_merging_phase(task, task->merge_man->merge_queue);
	log(lsDEBUG, "merge thread exit");
    return NULL;
}

void resetBaseSegment(void * _segment){
	log(lsDEBUG, "resetBaseSegment started");
	BaseSegment * segment = (BaseSegment*) _segment;
	segment->getKVOUutput()->returnToPool();
	log(lsDEBUG, "resetBaseSegment finished");
}

void MergeManager::fetch_lpqs (){

	// lpq_shared_counter is not really shared between reducers since - with JNI - UDA is instantiated per RT. Hence, we use random
    timeval tv;
    gettimeofday(&tv, NULL);
    srand(tv.tv_usec);
	static int lpq_shared_counter = rand() % task->local_dirs.size();

	char temp_file[PATH_MAX];
	for (int i = 0; task->merge_man->total_count < task->num_maps; ++i)
	{
		int num_to_fetch = (i < num_regular_lpqs) ? num_mofs_in_lpq : max_mofs_in_lpqs;
		log(lsINFO, "====== [F %d/%d] Creating LPQ for %d segments (already fetched=%d; num_maps=%d)", i, task->merge_man->num_lpqs, num_to_fetch, task->merge_man->total_count, task->num_maps);

		int local_counter = ++lpq_shared_counter; // not critical to sync between threads here
		local_counter %= task->local_dirs.size();
		const string & dir = task->local_dirs[local_counter]; //just ref - no copy
		sprintf(temp_file, "%s/uda.%s.lpq-%03d", dir.c_str(), task->reduce_task_id, i);
		SegmentMergeQueue *lpq = new SegmentMergeQueue(num_to_fetch, NULL, temp_file, resetBaseSegment);

		log(lsINFO, "   === [F %d/%d] wait on reserve quota for LPQ with %d segments ", i, this->num_lpqs, num_to_fetch);
		pendingMerge->wait_and_reserve();
		log(lsINFO, "   === [F %d/%d] after wait", i, this->num_lpqs);
		merge_do_fetching_phase(task, lpq, num_to_fetch);
		pendingMerge->push_reserved(lpq);
		log(lsINFO, "   === [F %d/%d] after reserving and pushing LPQ", i, this->num_lpqs);
	}

	log(lsINFO, "   === F ALL LPQs completed their fetching phase");

}

/*static*/void *MergeManager::lpq_fetcher_start (void *context) throw (UdaException*){
	MergeManager *_this = (MergeManager*)context;
	_this->fetch_lpqs();
	return NULL;
}

void *MergeManager::merge_hybrid ()
{
	if (task->num_maps < this->num_lpqs) return merge_online(task);
	this->pendingMerge = new concurrent_external_quota_queue <SegmentMergeQueue*>(this->num_parallel_lpqs);
	pthread_t thr;
	uda_thread_create(&thr, NULL, lpq_fetcher_start, this);

	SegmentMergeQueue* merge_lpq[this->num_lpqs];
	bool b = true;
	int32_t total_write;

	for (int i = 0; i < this->num_lpqs; ++i) {
		log(lsINFO, "[M %d] ====== waiting on pop for LPQ", i);
		pendingMerge->wait_and_pop_without_dereserve(merge_lpq[i]);
		log(lsINFO, "[M %d]    === after  pop - going to merge LPQ using file: %s", i, merge_lpq[i]->filename.c_str());

		b = write_kv_to_file(merge_lpq[i], merge_lpq[i]->filename.c_str(), total_write);
		log(lsINFO, "[M %d]   === after merge of LPQ b=%d, total_write=%d; clearing and de-reserving...", i, (int)b, total_write);
		merge_lpq[i]->core_queue.clear(); // sanity return RDMA buffers to pool (actually the segments were already released)

		pendingMerge->dereserve();
		log(lsINFO, "[M %d]    === after dereserve", i);
	}

	log(lsINFO, "=== MM ALL LPQs entirely completed.  Building RPQ...");
	// turn compression off in case it was on, since currently RPQ is always without compression
	compressionType _comp_alg = task->resetCompression();
	for (int i = 0; i < this->num_lpqs ; ++i)
	{
		log(lsINFO, "[M %d] === inserting LPQ to RPQ using file: %s", i, merge_lpq[i]->filename.c_str());
		task->merge_man->merge_queue->insert(new SuperSegment(task, merge_lpq[i]->filename.c_str()));
		log(lsINFO, "[M %d] === after insertion of LPQ into RPQ", i);
	}

	//TODO: probably can be joined with previous loop
	for (int i = 0; i < this->num_lpqs ; ++i)
	{
		delete merge_lpq[i];
	}

	log(lsINFO, "MM RPQ phase: going to merge all LPQs...");
	merge_do_merging_phase(task, this->merge_queue);
	log(lsINFO, "MM after ALL merge");
	// merge_queue will be deleted in DTOR of MergeManager

	task->setCompressionType(_comp_alg);
	log(lsINFO, "MM compression state was restored");
    return NULL;
}

//COVERITY: UNCAUGHT_EXCEPT, RM#189300. false alarm
void *MergeManager::merge_thread_main (void *context) throw (UdaException*)
{
	reduce_task_t *task = (reduce_task_t *) context;
	MergeManager *_this = task->merge_man;

	int online = _this->online;
	log(lsDEBUG, "online=%d; task->num_maps=%d", online, task->num_maps);

	switch (online) {
	case 0:
		/* FIXME: on-disk merge*/
		break;
	case 1:
		merge_online (task);
		break;
	case 2: default:
		log(lsINFO, "using hybrid merge");
		_this->merge_hybrid ();
		break;
	}

	log(lsDEBUG, "finished !!!");
    return NULL;
}

/* The following is for MergeManager */
MergeManager::MergeManager(int threads, int online, struct reduce_task *task, int _num_lpqs) :
		num_lpqs(_num_lpqs),
		num_mofs_in_lpq(task->num_maps/num_lpqs),
		max_mofs_in_lpqs(num_mofs_in_lpq+1),
		num_regular_lpqs(num_lpqs - task->num_maps%num_lpqs), // rest lpqs will have one more mof
		pendingMerge(NULL)
{

    this->task = task;
    this->online = online;
    this->flag = INIT_FLAG;

    this->total_count = 0;
    this->progress_count = 0;
    this->merge_queue = NULL;

    string value = UdaBridge_invoke_getConfData_callback("mapred.rdma.num.parallel.lpqs", "0");
    int num_parallel_lpqs = atoi(value.c_str());
    this->num_parallel_lpqs = (num_parallel_lpqs < MIN_PARALLEL_LPQS) ? MIN_PARALLEL_LPQS : num_parallel_lpqs;

    num_kv_bufs = this->online == 2 ? // 2 is hybrid_merge
			this->max_mofs_in_lpqs * this->num_parallel_lpqs : this->task->num_maps;

    pthread_mutex_init(&this->lock, NULL);
    pthread_cond_init(&this->cond, NULL); 
    
    if (online) {    

    	if (online == 1) {
    		merge_queue = new SegmentMergeQueue(task->num_maps);
    	}
    	else { //online == 2
    		log(lsINFO, "hybrid merge will use %d lpqs", num_lpqs);
    		merge_queue = new SegmentMergeQueue(num_lpqs);
    		log(lsINFO, "====== num_maps=%d; num_lpqs=%d; num_mofs_in_lpq=%d, max_mofs_in_lpqs=%d, num_regular_lpqs=%d, num_kv_bufs=%d, this->num_parallel_lpqs=%d",
    				task->num_maps, num_lpqs, num_mofs_in_lpq, max_mofs_in_lpqs, num_regular_lpqs, num_kv_bufs, this->num_parallel_lpqs);
    	}

        /* get staging mem from memory_pool*/
        pthread_mutex_lock(&task->kv_pool.lock);
        for (int i = 0; i < NUM_STAGE_MEM; ++i) {
            merge_queue->staging_bufs[i] = 
              list_entry(task->kv_pool.free_descs.next,
                         typeof(*merge_queue->staging_bufs[i]), list);
            list_del(&merge_queue->staging_bufs[i]->list);
        }
        pthread_mutex_unlock(&task->kv_pool.lock);
    }
}

int MergeManager::update_fetch_req(client_part_req_t *req)
{
    /*
     * 1. mark memory available again
     * 2. increase MOF offset and set length
     */
    uint64_t recvd_data[4];
    int i = 0;
    int j = 0;

    /* format: "rawlength:partlength:recv_data:mofoffset:mofpath" */
    recvd_data[0] = atoll(req->recvd_msg);
    while (req->recvd_msg[i] != ':' ) { ++i; }
    recvd_data[1] = atoll(req->recvd_msg + (++i));
    while (req->recvd_msg[i] != ':' ) { ++i; }
    recvd_data[2] = atoll(req->recvd_msg + (++i));
    while (req->recvd_msg[i] != ':' ) { ++i; }
    recvd_data[3] = atoll(req->recvd_msg + (++i));
    while (req->recvd_msg[i] != ':' ) { ++i; }
    j = i+1;
    while (req->recvd_msg[j] != ':' ) { ++j; }
    int size = j-i-1;

    pthread_mutex_lock(&req->mop->lock);
    /* set variables in map output */
    req->mop->last_fetched = recvd_data[2];
    req->mop->total_len_rdma = recvd_data[1];
    req->mop->total_len_uncompress = recvd_data[0];
    req->mop->fetched_len_rdma += recvd_data[2];
    req->mop->mofOffset = recvd_data[3];
    req->mop->mofPath.assign((req->recvd_msg + (++i)), size);

    pthread_mutex_unlock(&req->mop->lock);

    if (req->mop->mofPath.compare("MOF_PATH_SIZE_TOO_LONG") == 0) {
    	throw new UdaException("Mof path is too long, UDA supports path of max 600 chars");
    }

    log(lsTRACE, "update_fetch_req total_len_part=%lld total_len_raw=%lld req->mop->total_fetched_compressed=%lld req->last_fetched=%lld req->mop->mop_id=%d, req->mop->mofOffset=%lld, req->mop->mofPath=%s",
    		(long long)req->mop->total_len_rdma, (long long)req->mop->total_len_uncompress, (long long)req->mop->fetched_len_rdma, (long long)recvd_data[2], req->mop->mop_id, (long long)req->mop->mofOffset, req->mop->mofPath.c_str());

    return 1;
}

void MergeManager::mark_req_as_ready(client_part_req_t *req)
{

	pthread_mutex_lock(&req->mop->lock);
    req->mop->mop_bufs[req->mop->staging_mem_idx]->status = MERGE_READY;
	if (req->mop->fetch_count != 0){
		log(lsTRACE, "broadcasting id=%d", req->mop->mop_id);
        pthread_cond_broadcast(&req->mop->cond);
	}
    pthread_mutex_unlock(&req->mop->lock);

	if (req->mop->fetch_count == 0) {
        // Insert into merge manager fetched_mops
		log(lsTRACE, "Inserting into merge manager fetched_mops...");
        pthread_mutex_lock(&this->lock);
        this->fetched_mops.push_back(req->mop);
        pthread_cond_broadcast(&this->cond);
        pthread_mutex_unlock(&this->lock);
	}
}

void MergeManager::allocate_rdma_buffers(client_part_req_t *req)
{
	BULLSEYE_EXCLUDE_BLOCK_START
    if (!req->mop) {
    	req->mop = new MapOutput(this->task);
        req->mop->part_req = req;
        req->mop->fetch_count = 0;
        task->total_first_fetch += 1;
    }
    BULLSEYE_EXCLUDE_BLOCK_END
}


void MergeManager::start_fetch_req(client_part_req_t *req)
{
	int ret;
	if (this->task->isCompressionOn()){
		req->mop->mop_bufs[0]->status = BUSY;
		ret = task->client->start_fetch_req(req, req->mop->mop_bufs[0]->buff, req->mop->mop_bufs[0]->buf_len);
	}else{
		req->mop->mop_bufs[req->mop->staging_mem_idx]->status = BUSY;
		ret = task->client->start_fetch_req(req, req->mop->mop_bufs[req->mop->staging_mem_idx]->buff, req->mop->mop_bufs[req->mop->staging_mem_idx]->buf_len);
	}

    if ( ret == 0 ) {
        if (req->mop->fetch_count == 0) {
        	log(lsDEBUG,
                     "First time fetch: %d destination: %s",
                      task->total_first_fetch,
                      req->info->params[0]);
        }
    } else if(ret == -2) {
        if (req->mop->fetch_count == 0) {
        	log(lsDEBUG,
                     "First time fetch request is in backlog: %d",
                     task->total_first_fetch);
        }
    } else {
        if (req->mop->fetch_count == 0) {
        	log(lsERROR,"First time fetch is lost %d",task->total_first_fetch);
        }
        throw new UdaException("Error in MergeManager::start_fetch_req");
    }
}



MergeManager::~MergeManager()
{
    pthread_mutex_destroy(&lock);
    pthread_cond_destroy(&cond);
    
    BULLSEYE_EXCLUDE_BLOCK_START
    if (merge_queue != NULL ) {
        pthread_mutex_lock(&task->kv_pool.lock);
        for (int i = 0; i < NUM_STAGE_MEM; ++i) {
            mem_desc_t *desc = 
                merge_queue->staging_bufs[i];
            pthread_cond_broadcast(&desc->cond); 
            list_add_tail(&desc->list,
                          &task->kv_pool.free_descs);
        }
        pthread_mutex_unlock(&task->kv_pool.lock);

        merge_queue->core_queue.clear(); //TODO: this should be moved into ~MergeQueue()
        delete merge_queue; 
    }
    delete pendingMerge;
    BULLSEYE_EXCLUDE_BLOCK_END
}

#if LCOV_HYBRID_MERGE_DEAD_CODE

int aio_lpq_write_completion_handler(void* data, int status) {
	lpq_aio_arg* arg = (lpq_aio_arg*)data;

	pthread_mutex_lock(&arg->desc->lock);
	arg->desc->status = FETCH_READY;
	if (arg->last_lpq_submition) { // wrting last part of lpq output with blocking write (without AIO) because AIO must write aligned size
		close(arg->fd); // reopen file because it was opened for AIO with O_DIRECT which forbiddens calling sync lseek
		arg->fd = open(arg->filename, O_WRONLY);
		if (arg->fd < 0)
		{
			log(lsERROR, "Could not open file %s for writing last lpq output (without AIO), Aborting Task.", arg->filename);
			throw new UdaException("UDA Could not open file for writing");
		}
		lseek(arg->fd, arg->aligment_carry_fileOffset, SEEK_SET);
		write(arg->fd, arg->aligment_carry_buff, arg->aligment_carry_size);
		close(arg->fd);
		log(lsTRACE, "IDAN_LPQ_WAIT last completion for fd=%d", arg->fd);
	}
	pthread_cond_broadcast(&arg->desc->cond);
	pthread_mutex_unlock(&arg->desc->lock);

	delete arg;

	return 0;
}

int aio_rpq_read_completion_handler(void* data, int status) {
	rpq_aio_arg* arg = (rpq_aio_arg*)data;

	pthread_mutex_lock(&arg->kv_output->lock);
	arg->kv_output->last_fetched = arg->size;
	arg->kv_output->total_fetched += arg->size;
	arg->desc->status = MERGE_READY;
	log(lsTRACE, "IDAN RPQ READ completion: size=%llu total_fetched=%lld", arg->size, arg->kv_output->total_fetched);
	pthread_cond_broadcast(&arg->kv_output->cond);
	pthread_mutex_unlock(&arg->kv_output->lock);

	// if this is the first completion for the segment
	if (arg->kv_output->total_fetched == arg->size) {
		log(lsTRACE, "IDAN RPQ Segment's first completion");

		// after completion for first buff , send second buff aio request
		arg->segment->send_request();

		// after first buffer aio read is completed, it is possible to insert the segment to PQ for merging
		arg->kv_output->task->merge_man->merge_queue->insert(arg->segment);

		// merge manager is waiting for all first completions of RPQ's AioSegments to complete , for starting RPQ merge phase
		pthread_mutex_lock(&arg->kv_output->task->merge_man->lock);
		pthread_cond_broadcast(&arg->kv_output->task->merge_man->cond);
		pthread_mutex_unlock(&arg->kv_output->task->merge_man->lock);
	}

	delete arg;
	return 0;
}

// hybrid merge - LPQs phase: executes merge of RDMA segments with LPQs to disk, one after another,  + using AIO
void merge_hybrid_lpq_phase(AIOHandler* aio, SegmentMergeQueue* merge_lpqs[], reduce_task_t* task)
{
	char temp_file[PATH_MAX];
	int mem_desc_idx=0;
	bool b = true;
	int32_t total_write;

	const int regular_lpqs = task->merge_man->num_lpqs > 1 ?  task->merge_man->num_lpqs - 1 : 1; // all lpqs but the 1st will have same number of segments
	int num_to_fetch = 0;
	int subsequent_fetch = 0;
	if (task->lpq_size > 0) {
		subsequent_fetch = task->lpq_size;
		num_to_fetch = task->num_maps % task->lpq_size;
		if (num_to_fetch <= 1) // in case of 1 segment left, 1st lpq will merge it
			num_to_fetch += subsequent_fetch;
	}
	else if (task->num_maps % regular_lpqs) {
		num_to_fetch = task->num_maps % regular_lpqs; //1st lpq will be smaller than all others
		subsequent_fetch = task->num_maps / regular_lpqs;
	}
	else {
		subsequent_fetch = task->num_maps / task->merge_man->num_lpqs; // can't use previous attitude
		num_to_fetch = subsequent_fetch + task->num_maps % task->merge_man->num_lpqs; // put the extra segments in 1st lpq
	}

	int min_number_rdma_buffers = max(subsequent_fetch, num_to_fetch)*2;

    if (min_number_rdma_buffers > task->getMergingSm()->mop_pool.num){
    	log(lsERROR, "there are not enough rdma buffers! please allocate at least %d ", min_number_rdma_buffers);
        throw new UdaException("there are not enough rdma buffers!");
    }

    // allocating aligned buffer for staging mem
    char* staging_row_mem;
    int total_stating_size = NUM_STAGE_MEM * LPQ_STAGE_MEM_SIZE;
    int rc = posix_memalign((void**)&staging_row_mem,  AIO_ALIGNMENT, total_stating_size);
    if (rc) {
    	log(lsERROR, "failed to allocate memory for LPQs stating buffer. posix_memalign failed: alignment=%d , total_size=%ll --> rc=%d %m", AIO_ALIGNMENT, total_stating_size, rc );
        throw new UdaException("failed to allocate memory for LPQs stating buffer. posix_memalign failed");
    }

    // creating one set of staging mem for all LPQs - TODO: on future non-blocking LPQs, will need a set of stating mem for each LPQ
	mem_desc_t* staging_descs = new mem_desc_t[NUM_STAGE_MEM];
    for (int i = 0; i < NUM_STAGE_MEM; ++i) {
        staging_descs[i].buff  = staging_row_mem + i*LPQ_STAGE_MEM_SIZE;
        staging_descs[i].buf_len = LPQ_STAGE_MEM_SIZE;
        staging_descs[i].owner = NULL;
        staging_descs[i].status = INIT;
        pthread_mutex_init(&staging_descs[i].lock, NULL);
        pthread_cond_init(&staging_descs[i].cond, NULL);
    }

    // no more shared index for all reducers as a result of running each R with a different process (JNI)
    // therfore, using random index for first local dir index
    timeval tv;
    tv.tv_usec=0;
    gettimeofday(&tv, NULL);
    srand(tv.tv_usec);
	int local_dir_index = rand() % task->local_dirs.size();
	log(lsDEBUG, "start with loc"
			"al_dir_index=%d", local_dir_index);
	for (int i = 0; task->merge_man->total_count < task->num_maps; ++i)
	{
		log(lsDEBUG, "====== [%d] Creating LPQ for %d segments (already fetched=%d; num_maps=%d)", i, num_to_fetch, task->merge_man->total_count, task->num_maps);

		const string & dir = task->local_dirs[local_dir_index]; //just ref - no copy
		sprintf(temp_file, "%s/NetMerger.%s.lpq-%d", dir.c_str(), task->reduce_task_id, i);
		merge_lpqs[i] = new SegmentMergeQueue(num_to_fetch, staging_descs, temp_file);
		merge_do_fetching_phase(task, merge_lpqs[i], num_to_fetch);
		log(lsDEBUG, "[%d] === Enter merging LPQ using file: %s", i, merge_lpqs[i]->filename.c_str());
		merge_lpq_to_aio_file(task, merge_lpqs[i], merge_lpqs[i]->filename.c_str(), aio , total_write, mem_desc_idx);
		log(lsDEBUG, "===after merge of LPQ b=%d, total_write=%d", (int)b, total_write);
		// end of block from previous loop

		++local_dir_index;
		local_dir_index %= task->local_dirs.size();
		num_to_fetch = subsequent_fetch;
	}

    log(lsTRACE, "IDAN_LPQ after merge loop - AIO ONAIR=%d", aio->getOnAir());

	// wait for aio to complete all LPQs outputs async write
	while(aio->getOnAir() > 0)  {
		mem_desc_idx = (mem_desc_idx == NUM_STAGE_MEM - 1) ? 0 : (mem_desc_idx + 1) ;
		pthread_mutex_lock(&staging_descs[mem_desc_idx].lock);
		if (aio->getOnAir() > 0) {
			log(lsTRACE, "IDAN_LPQ_WAIT REDUCEID=%s waiting for lpqs AIO: ONAIR=%d", task->reduce_task_id ,aio->getOnAir());
			if (staging_descs[mem_desc_idx].status != FETCH_READY)
				pthread_cond_wait(&staging_descs[mem_desc_idx].cond, &staging_descs[mem_desc_idx].lock);
	}
		pthread_mutex_unlock(&staging_descs[mem_desc_idx].lock);
	}

	// delete LPQs staging buffers
	log(lsTRACE, "IDAN LPQ - destrying staging buffers's cond&mutex");
    for (int i = 0; i < NUM_STAGE_MEM; ++i) {
        if ((rc=pthread_cond_destroy(&staging_descs[i].cond))) {
        	log(lsERROR, "Faile to destroy pthread_cond - rc=%d", rc);
        }
        if ((rc=pthread_mutex_destroy(&staging_descs[i].lock))) {
        	log(lsERROR, "Faile to destroy pthread_mutex - rc=%d", rc);
        }
    }
	log(lsTRACE, "IDAN LPQ - deleting staging buffers");
	delete[] staging_descs;
    free(staging_row_mem);

}




// hybrid merge - RPQ phase: executes merge of LPQs spilled outputs to JAVA by using AIO to read spills and nexuses to write to JAVA stream
void merge_hybrid_rpq_phase(AIOHandler* aio, SegmentMergeQueue* merge_lpqs[], reduce_task_t* task)
{
	AioSegment** rpqSegmentsArr = new AioSegment*[task->merge_man->num_lpqs];

	for (int i = 0; i < task->merge_man->num_lpqs ; ++i)
	{
		log(lsDEBUG, "[%d] === creating AioSegment for RPQ using file: %s", i, merge_lpqs[i]->filename.c_str());

		// create AioSegment for RPQ
		rpqSegmentsArr[i] = new AioSegment(new KVOutput(task), aio , merge_lpqs[i]->filename.c_str());

		// send first aio request
		rpqSegmentsArr[i]->send_request();

		// delete LPQ after inserting AioSegment to RPQ
		merge_lpqs[i]->core_queue.clear();
		delete merge_lpqs[i];

	}

	// wait for RPQ's first aio read completions of all LPQs outputs
	pthread_mutex_lock(&task->merge_man->lock);
	while (task->merge_man->merge_queue->getQueueSize() < task->merge_man->num_lpqs) {
		log(lsTRACE, "IDAN_LPQ_WAIT REDUCEID=%s waiting for RPQ first AIO reads: current on Queue:%d num_lpqs=%d", task->reduce_task_id, task->merge_man->merge_queue->getQueueSize(), task->merge_man->num_lpqs);
		pthread_cond_wait(&task->merge_man->cond, &task->merge_man->lock);
	}
	log(lsTRACE, "IDAN_LPQ_WAIT REDUCEID=%s all first RPQ's AIO read completed (%d)", task->reduce_task_id ,task->merge_man->num_lpqs);
	pthread_mutex_unlock(&task->merge_man->lock);


	log(lsDEBUG, "RPQ phase: going to merge all LPQs...");
	merge_do_merging_phase(task, task->merge_man->merge_queue);
	log(lsDEBUG, "after ALL merge");

	delete[] rpqSegmentsArr;

}

void *merge_hybrid (reduce_task_t *task)
{
	// in case we have K segments and lpq_size=K+1 then we will do online merge insterad of  hybtid with 2 lpqs: one with K segments and second with 1 segment only.
	if ((task->num_maps < task->merge_man->num_lpqs) || (task->num_maps <= task->lpq_size + 1) || (task->merge_man->num_lpqs < 2))
		return merge_online(task);

    // Setup AIO context
	int max_simultaniesly_aios= (NUM_STAGE_MEM * task->merge_man->num_lpqs);
	int max_events= max(MERGE_AIOHANDLER_CTX_MAXEVENTS, (2 * max_simultaniesly_aios)); // x2 to be on safe side
	int nr=max(MERGE_AIOHANDLER_NR, max_simultaniesly_aios); // represent the max amount of aio events pulled&callbacked for one call of io_getevents
	timespec timeout;
    timeout.tv_nsec=MERGE_AIOHANDLER_TIMEOUT_IN_NSEC;
    timeout.tv_sec=0;
	log(lsINFO, "AIO: creating new AIOHandler with maxevents=%d , min_nr=%d, nr=%d timeout=%ds %ldns", max_events, MERGE_AIOHANDLER_MIN_NR, nr , timeout.tv_sec, timeout.tv_nsec );
	AIOHandler* aio = new AIOHandler(aio_lpq_write_completion_handler, max_events, MERGE_AIOHANDLER_MIN_NR , MERGE_AIOHANDLER_NR, &timeout );
    aio->start();

    SegmentMergeQueue* merge_lpqs[task->merge_man->num_lpqs];
	merge_hybrid_lpq_phase(aio, merge_lpqs, task);
	log(lsINFO, "=== REDUCEID=%s ALL LPQs completed  building RPQ...", task->reduce_task_id);
	aio->setCompletionCallback(aio_rpq_read_completion_handler);
	merge_hybrid_rpq_phase(aio, merge_lpqs, task);

	delete aio;
	log(lsINFO, "hybrid merge finish");
    return NULL;
}
#endif

/*
 * Local variables:
 *  c-indent-level: 4
 *  c-basic-offset: 4
 * End:
 *
 * vim: ts=4 sw=4 hlsearch cindent expandtab 
 */
