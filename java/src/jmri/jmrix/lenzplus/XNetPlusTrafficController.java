/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenzplus.port.XNetPacketizerDelegate;
import jmri.jmrix.lenzplus.port.XNetProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetPacketizer;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenz.XNetTurnout;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class XNetPlusTrafficController extends XNetPacketizer implements XNetProtocol {
    private XNetPacketizerDelegate  packetizer;
    private final QueueController   cmdController;
    
    public XNetPlusTrafficController(LenzCommandStation pCommandStation) {
        super(pCommandStation);
        cmdController = new QueueController(this);
    }

    public XNetPacketizerDelegate getPacketizer() {
        return packetizer;
    }
    
    public QueueController getQueueController() {
        return cmdController;
    }

    public XNetPacketizer setPacketizer(XNetPacketizerDelegate packetizer) {
        this.packetizer = packetizer;
        packetizer.attachTo(this);
        return this;
    }

    @Override
    public void sendXNetMessage(XNetMessage m, XNetListener reply) {
        XNetPlusMessage m2;
        
        if (m instanceof XNetPlusMessage) {
            m2 = (XNetPlusMessage)m;
        } else {
            m2 = new XNetPlusMessage(m);
        }
        // delegate to the command controller.
        cmdController.send(m2, reply);
    }
    
     void doSendMessage(XNetPlusMessage m, XNetListener reply) {
        super.sendXNetMessage(m, reply);
    }

    // Delegate encapsulation to a helper
    // -----------------------------------------------------
    @Override
    protected void loadChars(AbstractMRReply msg, DataInputStream istream) throws IOException {
        packetizer.loadChars((XNetPlusReply)msg, istream);
    }

    @Override
    protected int lengthOfByteStream(AbstractMRMessage m) {
        return packetizer.lengthOfByteStream((XNetMessage)m);
    }

    @Override
    protected int addHeaderToOutput(byte[] msg, AbstractMRMessage m) {
        return packetizer.addHeaderToOutput(msg, (XNetMessage)m);
    }
    
    // XNetProtocol delegate implementation
    // -----------------------------------------------------
    @Override
    public byte readByteProtected(InputStream is) throws IOException {
        // for some weird reason, DataInputStream is required, although
        // not methods from the Data* interface is used.
        return super.readByteProtected((DataInputStream)istream);
    }

    @Override
    public boolean endOfMessage(XNetPlusReply reply) {
        return super.endOfMessage(reply);
    }

    @Override
    public boolean isReplyExpected() {
        return mCurrentState != IDLESTATE;
    }
    
    public synchronized void forwardToPort(XNetPlusMessage m, XNetListener reply) {
        super.forwardToPort(m, reply);
    }
    
    // -----------------------------------------------------
    
    @GuardedBy("this")
    private volatile StreamReceiver receiver;
    private volatile XNetPlusMessage lastSentMessage;
    
    @Override
    protected synchronized void forwardToPort(AbstractMRMessage m, AbstractMRListener reply) {
        if (receiver == null) {
            throw new IllegalStateException();
        }
        lastSentMessage = (XNetPlusMessage)m;
        getQueueController().message(lastSentMessage);
        receiver().markTransmission();
        packetizer.forwardToPort(lastSentMessage, (XNetListener)reply);
    }

    /**
     * Records that a message is being received.
     */
    public void notifyMessageStart(XNetPlusReply msg) {
        StreamReceiver r = receiver;
        if (r != null) {
            r.incomingPacket(msg);
        }
    }
    
    final ExecutorService executor = Executors.newCachedThreadPool();
    
    private StreamReceiver receiver() {
        StreamReceiver r = this.receiver;
        if (r == null) {
            throw new IllegalStateException();
        }
        return r;
    }

    @Override
    public void receiveLoop() {
        executor.submit(receiver = new StreamReceiver(Thread.currentThread()));
        super.receiveLoop();
    }
    
    /**
     * Distributes reply among the listeners, and potentially sender. Overridable
     * for possible extension with pre/post actions.
     * 
     * @param reply the reply to distribute
     * @param lastSender the sender that should be targetted by the reply; null for no target
     * @param r Runnable to execute in the layout thread, which will actually distribute the message
     */
    protected void distributeReply(AbstractMRReply reply, AbstractMRListener lastSender, Runnable r) {
        distributeReply(() -> {
            XNetPlusReply plusReply = (XNetPlusReply)reply;
            // pre-process
            getQueueController().message(plusReply);
            try {
                r.run();
            } finally {
                // trigger post-processing of the reply
                getQueueController().postReply(plusReply);
            }
        });
    }

    /**
     * This loop provides processing coordinated between the transmit and receive threads.
     * It attempts to acquire a message from the receiver, blocking of no message is available.
     * When acquired, 
     */
    @Override
    public void handleOneIncomingReply() throws IOException {
        XNetReply r = null;
        StreamReceiver recvHelper = this.receiver;
        ResponseHandler handler = null;
        boolean mustProcess = true;
        try {
//            while (true) {
                if (!recvHelper.shouldRun()) {
                    return;
                }
                 r = recvHelper.take();
                 // re-check after possible block
                 if (!recvHelper.shouldRun() || r == null) {
                     return;
                 }
                 // share the handler for all unsolicited messages
                 if (handler == null) {
                    handler = new ResponseHandler(this);
                 }
                 if (!recvHelper.isSolicited(r)) {
                    handler.process(r);
                    if (!r.isUnsolicited()) {
                        mustProcess = false;
                        LOG.error("Unexpected solicited reply: " + r);
                    } else {
                        return;
                    }
                 }
//            }
            handler.update();
            if (mustProcess) {
                handler.process(r);
            }

            // process until the input stream terminates:
            // special case if the last sender is a turnout:
            if (false && ((handler.mLastSender instanceof XNetTurnout) ||
                (handler.lastMessage != null && handler.lastMessage.getElement(0) == 0x52))) {
                while (true) {
                    r = recvHelper.takeWithTimeout(30);
                    if (r == null) {
                        break;
                    }
                    handler.process(r);
                }
            }
        } finally {
            recvHelper.resetSolicitedReply(r);
            if (handler != null) {
                handler.flush();
            }
        }
    }
    
    // operated from the received thread.
    private int retransmitCount;
    
    /**
     * The ResponseHandler instance will capture all the state changes until
     * after all replies are processed. This is will prevent the
     * transmit thread from waking up too early.
     */
    static class ResponseHandler {
        final XNetPlusTrafficController ctrl;
        final Object xmtRunnable;
        
        AbstractMRMessage  lastMessage;
        AbstractMRListener mLastSender;
        int origCurrentState;
        int mCurrentState;
        int mCurrentMode;
        boolean replyInDispatch = true;
        boolean warmedUp;
        
        private ResponseHandler(XNetPlusTrafficController source) {
            ctrl = source;
            xmtRunnable = source.xmtRunnable;
            
            synchronized (source.xmtRunnable) {
                mCurrentMode = source.mCurrentMode;
                mCurrentState = source.mCurrentState;
                origCurrentState = mCurrentState;
                update();
            }
        }
        
        public synchronized void update() {
            this.mLastSender = ctrl.mLastSender;
            this.lastMessage = ctrl.lastSentMessage;
        }
        
        private boolean handleSolicitedStateTransition(XNetReply msg) {
            switch (mCurrentState) {
                case WAITMSGREPLYSTATE: {
                    if (msg.isRetransmittableErrorMsg()) {
                        LOG.error("Automatic Recovery from Error Message: {}.  Retransmitted {} times.", msg, ctrl.retransmitCount);
                        synchronized (xmtRunnable) {
                            mCurrentState = AUTORETRYSTATE;
                            if (ctrl.retransmitCount > 0) {
                                try {
                                    xmtRunnable.wait(ctrl.retransmitCount * 100L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); // retain if needed later
                                }
                            }
                            replyInDispatch = false;
                            ctrl.retransmitCount++;
                        }
                    } else {
                        // update state, and notify to continue
                        mCurrentState = NOTIFIEDSTATE;
                        replyInDispatch = false;
                        ctrl.retransmitCount = 0;
                    }
                    break;
                }
                case WAITREPLYINPROGMODESTATE: {
                    // entering programming mode
                    mCurrentMode = PROGRAMINGMODE;
                    replyInDispatch = false;

                    // check to see if we need to delay to allow decoders to become
                    // responsive
                    int warmUpDelay = ctrl.enterProgModeDelayTime();
                    if (!warmedUp && warmUpDelay != 0) {
                        warmedUp = true;
                        try {
                            synchronized (xmtRunnable) {
                                xmtRunnable.wait(warmUpDelay);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // retain if needed later
                        }
                    }
                    // update state, and notify to continue
                    mCurrentState = OKSENDMSGSTATE;
                    break;
                }
                case WAITREPLYINNORMMODESTATE: {
                    // entering normal mode
                    mCurrentMode = NORMALMODE;
                    replyInDispatch = false;
                    // update state, and notify to continue
                    mCurrentState = OKSENDMSGSTATE;
                    break;
                }
                default:
                    return false;
            }
            return true;
        }
        
        public void process(XNetReply msg) {
            replyInDispatch = true;
            // forward the message to the registered recipients,
            // which includes the communications monitor
            // return a notification via the Swing event queue to ensure proper thread
            LOG.debug("dispatch reply of length {} contains \"{}\", state {}, origState {}", msg.getNumDataElements(), msg, mCurrentState, origCurrentState);
            Runnable r = new RcvNotifier(msg, mLastSender, ctrl);
            ctrl.distributeReply(msg, mLastSender, r);

            if (!msg.isUnsolicited()) {
                boolean success = handleSolicitedStateTransition(msg);
                if (!success) {
                    // retry with the original state
                    int saveState = mCurrentState;
                    mCurrentState = origCurrentState;
                    success = handleSolicitedStateTransition(msg);
                    if (!success) {
                        mCurrentState = saveState;
                        // FIXME bug: should occur inside the monitor to ensure cache propagation.
                        // will be flushed "at some point", but can be stuck while waiting on a
                        // next message.
                        if (ctrl.allowUnexpectedReply) {
                            replyInDispatch = false;
                            LOG.debug("Allowed unexpected reply received in state: {} was {}", mCurrentState, msg);
                        } else {
                            ctrl.unexpectedReplyStateError(mCurrentState, msg.toString());
                        }
                    }
                }
            } else {
                LOG.debug("Unsolicited Message Received {}", msg);
            }
        }
        
        public void flush() {
            ctrl.mCurrentState = mCurrentState;
            ctrl.mCurrentMode = mCurrentMode;
            synchronized (xmtRunnable) {
                ctrl.replyInDispatch = false;
                if (replyInDispatch == false) {
                    xmtRunnable.notify();
                }
            }
        }
    }
    
    @Override
    protected XNetPlusReply newReply() {
        return new XNetPlusReply();
    }

    /**
     * The receiver thread. It helps to correlate request and response threads.
     * This thread receives data continuously into a bounded buffer: the buffer's capacity
     * should be big enough to accumulate data during excess processing time in the response thread.
     * <p>
     * Upon transmission of a new message, the queue of existing data is flushed - those
     * received packets <b>can not be</b> responses to the request being sent. They can be
     * safely processed as out-of-band unsolicited messages from the command station.
     * <p>
     * Next, the caller may perform a time-bounded poll for a message. If, for example,
     * the turnout operation expects OK <b>and optional feedback</b>, the response thread
     * may poll for some small time to get that feedback, and process it as a part of
     * the transmitted operation, before it releases the transmit thread.
     */
    class StreamReceiver implements Runnable {
        private final Thread responseThread;
        /**
         * Counts the number of incoming packets, that are in the queue,
         * or are in the process of insertion.
         */
        private final Semaphore incomingPackets = new Semaphore(0);
        
        private final BlockingQueue<XNetReply>   recvQueue = new BlockingArrayQueue<>(10);
        
        /**
         * Number of recv errors. The number is incremented in the receiver thread,
         * but might be read from other threads.
         */
        private volatile int errorCount;
        
        @GuardedBy("this")
        private boolean transmitMark;
        
        @GuardedBy("this")
        private volatile XNetPlusReply solicitedReply;
        
        private volatile IOException ioError;
        
        private volatile RuntimeException runtimeError;

        public StreamReceiver(Thread responseThread) {
            this.responseThread = responseThread;
        }
        
        /**
         * An incoming packet is detected. If a message has been transmitted and not
         * yet serviced, we mark the incoming reply as solicited - IF the incoming
         * packet is not marked as broadcast by the link layer.
         */
        void incomingPacket(XNetPlusReply mark) {
            synchronized (this) {
                if (transmitMark) {
                    LOG.debug("SOLICITED incoming packet starts: {}", mark);
                    solicitedReply = mark;
                    transmitMark = false;
                } else {
                    LOG.debug("unsolicited incoming packet starts: {}", mark);
                }
                incomingPackets.release();
            }
        }
        
        public void run() {
            while (shouldRun()) {
                try {
                    receiveOne();
                } catch (IOException e) {
                    ioError = e;
//                    rcvException = true;
//                    reportReceiveLoopException(e);
                    break;
                } catch (RuntimeException e1) {
                    LOG.error("Exception in receive loop: {}", e1.toString(), e1);
                    errorCount++;
                    if (errorCount == maxRcvExceptionCount) {
                        errorCount--;
//                        reportReceiveLoopException(e1);
                        runtimeError = e1;
                    }
                }
            }
//            if (!threadStopRequest) { // if e.g. unexpected end
//                ConnectionStatus.instance().setConnectionState(controller.getUserName(), controller.getCurrentPortName(), ConnectionStatus.CONNECTION_DOWN);
//                log.error("Exit from rcv loop in {}", this.getClass());
//                recovery(); // see if you can restart
//            }
            responseThread.interrupt();
        }
        
        
        
        private void receiveOne() throws IOException {
            XNetPlusReply msg = (XNetPlusReply)newReply();

            // wait for start if needed
            waitForStartOfReply(istream);

            // message exists, now fill it
            loadChars(msg, istream);
            
            // note: the semaphore was already incremented after 1st reply
            // byte was received, see loadChars().
            recvQueue.offer(msg);
        }
        
        public boolean isSolicited(XNetReply r) {
            return r == solicitedReply;
        }
        
        public void resetSolicitedReply(XNetReply r) {
            synchronized (this) {
                LOG.debug("Attempt to reset solicied reply for: {} - {}", r, r == solicitedReply);
                if (r == solicitedReply) {
                    solicitedReply = null;
                    transmitMark = false;
                }
            }
        }
        
        public XNetReply take() throws IOException {
            while (shouldRun()) {
                try {
                    // wait for data to become available
                    incomingPackets.acquire();
                    break;
                } catch (InterruptedException ex) {
                    // no op, loop again
                }
            }
            while (shouldRun()) {
                try {
                    return recvQueue.take();
                } catch (InterruptedException ex) {
                    // no op, loop again
                }
            }
            if (ioError != null) {
                throw ioError;
            } else if (runtimeError != null) {
                throw runtimeError;
            }
            return null;
        }
        
        public XNetReply takeWithTimeout(int waitTimeout) {
            XNetReply reply = null;
            boolean havePermit = false;
            while (shouldRun()) {
                try {
                    if (incomingPackets.tryAcquire(waitTimeout, TimeUnit.MILLISECONDS)) {
                        // note: may wait until the packet is complete.
                        havePermit = true;
                    }
                    break;
                } catch (InterruptedException ex) {
                    // no op, loop again
                }
            }
            if (!havePermit) {
                return null;
            }
            while (shouldRun()) {
                try {
                    return recvQueue.take();
                } catch (InterruptedException ex) {
                    // no op, loop again
                }
            }
            return null;
        }
        
        void markTransmission() {
            synchronized (this) {
                if (!transmitMark) {
                    solicitedReply = null;
                }
                LOG.debug("Outgoing transmission in progress: {}", lastSentMessage);
                transmitMark = true;
            }
        }
        
        public boolean shouldRun() {
            return ioError == null && runtimeError == null &&
                  !rcvException && !threadStopRequest;
        }
        
    } // end of StreamReceiver

    private static final Logger LOG = LoggerFactory.getLogger(XNetPlusTrafficController.class);
}
