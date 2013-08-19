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

package seda.sandStorm.lib.aSocket.nbio;

import seda.sandStorm.api.*;
import seda.sandStorm.core.*;
import seda.sandStorm.lib.aSocket.*;
import seda.nbio.*;

import java.net.*;
import java.io.*;
import java.util.*;

/**
 * Internal class used to represent state of an active socket connection.
 */
class SockState extends seda.sandStorm.lib.aSocket.SockState {

	private static final boolean DEBUG = false;

	private NonblockingInputStream nbis;
	private NonblockingOutputStream nbos;
	private SelectItem readsi, writesi;

	private SelectSource read_selsource;
	private SelectSource write_selsource;

	public SockState(ATcpConnection conn, Socket nbsock, int writeClogThreshold) throws IOException {
		if (DEBUG)
			System.err.println("SockState: Constructor called with " + conn + ", " + nbsock + ", " + writeClogThreshold);
		this.conn = conn;
		this.nbsock = nbsock;
		this.writeClogThreshold = writeClogThreshold;
		this.write_selsource = null;

		if (DEBUG)
			System.err.println("Sockstate " + nbsock + ": Const creating nbis");
		nbis = (NonblockingInputStream) nbsock.getInputStream();
		if (DEBUG)
			System.err.println("Sockstate " + nbsock + ": Const creating nbos");
		nbos = (NonblockingOutputStream) nbsock.getOutputStream();

		if (DEBUG)
			System.err.println("SockState " + nbsock + ": Const creating readBuf of size " + aSocketConst.READ_BUFFER_SIZE);
		readBuf = new byte[aSocketConst.READ_BUFFER_SIZE];

		if (DEBUG)
			System.err.println("SockState " + nbsock + ": Setting flags");
		outstanding_writes = 0;
		numEmptyWrites = 0;
		writeReqList = new ssLinkedList();

		clogged_qel = null;
		clogged_numtries = 0;
		if (DEBUG)
			System.err.println("SockState " + nbsock + ": Const done");
	}

	public SockState(ATcpConnection conn, Socket nbsock, Integer writeClogThreshold) throws IOException {
		this(conn, nbsock, writeClogThreshold.intValue());
	}

	// This is synchronized with close()
	protected synchronized void readInit(SelectSourceIF read_selsource, SinkIF compQ, int readClogTries) {
		if (DEBUG)
			System.err.println("readInit called on " + this);
		if (closed)
			return; // May have been closed already
		this.read_selsource = (SelectSource) read_selsource;
		this.readCompQ = compQ;
		this.readClogTries = readClogTries;
		//!!!!SelectItem和SockState的联系在此。
		readsi = new SelectItem((NonblockingSocket) nbsock, this, Selectable.READ_READY);
		this.read_selsource.register(readsi);
	}

	/**
	 * 流程：判断sock是否已经关闭
	 * 如果没有，将clogged_qel存有的写到enqueue中去（Sink会满，可能会有异常）
	 * 完成clogged_qel清空后，开始从socket读取数据，写到readBuf中。
	 * SelectItem.revents还不明白。
	 * 将readBuf包装到ATcpInPacket也是一个QueueElementIF。
	 * 放到readCompQ中
	 *  (non-Javadoc)
	 * @see seda.sandStorm.lib.aSocket.SockState#doRead()
	 */
	protected void doRead() {
		if (DEBUG)
			System.err.println("SockState: doRead called");

		// When using SelectSource, we need this guard, since after closing
		// a socket we may have outstanding read events still in the queue
		if (closed)
			return;

		if (clogged_qel != null) {
			// Try to drain the clogged element first
			if (DEBUG)
				System.err.println("SockState: doRead draining clogged element " + clogged_qel);
			try {
				//STEPIN readCompQ是SinkIF，干嘛用的?
				readCompQ.enqueue(clogged_qel);
				clogged_qel = null;
				clogged_numtries = 0;
			} catch (SinkFullException qfe) {
				// Nope, still clogged
				if ((readClogTries != -1) && (++clogged_numtries >= readClogTries)) {
					if (DEBUG)
						System.err.println("SockState: warning: readClogTries exceeded, dropping " + clogged_qel);
					clogged_qel = null;
					clogged_numtries = 0;
				} else {
					// Try again later
					return;
				}
			} catch (SinkException sce) {
				// Whoops - user went away - just drop
				this.close(null);
			}
		}

		int len;

		try {
			if (DEBUG)
				System.err.println("SockState: doRead trying read");
			len = nbis.read(readBuf, 0, READ_BUFFER_SIZE);
			if (DEBUG)
				System.err.println("SockState: read returned " + len);
			//如果没有读出字节来。
			if (len == 0) {
				// XXX MDW: Sometimes we get an error return result from
				// poll() which causes an attempted read here, but no
				// IOException. For now I am going to just drop the "null"
				// packet - on Linux it seems that certain TCP errors can
				// trigger this.
				// System.err.println("ss.doRead: Warning: Got empty read on socket");
				//!!!! readsi是read selectitem。readinit方法中初始化。
				readsi.revents = 0;
				return;
			} else if (len < 0) {
				// Read failed - assume socket is dead
				if (DEBUG)
					System.err.println("ss.doRead: read failed, sock closed");
				this.close(readCompQ);
				readsi.revents = 0;
				return;
			}
		} catch (Exception e) {
			// Read failed - assume socket is dead
			if (DEBUG)
				System.err.println("ss.doRead: read got IOException: " + e.getMessage());
			this.close(readCompQ);
			//STEPIN revents干嘛用的？
			readsi.revents = 0;
			return;
		}

		if (DEBUG)
			System.err.println("ss.doRead: Pushing up new ATcpInPacket, len=" + len);

		pkt = new ATcpInPacket(conn, readBuf, len, aSocketConst.READ_BUFFER_COPY, seqNum);
		// 0 is special (indicates no sequence number)
		seqNum++;
		if (seqNum == 0)
			seqNum = 1;
		if (aSocketConst.READ_BUFFER_COPY == false) {
			readBuf = new byte[aSocketConst.READ_BUFFER_SIZE];
		}

		try {
			readCompQ.enqueue(pkt);
		} catch (SinkFullException qfe) {
			clogged_qel = pkt;
			clogged_numtries = 0;
			return;
		} catch (SinkException sce) {
			// User has gone away
			this.close(null);
			return;
		}
		readsi.revents = 0;
	}

	// XXX This is synchronized with close() to avoid a race with close()
	// removing the writeReqList while this method is being called.
	// Probably a better way to do this...
	protected synchronized boolean addWriteRequest(aSocketRequest req, SelectSourceIF write_selsource) {
		if (closed)
			return false;

		if (DEBUG)
			System.err.println("SockState: addWriteRequest called");

		if (this.write_selsource == null) {
			if (DEBUG)
				System.err.println("SockState: Setting selsource to " + write_selsource);
			this.write_selsource = (SelectSource) write_selsource;
			writesi = new SelectItem((NonblockingSocket) nbsock, this, Selectable.WRITE_READY);
			((SelectSource) write_selsource).register(writesi);
			numActiveWriteSockets++;
			if (DEBUG)
				System.err.println("SockState: Registered with selsource");
		} else if (this.outstanding_writes == 0) {
			numEmptyWrites = 0;
			writeMaskEnable();
		}

		if ((writeClogThreshold != -1) && (this.outstanding_writes > writeClogThreshold)) {
			if (DEBUG)
				System.err.println("SockState: warning: writeClogThreshold exceeded, dropping " + req);
			if (req instanceof ATcpWriteRequest)
				return false;
			if (req instanceof ATcpCloseRequest) {
				// Do immediate close: Assume socket is clogged
				ATcpCloseRequest creq = (ATcpCloseRequest) req;
				this.close(creq.compQ);
				return true;
			}
		}

		if (DEBUG)
			System.err.println("SockState: Adding writeReq to tail");
		writeReqList.add_to_tail(req);
		this.outstanding_writes++;
		return true;
	}

	protected void initWrite(ATcpWriteRequest req) {
		this.cur_write_req = req;
		this.writeBuf = SockStatereq.buf.data;
		this.cur_offset = req.buf.offset;
		this.cur_length_target = req.buf.size + cur_offset;
	}

	protected boolean tryWrite() throws SinkClosedException {
		try {
			int tryLen;
			if (MAX_WRITE_LEN == -1) {
				tryLen = cur_length_target - cur_offset;
			} else {
				tryLen = Math.min(cur_length_target - cur_offset, MAX_WRITE_LEN);
			}
			cur_offset += nbos.nbWrite(writeBuf, cur_offset, tryLen);
			if (DEBUG)
				System.err.println("SockState: tryWrite() of " + tryLen + " bytes (len=" + cur_length_target + ", off=" + cur_offset);

		} catch (IOException ioe) {
			// Assume this is because socket was already closed
			this.close(null);
			throw new SinkClosedException("tryWrite got exception doing write: " + ioe.getMessage());
		}
		if (cur_offset == cur_length_target) {
			if (DEBUG)
				System.err.println("SockState: tryWrite() completed write of " + cur_length_target + " bytes");
			return true;
		} else
			return false;
	}

	protected void writeMaskEnable() {
		numActiveWriteSockets++;
		writesi.events |= Selectable.WRITE_READY;
		write_selsource.update(writesi);
	}

	protected void writeMaskDisable() {
		numActiveWriteSockets--;
		writesi.events &= ~(Selectable.WRITE_READY);
		write_selsource.update(writesi);
	}

	// XXX This is synchronized to avoid close() interfering with
	// addWriteRequest
	// 首先从selsource中注销，再将socket关闭
	protected synchronized void close(SinkIF closeEventQueue) {
		if (closed)
			return;

		closed = true;

		if (DEBUG)
			System.err.println("SockState.close(): Deregistering with selsources");
		if (read_selsource != null)
			read_selsource.deregister(readsi);
		if (write_selsource != null)
			write_selsource.deregister(writesi);
		if (DEBUG)
			System.err.println("SockState.close(): done deregistering with selsources");
		// Eliminate write queue

		// XXX XXX XXX MDW: This introduces a race condition with
		// addWriteRequest() -- need to serialize close() with other
		// queue operations on the socket.
		writeReqList = null;

		try {
			if (DEBUG)
				System.err.println("SockState.close(): doing close [" + nbsock + "]");
			nbsock.close();
		} catch (IOException e) {
			// Do nothing
		}

		if (closeEventQueue != null) {
			//!!!!sock已经关闭了，添加conn以备下次再次连接
			SinkClosedEvent sce = new SinkClosedEvent(conn);
			closeEventQueue.enqueue_lossy(sce);
		}
	}

}
