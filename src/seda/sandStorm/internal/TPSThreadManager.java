/* 
 * Copyright (c) 2001 by Matt Welsh and The Regents of the University of 
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

package seda.sandStorm.internal;

import seda.sandStorm.api.*;
import seda.sandStorm.api.internal.*;
import seda.sandStorm.core.*;
import seda.sandStorm.main.*;
import java.util.*;

/**
 * TPSThreadManager provides a threadpool-per-source-per-stage thread 
 * manager implementation. 
 * 
 * @author   Matt Welsh
 */

public class TPSThreadManager implements ThreadManagerIF, sandStormConst {

  private static final boolean DEBUG = false;
  private static final boolean DEBUG_VERBOSE = false;

  protected ManagerIF mgr;
  protected SandstormConfig config;
  protected Hashtable srTbl;
  protected ThreadPoolController sizeController;

  public TPSThreadManager(ManagerIF mgr) {
    this(mgr, true);
  }

  public TPSThreadManager(ManagerIF mgr, boolean initialize) {
    this.mgr = mgr;
    this.config = mgr.getConfig();

    if (initialize) {
      if (config.getBoolean("global.threadPool.sizeController.enable")) {
	sizeController = new ThreadPoolController(mgr);
      }
      srTbl = new Hashtable();
    }
  }

  /**
   * Register a stage with this thread manager.
   */
  public void register(StageWrapperIF stage) {
    // Create a threadPool for the stage
    stageRunnable sr = new stageRunnable(stage);
    srTbl.put(sr, stage);
  }

  /**
   * Deregister a stage with this thread manager.
   */
  public void deregister(StageWrapperIF stage) {
    Enumeration e = srTbl.keys();
    while (e.hasMoreElements()) {
      stageRunnable sr = (stageRunnable)e.nextElement();
      StageWrapperIF s = (StageWrapperIF)srTbl.get(sr);
      if (s == stage) {
	sr.tp.stop();
	srTbl.remove(sr);
      }
    }
  }

  /**
   * Stop the thread manager and all threads managed by it.
   */
  public void deregisterAll() {
    Enumeration e = srTbl.keys();
    while (e.hasMoreElements()) {
      stageRunnable sr = (stageRunnable)e.nextElement();
      StageWrapperIF s = (StageWrapperIF)srTbl.get(sr);
      sr.tp.stop();
      srTbl.remove(sr);
    }
  }

  /**
   * Internal class representing the Runnable for a single stage.
   */
  public class stageRunnable implements Runnable {

    protected ThreadPool tp;
    protected StageWrapperIF wrapper;
    protected SourceIF source;
    protected String name;
    protected ResponseTimeControllerIF rtController = null;
    protected boolean firstToken = false;
    protected int aggTarget = -1;

    protected stageRunnable(StageWrapperIF wrapper, ThreadPool tp) {
      this.wrapper = wrapper;
      this.tp = tp;
      this.source = wrapper.getSource();
      this.name = wrapper.getStage().getName();

      if (tp != null) {
	if (sizeController != null) {
  	  // The sizeController is globally enabled -- has the user disabled
  	  // it for this stage?
  	  String val = config.getString("stages."+this.name+".threadPool.sizeController.enable");
  	  if ((val == null) || val.equals("true") || val.equals("TRUE")) {
  	    sizeController.register(wrapper, tp);
  	  }
   	}
      }
      this.rtController = wrapper.getResponseTimeController();

      if (tp != null) tp.start();
    }

    protected stageRunnable(StageWrapperIF wrapper) {
      this.wrapper = wrapper;
      this.tp = tp;
      this.source = wrapper.getSource();
      this.name = wrapper.getStage().getName();

      // Create a threadPool for the stage
      if (wrapper.getEventHandler() instanceof SingleThreadedEventHandlerIF) {
	tp = new ThreadPool(wrapper, mgr, this, 1);
      } else {
	tp = new ThreadPool(wrapper, mgr, this);
      }

      if (sizeController != null) {
	// The sizeController is globally enabled -- has the user disabled
	// it for this stage?
	String val = config.getString("stages."+this.name+".threadPool.sizeController.enable");
	if ((val == null) || val.equals("true") || val.equals("TRUE")) {
	  sizeController.register(wrapper, tp);
	}
      }
      this.rtController = wrapper.getResponseTimeController();

      tp.start();
    }

    public void run() {
      int blockTime;
      long t1, t2;
      long tstart = 0, tend = 0;
      boolean isFirst = false;

      if (DEBUG) System.err.println(name+": starting, source is "+source);

      t1 = System.currentTimeMillis();

      while (true) {

	synchronized (this) {
  	  if (firstToken == false) {
  	    firstToken = true; isFirst = true;
  	  }
   	}
	
       	try {

	  blockTime = (int)tp.getBlockTime();
	  aggTarget = tp.getAggregationTarget();

	  if (DEBUG_VERBOSE) System.err.println(name+": Doing blocking dequeue for "+wrapper);

	  QueueElementIF fetched[];
	  if (aggTarget == -1) {
	    if (DEBUG_VERBOSE) System.err.println("TPSTM <"+this.name+"> dequeue (aggTarget -1)");
  	    fetched = source.blocking_dequeue_all(blockTime);
          } else {
	    if (DEBUG_VERBOSE) System.err.println("TPSTM <"+this.name+"> dequeue (aggTarget "+aggTarget+")");
  	    fetched = source.blocking_dequeue(blockTime, aggTarget);
	  }

	  if (fetched == null) {
	    t2 = System.currentTimeMillis();
	    if (tp.timeToStop(t2-t1)) {
	      if (DEBUG) System.err.println(name+": Exiting");
	      if (isFirst) {
		synchronized (this) { firstToken = false; }
	      }
	      return;
	    }
	    continue;
	  } 

	  t1 = System.currentTimeMillis();

	  if (DEBUG_VERBOSE) System.err.println(name+": Got "+fetched.length+" elements for "+wrapper);

	  /* Process events */
	  tstart = System.currentTimeMillis();
	  wrapper.getEventHandler().handleEvents(fetched);
	  tend = System.currentTimeMillis();

	  /* Record service rate */
	  ((StageWrapper)wrapper).getStats().recordServiceRate(fetched.length, tend-tstart);

	  /* Run response time controller controller */
	  if (rtController != null) {
	    if (rtController instanceof ResponseTimeControllerMM1) {
	      ((ResponseTimeControllerMM1)rtController).adjustThreshold(fetched, tstart, tend, isFirst, tp.numThreads());
	    } else {
	      rtController.adjustThreshold(fetched, tend-tstart);
	    }
	  }

	  if (tp.timeToStop(0)) {
	    if (DEBUG) System.err.println(name+": Exiting");
	    if (isFirst) {
      	      synchronized (this) { firstToken = false; }
	    }
	    return;
	  }

	  Thread.currentThread().yield();

	} catch (Exception e) {
	  System.err.println("TPSThreadManager: appThread ["+name+"] got exception "+e);
	  e.printStackTrace();
	}
      }
    }
  }

}

