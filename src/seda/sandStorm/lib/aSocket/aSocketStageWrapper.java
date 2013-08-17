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
import java.util.*;

/**
 * Internal stage wrapper implementation for aSocket.
 */
class aSocketStageWrapper implements StageWrapperIF {

	private String name;
	/**
	 * 自己创建
	 */
	private StageIF stage;
	/**
	 * 传入
	 */
	private EventHandlerIF handler;
	/**
	 * 传入
	 */
	private ConfigDataIF config;
	/**
	 * 自己创建
	 */
	private FiniteQueue eventQ;
	/**
	 * 自己创建
	 */
	private SelectSourceIF selsource;
	/**
	 * 传入
	 */
	private ThreadManagerIF tm;
	private StageStatsIF stats;

	aSocketStageWrapper(String name, EventHandlerIF handler, ConfigDataIF config, ThreadManagerIF tm) {
		this.name = name;
		this.handler = handler;
		this.config = config;
		this.tm = tm;
		this.stats = new StageStats(this);
		// !!!!最大队列长度
		int queuelen;
		if ((queuelen = config.getInt("_queuelength")) <= 0) {
			queuelen = -1;
		}
		if (queuelen == -1) {
			eventQ = new FiniteQueue();
		} else {
			eventQ = new FiniteQueue();
			// !!!!添加阈值判断
			QueueThresholdPredicate pred = new QueueThresholdPredicate(eventQ, queuelen);
			eventQ.setEnqueuePredicate(pred);
		}
		this.selsource = ((aSocketEventHandler) handler).getSelectSource();
		this.stage = new Stage(name, this, (SinkIF) eventQ, config);
		this.config.setStage(this.stage);
	}

	/**
	 * Initialize this stage.
	 */
	public void init() throws Exception {
		//!!!!啥也不干 ReadEventHandler
		handler.init(config);
		//!!!!开始创建线程池，并启动
		tm.register(this);
	}

	/**
	 * Destroy this stage.
	 */
	public void destroy() throws Exception {
		tm.deregister(this);
		handler.destroy();
	}

	/**
	 * Return the event handler associated with this stage.
	 */
	public EventHandlerIF getEventHandler() {
		return handler;
	}

	/**
	 * Return the stage handle for this stage.
	 */
	public StageIF getStage() {
		return stage;
	}

	/**
	 * Return the source from which events should be pulled to pass to this
	 * EventHandlerIF. <b>Note</b> that this method is not used internally.
	 */
	public SourceIF getSource() {
		return eventQ;
	}

	/**
	 * Return the thread manager for this stage.
	 */
	public ThreadManagerIF getThreadManager() {
		return tm;
	}

	// So aSocketTM can access it
	SelectSourceIF getSelectSource() {
		return selsource;
	}

	// So aSocketTM can access it
	SourceIF getEventQueue() {
		return eventQ;
	}

	public StageStatsIF getStats() {
		return stats;
	}

	/** Not implemented. */
	public ResponseTimeControllerIF getResponseTimeController() {
		return null;
	}

	public String toString() {
		return "ASOCKETSW[" + stage.getName() + "]";
	}

}
