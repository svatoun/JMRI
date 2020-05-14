/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.comm.QueueController;
import jmri.jmrix.lenzplus.impl.ReplyDispatcher;
import jmri.jmrix.lenzplus.impl.ResponseHandler;
import jmri.jmrix.lenzplus.impl.StreamReceiver;
import jmri.jmrix.lenzplus.port.XNetPacketizerDelegate;
import jmri.jmrix.lenzplus.port.XNetProtocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetPacketizer;
import jmri.jmrix.lenzplus.comm.TrafficController;
import jmri.util.ThreadingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class XNetPlusTrafficController extends XNetPacketizer 
        implements XNetProtocol, ReplyDispatcher {
    private final QueueController   cmdController;
    
    /**
     * The execution service used to run the receiver and reply threads.
     */
    final ExecutorService executor = Executors.newCachedThreadPool();

    private XNetPacketizerDelegate  packetizer;
    
    /**
     * The current StreamReceiver. Will be reset when the TC is reconnected.
     * Never use the field directly; acquire using {@link #receiver()} and remember
     * in local variable to have consistent set of calls.
     */
    private volatile StreamReceiver receiver;
    
    /**
     * The preprocess being just sent.
     */
    private volatile XNetPlusMessage lastSentMessage;
    
    public XNetPlusTrafficController(LenzCommandStation pCommandStation) {
        super(pCommandStation);
        cmdController = new QueueController(new TrafficController() {
            @Override
            public <T> T lookup(Class<T> service) {
                return getSystemConnectionMemo().get(service);
            }

            @Override
            public void sendMessageToDevice(XNetPlusMessage msg, XNetListener l) {
                doSendMessage(msg, l);
            }
        });
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
    
    public void sendHighPriorityXNetMessage(XNetMessage m, XNetListener reply) {
        sendXNetMessage(XNetPlusMessage.create(m).asPriority(true), reply);
    }

    @Override
    public void sendXNetMessage(XNetMessage m, XNetListener reply) {
        XNetPlusMessage m2 = XNetPlusMessage.create(m);
        // delegate to the command controller.
        cmdController.send(m2, reply);
    }
    
    // FIXME: make somehow inaccessible from outside LenzPlus
    public void doSendMessage(XNetPlusMessage m, XNetListener reply) {
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
    
    @Override
    protected void forwardToPort(AbstractMRMessage m, AbstractMRListener reply) {
        XNetPlusMessage m2 = (XNetPlusMessage)m;
        // just check
        StreamReceiver r = receiver();
        lastSentMessage = m2;
        getQueueController().message(m2);
        r.markTransmission(m2);
        packetizer.forwardToPort(m2, (XNetListener)reply);
    }
    
    /**
     * Receives one preprocess into the XNetPlusReply object. 
     * @return the reply object.
     * @throws IOException on I/O error or comm termination.
     */
    XNetPlusReply receiveReply() throws IOException {
        XNetPlusReply msg = (XNetPlusReply)newReply();

        // wait for start if needed
        waitForStartOfReply(istream);

        // preprocess exists, now fill it
        loadChars(msg, istream);
        
        return msg;
    }

    /**
     * Records that a preprocess is being received.
     */
    @Override
    public void notifyMessageStart(XNetPlusReply msg) {
        StreamReceiver r = receiver;
        if (r != null) {
            r.incomingPacket(msg);
        }
    }
    
    /**
     * Returns the current receiver. Throws {@link IllegalStateException} if
     * the controller has not been (re)started.
     * @return the receiver.
     */
    private StreamReceiver receiver() {
        StreamReceiver r = this.receiver;
        if (r == null) {
            throw new IllegalStateException();
        }
        return r;
    }

    // used from the RESPONSE thread.
    private ResponseHandler responseHandler;

    @Override
    public void receiveLoop() {
        executor.submit(receiver = new StreamReceiver(this::receiveReply));
        responseHandler = new ResponseHandler(receiver, this);
        super.receiveLoop();
    }
    
    @Override
    public void handleOneIncomingReply() throws IOException {
        responseHandler.handleOneIncomingReply();
    }
    
    /**
     * Distributes reply among the listeners, and potentially sender. Overridable
     * for possible extension with pre/post actions.
     * 
     * @param reply the reply to distribute
     * @param lastSender the sender that should be targetted by the reply; null for no target
     * @param r Runnable to execute in the layout thread, which will actually distribute the preprocess
     */
    protected ReplyOutcome distributeReply(AbstractMRReply reply, AbstractMRListener lastSender, Runnable r) {
        AtomicReference<ReplyOutcome> result = new AtomicReference<>();
        distributeReply(() -> {
            XNetPlusReply plusReply = (XNetPlusReply)reply;
            // pre-process
            ReplyOutcome out = null;
            Exception savedEx = null;
            try {
                getQueueController().preprocess(plusReply);
                // maybe change the result to AtomicReference, so even in case
                // the user callback fails, the outcome is OK.
                out = getQueueController().processReply(plusReply);
                try {
                    // attempt to propperly process the protocol.
                    // run through all other listeners.
                    r.run();
                } catch (Exception ex) {
                    // catch errors in JMRI higher layers
                    savedEx = ex;
                } finally {
                    if (savedEx != null) {
                        out.withError(savedEx);
                    }
                }
            } catch (Exception ex) {
                if (out == null) {
                    // last resort, if the protocol itself fails.
                    out = ReplyOutcome.finished(plusReply, ex);
                }
                out.withError(ex);
            }
            result.set(out);
        });
        return result.get();
    }

    public final void snapshot(StateMemento m) {
        synchronized (m) {
            synchronized (xmtRunnable) {
                m.retransmitCount = retransmitCount;
                m.mCurrentMode = mCurrentMode;
                m.mCurrentState = mCurrentState;
                m.origCurrentState = mCurrentState;
            }
            update(m);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Saved state memento: {}, lastMessage: {}, lastSender: {}", m.toString(), m.lastMessage, m.mLastSender);
            }
        }
    }

    public synchronized void update(StateMemento m) {
        m.mLastSender = (XNetListener)mLastSender;
        m.lastMessage = lastSentMessage;
    }
    
    public void commit(StateMemento m, boolean notify) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Committing memento: {}, notify: {}", m.toString(), notify);
        }
        mCurrentState = m.mCurrentState;
        mCurrentMode = m.mCurrentMode;
        retransmitCount = m.retransmitCount;
        synchronized (xmtRunnable) {
            replyInDispatch = false;
            if (notify) {
                LOG.debug("Notifying transmit thread...");
                xmtRunnable.notify();
            }
        }
    }
    
    public ReplyOutcome distributeReply(StateMemento m, XNetPlusReply msg, XNetListener target) {
        synchronized (xmtRunnable) {
            replyInDispatch = true;
        }
        synchronized (m) {
            LOG.debug("dispatch reply of length {} contains \"{}\", state {}, origState {}", 
                    msg.getNumDataElements(), msg, m.mCurrentState, m.origCurrentState);
        }
        Runnable r = new RcvNotifier(msg, target, this);
        return distributeReply(msg, mLastSender, r);
    }
    
    @Override
    public void commandFinished(StateMemento m, ReplyOutcome out) {
        ThreadingUtil.runOnLayout(() -> 
                getQueueController().replyFinished(out)
        );
    }

    
    // operated from the received thread.
    private int retransmitCount;
    
    
    
    @Override
    public void terminateThreads() {
        StreamReceiver rcv = receiver();
        if (rcv != null) {
            rcv.stop();
            try {
                istream.close();
            } catch (IOException ex) {
                // ignore
            }
            try {
                ostream.close();
            } catch (IOException ex) {
                // ignore
            }
        }
        super.terminateThreads();
    }
    
    @Override
    protected XNetPlusReply newReply() {
        return new XNetPlusReply();
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(XNetPlusTrafficController.class);
}
