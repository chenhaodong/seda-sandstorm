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
import seda.sandStorm.core.*;

import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Internal event handler used to process socket read events.
 */
class ReadEventHandler extends aSocketEventHandler implements EventHandlerIF {

  private static final boolean DEBUG = false;

  ReadEventHandler() {
  }

  public void init(ConfigDataIF config) {
  }

  public void destroy() {
  }

  private void processReadRequest(aSocketRequest req) throws IOException {

    if (req instanceof ATcpStartReadRequest) {
      ATcpStartReadRequest srreq = (ATcpStartReadRequest)req;
      SockState ss = srreq.conn.sockState;
      ss.readInit(selsource, srreq.compQ, srreq.readClogTries);

    } else if (req instanceof AUdpStartReadRequest) {
      AUdpStartReadRequest srreq = (AUdpStartReadRequest)req;
      DatagramSockState ss = srreq.sock.sockState;
      ss.readInit(selsource, srreq.compQ, srreq.readClogTries);

    } else {
      throw new IllegalArgumentException("Bad request type to enqueueRead");
    }
  }

  public void handleEvent(QueueElementIF qel) {
    if (DEBUG) System.err.println("ReadEventHandler: Got QEL: "+qel);

    try {
      if (qel instanceof SelectQueueElement) {
        Object attach = ((SelectQueueElement)qel).getAttachment();
	if (attach instanceof SockState) {
	  SockState ss = (SockState)attach;
	  if (DEBUG) System.err.println("ReadEventHandler: ss is "+ss);
	  ss.doRead();
	} else {
	  DatagramSockState ss = (DatagramSockState)attach;
	  if (DEBUG) System.err.println("ReadEventHandler: ss is "+ss);
	  ss.doRead();
	}
	if (DEBUG) System.err.println("ReadEventHandler: returned from doRead");

      } else if (qel instanceof aSocketRequest) {
	processReadRequest((aSocketRequest)qel);

      } else {
	throw new IllegalArgumentException("ReadEventHandler: Got unknown event type "+qel);
      }

    } catch (Exception e) {
      System.err.println("ReadEventHandler: Got exception: "+e);
      e.printStackTrace();
    }
  }

  public void handleEvents(QueueElementIF qelarr[]) {
    for (int i = 0; i < qelarr.length; i++) {
      handleEvent(qelarr[i]);
    }
  }

}

