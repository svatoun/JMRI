package jmri.jmrix.lenz;

import static jmri.jmrix.AbstractMRTrafficController.AUTORETRYSTATE;
import static jmri.jmrix.AbstractMRTrafficController.NORMALMODE;
import static jmri.jmrix.AbstractMRTrafficController.NOTIFIEDSTATE;
import static jmri.jmrix.AbstractMRTrafficController.OKSENDMSGSTATE;
import static jmri.jmrix.AbstractMRTrafficController.PROGRAMINGMODE;
import static jmri.jmrix.AbstractMRTrafficController.WAITMSGREPLYSTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINNORMMODESTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINPROGMODESTATE;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.AbstractMRTrafficController;
import net.jcip.annotations.GuardedBy;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for implementations of XNetInterface.
 * <p>
 * This provides just the basic interface.
 * @see jmri.jmrix.AbstractMRTrafficController
 *
 * @author Bob Jacobsen Copyright (C) 2002
 * @author Paul Bender Copyright (C) 2004-2010
 */
public abstract class XNetTrafficController extends AbstractMRTrafficController implements XNetInterface {

    @GuardedBy("this")
    // PENDING: the field should be probably made private w/ accessor to force proper synchronization for reading.
    protected final HashMap<XNetListener, Integer> mListenerMasks;
    
    protected final XActionQueue queueManager;

    /**
     * Create a new XNetTrafficController.
     * Must provide a LenzCommandStation reference at creation time.
     *
     * @param pCommandStation reference to associated command station object,
     *                        preserved for later.
     */
    XNetTrafficController(LenzCommandStation pCommandStation) {
        mCommandStation = pCommandStation;
        setAllowUnexpectedReply(true);
        mListenerMasks = new HashMap<>();
        highPriorityQueue = new LinkedBlockingQueue<>();
        highPriorityListeners = new LinkedBlockingQueue<>();
        queueManager = new XActionQueue(this);
        // will be the first, necessary as it possibly invalidates the message
        // from XNetTurnoutManager's processing.
        addXNetListener(ALL, queueManager);
    }

    static XNetTrafficController self = null;
    
    public XActionQueue getQueueManager() {
        return queueManager;
    }

    /**
     * Relays the message to the queue manager for advanced processing. The
     * Queue will eventually call back to {@link #doSendMessage}.
     * @param m
     * @param reply 
     */
    @Override
    protected synchronized void sendMessage(AbstractMRMessage m, AbstractMRListener reply) {
        getQueueManager().send((XNetMessage)m, (XNetListener)reply);
    }
    
    /**
     * Trampoline to actually queue the mesage with the traffic controller.
     * @param m the mesage
     * @param reply reply
     */
    void doSendMessage(XNetMessage m, XNetListener reply) {
        m.toPhase(XNetMessage.Phase.QUEUED);
        super.sendMessage(m, reply);
    }
    
    private volatile AbstractMRMessage lastSentMessage;
    
    protected void writeToStream(OutputStream os, byte[] bytes, AbstractMRMessage m, AbstractMRListener reply) throws IOException {
        StreamReceiver recv = receiver;
        if (recv != null) {
            recv.markTransmission();
            lastSentMessage = m;
        }
        super.writeToStream(os, bytes, m, reply);
    }

    @Override
    protected synchronized void dispatchMessage(AbstractMRMessage m, AbstractMRListener reply) {
        // extend the xmit thread lock
        super.dispatchMessage(m, reply);
        getQueueManager().preSendMessage((XNetMessage)m);
    }
    
    @Override
    protected void distributeReply(AbstractMRReply reply, AbstractMRListener lastSender, Runnable r) {
        super.distributeReply(reply, lastSender, () -> {
            r.run();
            getQueueManager().postReply((XNetReply)reply);
        });
    }

    // Abstract methods for the XNetInterface

    /**
     * Forward a preformatted XNetMessage to the actual interface.
     *
     * @param m Message to send; will be updated with CRC
     */
    @Override
    abstract public void sendXNetMessage(XNetMessage m, XNetListener reply);

    /**
     * Make connection to existing PortController object.
     */
    @Override
    public void connectPort(jmri.jmrix.AbstractPortController p) {
        super.connectPort(p);
        if (p instanceof XNetPortController) {
            this.addXNetListener(XNetInterface.COMMINFO, new XNetTimeSlotListener((XNetPortController) p));
        }
    }

    /**
     * Forward a preformatted XNetMessage to a specific listener interface.
     *
     * @param m Message to send
     */
    @Override
    public void forwardMessage(AbstractMRListener reply, AbstractMRMessage m) {
        if (!(reply instanceof XNetListener) || !(m instanceof XNetMessage)) {
            throw new IllegalArgumentException("");
        }
        ((XNetListener) reply).message((XNetMessage) m);
    }

    /**
     * Forward a preformatted XNetMessage to the registered XNetListeners.
     * <p>
     * NOTE: this drops the packet if the checksum is bad.
     *
     * @param client is the client getting the message
     * @param m      Message to send
     */
    @Override
    public void forwardReply(AbstractMRListener client, AbstractMRReply m) {
        if (!(client instanceof XNetListener) || !(m instanceof XNetReply)) {
            throw new IllegalArgumentException("");
        }
        // check parity
        if (!((XNetReply) m).checkParity()) {
            log.warn("Ignore packet with bad checksum: {}", (m));
        } else {
            int mask;
            synchronized (this) {
                mask = mListenerMasks.getOrDefault(client, XNetInterface.ALL);
            }
            if (mask == XNetInterface.ALL) {
                // Note: also executing this branch, if the client is not registered at all.
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.COMMINFO)
                    == XNetInterface.COMMINFO
                    && (((XNetReply) m).getElement(0)
                    == XNetConstants.LI_MESSAGE_RESPONSE_HEADER)) {
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.CS_INFO)
                    == XNetInterface.CS_INFO
                    && (((XNetReply) m).getElement(0)
                    == XNetConstants.CS_INFO
                    || ((XNetReply) m).getElement(0)
                    == XNetConstants.CS_SERVICE_MODE_RESPONSE
                    || ((XNetReply) m).getElement(0)
                    == XNetConstants.CS_REQUEST_RESPONSE
                    || ((XNetReply) m).getElement(0)
                    == XNetConstants.BC_EMERGENCY_STOP)) {
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.FEEDBACK)
                    == XNetInterface.FEEDBACK
                    && (((XNetReply) m).isFeedbackMessage()
                    || ((XNetReply) m).isFeedbackBroadcastMessage())) {
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.THROTTLE)
                    == XNetInterface.THROTTLE
                    && ((XNetReply) m).isThrottleMessage()) {
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.CONSIST)
                    == XNetInterface.CONSIST
                    && ((XNetReply) m).isConsistMessage()) {
                ((XNetListener) client).message((XNetReply) m);
            } else if ((mask & XNetInterface.INTERFACE)
                    == XNetInterface.INTERFACE
                    && (((XNetReply) m).getElement(0)
                    == XNetConstants.LI_VERSION_RESPONSE
                    || ((XNetReply) m).getElement(0)
                    == XNetConstants.LI101_REQUEST)) {
                ((XNetListener) client).message((XNetReply) m);
            }
        }
    }

    // We use the pollMessage routines for high priority messages.
    // This means responses to time critical messages (turnout off messages).
    // PENDING: these fields should be probably made private w/ accessor to force proper synchronization for reading.
    final LinkedBlockingQueue<XNetMessage> highPriorityQueue;
    final LinkedBlockingQueue<XNetListener> highPriorityListeners;

    public synchronized void sendHighPriorityXNetMessage(XNetMessage m, XNetListener reply) {
        m.asPriority(true);
        super.sendMessage(m, reply);
    }

    @Override
    protected AbstractMRMessage pollMessage() {
        try {
            if (highPriorityQueue.peek() == null) {
                return null;
            } else {
                return highPriorityQueue.take();
            }
        } catch (java.lang.InterruptedException ie) {
            log.error("Interrupted while removing High Priority Message from Queue");
        }
        return null;
    }

    @Override
    protected AbstractMRListener pollReplyHandler() {
        try {
            if (highPriorityListeners.peek() == null) {
                return null;
            } else {
                return highPriorityListeners.take();
            }
        } catch (java.lang.InterruptedException ie) {
            log.error("Interrupted while removing High Priority Message Listener from Queue");
        }
        return null;
    }

    @Override
    public synchronized void addXNetListener(int mask, XNetListener l) {
        addListener(l);
        // This is adds all the mask information.  A better way to do
        // this would be to allow updating individual bits
        mListenerMasks.put(l, mask);
    }

    @Override
    public synchronized void removeXNetListener(int mask, XNetListener l) {
        removeListener(l);
        // This is removes all the mask information.  A better way to do
        // this would be to allow updating of individual bits
        mListenerMasks.remove(l);
    }

    /**
     * This method has to be available, even though it doesn't do anything on
     * Lenz.
     */
    @Override
    protected AbstractMRMessage enterProgMode() {
        return null;
    }

    /**
     * Return the value of getExitProgModeMsg().
     */
    @Override
    protected AbstractMRMessage enterNormalMode() {
        return XNetMessage.getExitProgModeMsg();
    }

    /**
     * Check to see if the programmer associated with this interface is idle or
     * not.
     */
    @Override
    protected boolean programmerIdle() {
        if (mMemo == null) {
            return true;
        }
        jmri.jmrix.lenz.XNetProgrammerManager pm = mMemo.getProgrammerManager();
        if (pm == null) {
            return true;
        }
        XNetProgrammer p = (XNetProgrammer) pm.getGlobalProgrammer();
        if (p == null) {
            return true;
        }
        return !(p.programmerBusy());
    }

    @Override
    protected boolean endOfMessage(AbstractMRReply msg) {
        int len = (((XNetReply) msg).getElement(0) & 0x0f) + 2;  // opCode+Nbytes+ECC
        log.debug("Message Length {} Current Size {}", len, msg.getNumDataElements());
        return msg.getNumDataElements() >= len;
    }

    @Override
    protected AbstractMRReply newReply() {
        return new XNetReply();
    }

    /**
     * Get characters from the input source, and file a message.
     * <p>
     * Returns only when the message is complete.
     * <p>
     * Only used in the Receive thread.
     *
     * @param msg     message to fill
     * @param istream character source.
     * @throws java.io.IOException when presented by the input source.
     */
    @Override
    protected void loadChars(AbstractMRReply msg, java.io.DataInputStream istream) throws java.io.IOException {
        int i;
        for (i = 0; i < msg.maxSize(); i++) {
            byte char1 = readByteProtected(istream);
            if (i == 0) {
                notifyMessageStart((XNetReply)msg);
            }
            msg.setElement(i, char1 & 0xFF);
            if (endOfMessage(msg)) {
                break;
            }
        }
        if (mCurrentState == IDLESTATE) {
            msg.setUnsolicited();
        }
    }

    @Override
    protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
        super.handleTimeout(msg, l);
        if (l != null) {
            ((XNetListener) l).notifyTimeout((XNetMessage) msg);
        }
    }

    /**
     * Reference to the command station in communication here.
     */
    final LenzCommandStation mCommandStation;

    /**
     * Get access to communicating command station object.
     *
     * @return associated Command Station object
     */
    public LenzCommandStation getCommandStation() {
        return mCommandStation;
    }

    /**
     * Reference to the system connection memo.
     */
    XNetSystemConnectionMemo mMemo = null;

    /**
     * Get access to the system connection memo associated with this traffic
     * controller.
     *
     * @return associated systemConnectionMemo object
     */
    public XNetSystemConnectionMemo getSystemConnectionMemo() {
        return (mMemo);
    }

    /**
     * Set the system connection memo associated with this traffic controller.
     *
     * @param m associated systemConnectionMemo object
     */
    public void setSystemConnectionMemo(XNetSystemConnectionMemo m) {
        mMemo = m;
    }

    private XNetFeedbackMessageCache _FeedbackCache = null;

    /**
     * Return an XNetFeedbackMessageCache object associated with this traffic
     * controller.
     */
    public XNetFeedbackMessageCache getFeedbackMessageCache() {
        if (_FeedbackCache == null) {
            _FeedbackCache = new XNetFeedbackMessageCache(this);
        }
        return _FeedbackCache;
    }

    /**
     * @return whether or not this connection currently has a timeslot from the Command station.
     */
    boolean hasTimeSlot(){
       return ((XNetPortController)controller).hasTimeSlot();
    }
    
    private volatile StreamReceiver receiver;

    /**
     * Records that a message is being received.
     */
    void notifyMessageStart(XNetReply msg) {
        StreamReceiver r = receiver;
        if (r != null) {
            r.incomingPacket(msg);
        }
    }
    
    final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void receiveLoop() {
        executor.submit(receiver = new StreamReceiver(Thread.currentThread()));
        super.receiveLoop();
    }
    
    /**
     * This loop provides processing coordinated between the transmit and receive threads.
     * It attempts to acquire a message from the receiver, blocking of no message is available.
     * When acquired, 
     */
    @Override
    public void handleOneIncomingReply() throws IOException {
        XNetReply r = null;
        StreamReceiver receiver = this.receiver;
        ResponseHandler handler = null;
        boolean mustProcess = true;
        try {
//            while (true) {
                if (!receiver.shouldRun()) {
                    return;
                }
                 r = receiver.take();
                 // share the handler for all unsolicited messages
                 if (handler == null) {
                    handler = new ResponseHandler(this);
                 }
                 if (!receiver.isSolicited(r)) {
                    handler.process(r);
                    if (!r.isUnsolicited()) {
                        mustProcess = false;
                        log.error("Unexpected solicited reply: " + r);
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
                    r = receiver.takeWithTimeout(30);
                    if (r == null) {
                        break;
                    }
                    handler.process(r);
                }
            }
        } finally {
            receiver.resetSolicitedReply(r);
            if (handler != null) {
                handler.flush();
            }
        }
    }
    
    /**
     * The ResponseHandler instance will capture all the state changes until
     * after all replies are processed. This is will prevent the
     * transmit thread from waking up too early.
     */
    static class ResponseHandler {
        final XNetTrafficController ctrl;
        final Object xmtRunnable;
        
        AbstractMRMessage  lastMessage;
        AbstractMRListener mLastSender;
        int origCurrentState;
        int mCurrentState;
        int mCurrentMode;
        boolean replyInDispatch = true;
        boolean warmedUp;
        
        private ResponseHandler(XNetTrafficController source) {
            ctrl = source;
            
            xmtRunnable = source.xmtRunnable;
            mLastSender = source.mLastSender;
            mCurrentMode = source.mCurrentMode;
            mCurrentState = source.mCurrentState;
            origCurrentState = mCurrentState;
        }
        
        public void update() {
            this.mLastSender = ctrl.mLastSender;
            this.lastMessage = ctrl.lastSentMessage;
        }
        
        private boolean handleSolicitedStateTransition(XNetReply msg) {
            // FIXME - BUG: this switch supposes that there's no intervention between the switch reads the mCurrentState
            // and a branch produces a new mCurrentState value. For example,
            // between the fetch from queue and message transmission, the controller is in WAITMSGREPLYSTATE and this thread may
            // freely reempt and see that state when processing.
            // The correctness of the state automaton relies on precise message pairing and is therefore very fragile.
            switch (mCurrentState) {
                case WAITMSGREPLYSTATE: {
                    // check to see if the response was an error message we want
                    // to automatically handle by re-queueing the last sent
                    // message, otherwise go on to the next message
                    if (msg.isRetransmittableErrorMsg()) {
                        log.error("Automatic Recovery from Error Message: {}.  Retransmitted {} times.", msg, ctrl.retransmitCount);
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
            log.debug("dispatch reply of length {} contains \"{}\", state {}, origState {}", msg.getNumDataElements(), msg, mCurrentState, origCurrentState);
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
                            log.debug("Allowed unexpected reply received in state: {} was {}", mCurrentState, msg);
                        } else {
                            ctrl.unexpectedReplyStateError(mCurrentState, msg.toString());
                        }
                    }
                }
            } else {
                log.debug("Unsolicited Message Received {}", msg);
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
        private volatile XNetReply solicitedReply;
        
        private volatile IOException ioError;
        
        private volatile RuntimeException runtimeError;

        public StreamReceiver(Thread responseThread) {
            this.responseThread = responseThread;
        }
        
        void incomingPacket(XNetReply mark) {
            synchronized (this) {
                if (transmitMark) {
                    solicitedReply = mark;
                    transmitMark = false;
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
                    log.error("Exception in receive loop: {}", e1.toString(), e1);
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
            XNetReply msg = (XNetReply)newReply();

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
                transmitMark = true;
            }
        }
        
        public boolean shouldRun() {
            return ioError == null && runtimeError == null &&
                  !rcvException && !threadStopRequest;
        }
        
    } // end of StreamReceiver

    private static final Logger log = LoggerFactory.getLogger(XNetTrafficController.class);

}
