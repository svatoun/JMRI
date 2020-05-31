package jmri.jmrix.lenzplus.impl;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiver implementation. It partially solves the issue with dangling Feedback
 * broadcast on DR5000 send in response to Accessory Operation Requests: first OK
 * message is sent (confirms turnout command), then Feedback Broadcast is sent.
 * But since the feedback contains info for 2 turnouts, the paired Turnout may
 * react as if its own command was confirmed: so that Feedback needs to be "carefully"
 * filtered.
 * <p>
 * Also if the first OK would release transmit thread to send new commands, the
 * "dangling Feedback" may be incorrectly assigned to the next command in the queue.
 * So "dangling feedback" should be fully identified and "consumed" by the operation
 * which originated it.
 * <p>
 * This class is also intended to run <b>in separate thread</b>: helps with a time window 
 * where receiveLoop inspects, dispatches and processes the incoming message: 
 * receiveLoop thread is unable to receive data during that time, but the data still 
 * may come from the LI* interface. By pulling continously from the buffer, we may
 * prevent buffer overrun. Not imporant for Li-USB or LI-Ethernet, probably.
 * <p>
 * This class helps to correlate transmitted messages with received replies: if
 * a message is sent, {@link #markTransmission} should be used. The next reply
 * received will be marked as {@link #isExpected} to the receiver loop to indicate
 * it is <b>probably</b> a part of the response to the command.
 * <p>
 * Messages that come without an appropriate transmission are always unsolicited:
 * to help identifying them, {@link #incomingPacket} can be called immediately
 * when an incoming data is detected, before the entire reply packet is received.
 * If this <b>happens before</b> {@link #markTransmission}, the entire reply packet
 * will be tracked as unsolicited (it started before transmission occurred).
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public final class StreamReceiver implements Runnable, ReplySource {

    @FunctionalInterface
    public interface XNetReplySupplier {
        public XNetPlusReply receive() throws IOException;
    }
    
    /**
     * Callback that actually delivers the reply contents.
     */
    private final XNetReplySupplier receiveReplyCallback;
    
    /**
     * Counts the number of incoming packets, that are in the queue,
     * or are in the process of insertion.
     */
    private final Semaphore incomingPackets = new Semaphore(0);
    
    /**
     * Collects messages received between {@link #take} calls.
     */
    private final BlockingQueue<XNetPlusReply> recvQueue = new LinkedBlockingQueue<>(10);

    /**
     * Indicates that a transmission is in progress, and the first subsequent reply
     * is an expected reply-to-transmitted message.
     */
    @GuardedBy(value = "this")
    private boolean transmitMark;
    
    /**
     * The message being sent just now.
     */
    @GuardedBy(value = "this")
    private XNetPlusMessage messageBeingSent;

    /**
     * The expected reply's instance.
     */
    @GuardedBy(value = "this")
    private volatile XNetPlusReply expectedReply;
    
    /**
     * Capture IOException from the decicated receive thread, to be fired
     * from {@link #take}
     */
    private volatile IOException ioError;
    /**
     * Capture exception from the decicated receive thread, to be fired
     * from {@link #take}
     */
    private volatile RuntimeException runtimeError;
    
    /**
     * Signal that a thread stop is required. Must be set by {@link #stop}
     * as it needs to wake up the thread waiting on {@link #take}.
     */
    private volatile boolean stopRequested;
    
    /**
     * Notes that {@link #incomingPacket} was called before the 
     * supplier returned the actual contents.
     */
    private boolean incomingCalled;
    
    // for diagnostics only
    private volatile Thread ownThread;
    
    /**
     * Special "do not return" value used to just wake up the thread from
     * take() lock.
     */
    private static final XNetPlusReply NONE = new XNetPlusReply();

    public StreamReceiver(XNetReplySupplier loader) {
        this.receiveReplyCallback = loader;
    }
    
    /**
     * Diagnostic only: returns the receiving thread. Will busy-wait until the thread starts.
     * @return 
     */
    Thread getReceivingThread() {
        while (ownThread == null) {}
        return ownThread;
    }

    /**
     * An incoming packet is detected. If a message has been transmitted and not
     * yet serviced, we mark the incoming reply as solicited.
     */
    public void incomingPacket(XNetPlusReply mark) {
        if (Thread.currentThread() != ownThread) {
            throw new IllegalStateException("incomingPacket must be called from receive thread");
        }
        synchronized (this) {
            if (transmitMark) {
                LOG.debug("SOLICITED incoming packet starts: {}", mark);
                expectedReply = mark;
            } else {
                LOG.debug("unsolicited incoming packet starts: {}", mark);
            }
            incomingCalled = true;
            incomingPackets.release();
        }
    }
    
    /**
     * Forces stop of the receiver loop. A pending call to {@link #take} or
     * {@link #takeWithTimeout} will return {@code null}, and {@link #isActive}
     * will become {@code false}.
     */
    public void stop() {
        stopRequested = true;
        signalError();
    }

    private void signalError() {
        incomingPackets.release();
        recvQueue.offer(NONE);
    }

    /**
     * Main receiver loop. To be run in a separate thread.
     */
    public void run() {
        Thread t = Thread.currentThread();
        String saveName = t.getName();
        // just to be better recognized in thread dumps:
        t.setName("XPressNet:Plus data receiver");
        try {
            doRun();
        } finally {
            t.setName(saveName);
        }
    }
    
    void doRun() {
        ownThread = Thread.currentThread();
        try {
            while (shouldRun(true)) {
                try {
                    receiveOne();
                }
                catch (IOException e) {
                    ioError = e;
                    break;
                }
                catch (RuntimeException e1) {
                    LOG.error("Exception in receive loop: {}", e1.toString(), e1);
                    runtimeError = e1;
                    signalError();
                }
            }
        } finally {
            signalError();
        }
    }

    private void receiveOne() throws IOException {
        // note: the semaphore was already incremented after 1st reply
        // byte was received, see loadChars().
        XNetPlusReply r = receiveReplyCallback.receive();
        synchronized (this) {
            // safeguard against simple code that does not call
            // incomingPacket(), just serves the data.
            if (!incomingCalled) {
                incomingPacket(r);
            }
            if (r == null) {
                r = NONE;
            }
            if (r == expectedReply) {
                r.setResponseTo(messageBeingSent);
            }
            incomingCalled = false;
            recvQueue.offer(r);
        }
    }

    /**
     * Resets the expected reply state. This is required before the next
     * possible call to {@link #markTransmission}.
     * @param r the expected reply instance. 
     */
    @Override
    public void resetExpectedReply(XNetPlusReply r) {
        synchronized (this) {
            if (r != expectedReply) {
                LOG.warn("Reply does not match: {}", r);
                // the command has ended; so we should probably clear all queued packets
                recvQueue.forEach(m -> m.setResponseTo(null));
            }
            expectedReply = null;
            transmitMark = false;
            messageBeingSent = null;
        }
    }

    /**
     * Returns the next reply from the data stream, waiting if necessary. The
     * call will wait until a reply is available. If the receiver is shut down
     * by {@link #stop}, the method terminates and return {@code null}. It may also
     * throw an {@link IOException} (from the lower layers), or {@link RuntimeException},
     * if some unexpected error occurs. 
     * <p>
     * If {@code null} is returned, always check {@link #isActive}.
     * @return reply instance of {@code null}
     * @throws IOException  in case of I/O error.
     */
    @CheckForNull
    public XNetPlusReply take() throws IOException {
        // clear error state:
        while (shouldRun(false)) {
            try {
                // wait for data to become available
                incomingPackets.acquire();
                break;
            }
            catch (InterruptedException ex) {
                // no op, loop again
            }
        }
        return takeIncomingReply();
    }

    private XNetPlusReply takeIncomingReply() throws IOException {
        while (shouldRun(false)) {
            try {
                XNetPlusReply r = recvQueue.take();
                if (r != NONE) {
                    return r;
                }
            }
            catch (InterruptedException ex) {
                // no op, loop again
            }
        }
        if (ioError != null) {
            // will throw again on next take/takeWithTimeout.
            throw ioError;
        } else {
            RuntimeException rte = runtimeError;
            runtimeError = null;
            if (rte != null) {
                throw rte;
            }
        }
        return null;
    }

    /**
     * Waits for the next reply for a specified amount of time. Will return {@code null},
     * if the timeout expires. If returns {@code null}, check for {@link #isActive} state.
     * See {@link #take} for complete info
     * @param waitTimeout how long to wait, in milliseconds.
     * @return the reply, or {@code null}
     * @throws IOException if an I/O error occurs.
     * @see #take
     */
    @CheckForNull
    @Override
    public XNetPlusReply takeWithTimeout(int waitTimeout) throws IOException {
        if (waitTimeout < 1) {
            return take();
        }
        boolean havePermit = false;
        while (shouldRun(false)) {
            try {
                if (incomingPackets.tryAcquire(waitTimeout, TimeUnit.MILLISECONDS)) {
                    // note: may wait until the packet is complete.
                    havePermit = true;
                }
                break;
            }
            catch (InterruptedException ex) {
                // no op, loop again
            }
        }
        if (!havePermit) {
            return null;
        }
        return takeIncomingReply();
    }

    /**
     * Informs that a message was sent out to command station. The station is
     * likely to reply, so the next reply received is very likely a reply to
     * the command just sent out.
     * @param msg the outgoing command.
     */
    public void markTransmission(XNetPlusMessage msg) {
        synchronized (this) {
            if (!transmitMark) {
                expectedReply = null;
            } else {
                throw new IllegalStateException("Previous transmission not cleared.");
            }
            LOG.debug("Outgoing transmission in progress: {}", msg);
            transmitMark = true;
            messageBeingSent = msg;
        }
    }

    /**
     * Checks if the receiver is active. It returns {@code false}, if the receiver
     * has been {@link #stop}ped, or errored out.
     * @return active status
     */
    public boolean isActive() {
        return shouldRun(true);
    }

    private boolean shouldRun(boolean e) {
        return ioError == null && (e || runtimeError == null) && !stopRequested;
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(StreamReceiver.class);
    
} // end of StreamReceiver
