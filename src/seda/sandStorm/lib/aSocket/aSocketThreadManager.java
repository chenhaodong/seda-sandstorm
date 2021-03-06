/* 
 * Copyright (c) 2000 by Matt Welsh and The Regents of the University of 
 * California. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this software and its
 * documentation for any purpose, without fee, and without written agreement is
 * hereby granted, provided that the above copyright notice and the following
 * two paragraphs appear in all copies of this software.
 * 
 * IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
 * DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES ARISING OUT
 * OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF
 * CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
 * ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATION TO
 * PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
 *
 * Author: Matt Welsh <mdw@cs.berkeley.edu>
 * 
 */

package seda.sandStorm.lib.aSocket;

import seda.sandStorm.api.*;
import seda.sandStorm.api.internal.*;
import seda.sandStorm.core.*;
import seda.sandStorm.internal.*;
import seda.sandStorm.main.*;
import java.util.*;

/**
 * aSocketThreadManager provides a thread manager for the aSocket layer: one
 * thread for each of the read, write, and listen stages.
 * 
 * @author Matt Welsh
 */
class aSocketThreadManager implements ThreadManagerIF, aSocketConst {

	private static final boolean DEBUG = false;

	private ManagerIF mgr;

	aSocketThreadManager(ManagerIF mgr) {
		this.mgr = mgr;
	}

	protected aSocketThread makeThread(aSocketStageWrapper wrapper) {
		return new aSocketThread(wrapper);
	}

	/**
	 * Register a stage with this thread manager.
	 */
	public void register(StageWrapperIF thestage) {
		aSocketStageWrapper stage = (aSocketStageWrapper) thestage;
		//!!!!此处at只是一个Runnable，不是线程对象
		aSocketThread at = makeThread(stage);
		//!!!!创建线程池
		ThreadPool tp = new ThreadPool(stage, mgr, at, 1);
		//!!!!双向挂钩，保证单个线程能找到自己的线程池
		at.registerTP(tp);
		tp.start();
	}

	/**
	 * Deregister a stage with this thread manager.
	 */
	public void deregister(StageWrapperIF stage) {
		throw new IllegalArgumentException("aSocketThreadManager: deregister not supported");
	}

	/**
	 * Deregister all stages from this thread manager.
	 */
	public void deregisterAll() {
		throw new IllegalArgumentException("aSocketThreadManager: deregisterAll not supported");
	}

	/**
	 * Internal class representing a single aSocketTM-managed thread.
	 */
	protected class aSocketThread implements Runnable {

		protected ThreadPool tp;
		protected StageWrapperIF wrapper;
		protected SelectSourceIF selsource;
		protected SourceIF eventQ;
		protected String name;
		protected EventHandlerIF handler;

		protected aSocketThread(aSocketStageWrapper wrapper) {
			if (DEBUG)
				System.err.println("!!!!!aSocketThread init");
			this.wrapper = wrapper;
			this.name = "aSocketThread <" + wrapper.getStage().getName() + ">";
			this.selsource = wrapper.getSelectSource();
			this.eventQ = wrapper.getEventQueue();
			this.handler = wrapper.getEventHandler();
		}

		/**
		 * !!!!双向挂钩，保证单个线程能找到自己的线程池
		 * @param tp
		 */
		void registerTP(ThreadPool tp) {
			this.tp = tp;
		}

		/**
		 * 不停地循环获取eventQ中等待处理的queueElement，交由Handler处理
		 *  (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		public void run() {
			int aggTarget;
			if (DEBUG)
				System.err.println(name + ": starting, selsource=" + selsource + ", eventQ=" + eventQ + ", handler=" + handler);

			while (true) {

				if (DEBUG)
					System.err.println(name + ": Looping in run()");
				try {
					//!!!!是个整数，由吞吐量（队列长度）计算得到。代表了吞吐量。
					aggTarget = tp.getAggregationTarget();
					//!!!!在selsource没有Active时，运行eventQ的dequeue
					//STEPIN 如何将select source和FiniteEvent产生关系，见handleEvents
					while (selsource != null && selsource.numActive() == 0) {
						if (DEBUG)
							System.err.println(name + ": numActive is zero, waiting on event queue");
						QueueElementIF qelarr[];
						//!!!!默认就是-1
						if (aggTarget == -1) {
							qelarr = eventQ.blocking_dequeue_all(EVENT_QUEUE_TIMEOUT);
						} else {
							qelarr = eventQ.blocking_dequeue(EVENT_QUEUE_TIMEOUT, aggTarget);
						}

						if (qelarr != null) {
							if (DEBUG)
								System.err.println(name + ": got " + qelarr.length + " new requests");
							//!!!!此处是关键
							handler.handleEvents(qelarr);
						}
					}
					//!!!!再运行selctsource的dequeue
					for (int s = 0; s < SELECT_SPIN; s++) {
						if (DEBUG)
							System.err.println(name + ": doing select, numActive " + selsource.numActive());
						SelectQueueElement ret[];

						if (aggTarget == -1) {
							ret = (SelectQueueElement[]) selsource.blocking_dequeue_all(SELECT_TIMEOUT);
						} else {
							ret = (SelectQueueElement[]) selsource.blocking_dequeue(SELECT_TIMEOUT, aggTarget);
						}

						if (ret != null) {
							if (DEBUG)
								System.err.println(name + ": select got " + ret.length + " elements");
							long tstart = System.currentTimeMillis();
							//STEPIN 如果是读事件，会牵涉到SockState，这又是干嘛的？写事件呢？
							handler.handleEvents(ret);
							long tend = System.currentTimeMillis();
							wrapper.getStats().recordServiceRate(ret.length, tend - tstart);

						} else if (DEBUG)
							System.err.println(name + ": select got null");
					}

					if (DEBUG)
						System.err.println(name + ": Checking request queue");
					//STEPIN 为什么还要来一遍eventQ
					for (int s = 0; s < EVENT_QUEUE_SPIN; s++) {
						QueueElementIF qelarr[];
						if (aggTarget == -1) {
							qelarr = eventQ.dequeue_all();
						} else {
							qelarr = eventQ.dequeue(aggTarget);
						}
						if (qelarr != null) {
							if (DEBUG)
								System.err.println(name + ": got " + qelarr.length + " new requests");
							handler.handleEvents(qelarr);
							break;
						}
					}
					//STEPIN 学习一下yield，synchronized等锁关键字的含义。
					Thread.currentThread().yield();

				} catch (Exception e) {
					System.err.println(name + ": got exception " + e);
					e.printStackTrace();
				}
			}
		}

	}

}
