package jmri.jmrix.lenzplus.impl;

import java.io.IOException;
import jmri.jmrix.AbstractMRTrafficController;
import static jmri.jmrix.AbstractMRTrafficController.WAITMSGREPLYSTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINNORMMODESTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINPROGMODESTATE;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.StateMemento;
import jmri.jmrix.lenzplus.XNetPlusReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ResponseHandler instance will capture all the state changes until
 * after all replies for the command are processed. This is will prevent the
 * transmit thread from waking up too early.
 * <p>
 * There can be multiple broadcast / unsolicited messages preceding a transmission,
 * which can't change the transmission thread's variables. But after a transmission,
 * a directed reply + broadcast may come (in this order), and the transition
 * is made upon the directed reply. The subsequent broadcast may be optional, and
 * it won't come in certain situations.
 * <p>
 * In either case, the transmission thread's state must change only after all replies
 * are read up. Until that time, the state changes are buffered in the ResponseHandler.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public final class ResponseHandler extends StateMemento {
    protected final ReplyDispatcher dispatcher;
    protected final ReplySource receiver;
    private int progModeDelayTime;
    int followupTimeout = 30;
    boolean replyInDispatch = true;
    boolean warmedUp = false;
    
    private XNetPlusReply   backlog;

    public ResponseHandler(ReplySource s, ReplyDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.receiver = s;
    }

    public int getFollowupTimeout() {
        return followupTimeout;
    }

    public void setFollowupTimeout(int followupTimeout) {
        this.followupTimeout = followupTimeout;
    }
    
    XNetPlusReply r;
    
    ReplyOutcome loopUnsolicitedMessages() throws IOException {
        while (receiver.isActive()) {
            LOG.debug("Loop unsolicited messages");
            r = receiver.take();
            // re-check after possible block
            if (!receiver.isActive() || r == null) {
                return null;
            }
            LOG.debug("Received reply: {}, response to: {}" , r, r.getResponseTo());
            
            dispatcher.snapshot(this);

            ReplyOutcome outcome = dispatcher.distributeReply(this, r, mLastSender);
            LOG.debug("Reply outcome: {}", outcome);
            if (outcome.isSolicited()) {
                return outcome;
            }
            if (r.isRetransmittableErrorMsg()) {
                System.err.println("error");
            }
        }
        LOG.debug("Receiver de-activated");
        return null;
    }

    /**
     * This loop provides processing coordinated between the transmit and receive threads.
     * It attempts to acquire a message from the receiver, blocking of no message is available.
     * When acquired,
     */
    public void handleOneIncomingReply() throws IOException {
        processOneCommand();
    }
    
    void checkOutcomeConsistent(ReplyOutcome o) {
        if (!o.isComplete() && o.isAdditionalReplyRequired()) {
            // OK, we're waiting for additional message, but then we should not
            // make any transition:
            if (!(r.isUnsolicited() || r.isBroadcast())) {
                LOG.warn("Going to make a transition on INCOMPLETE outcome: outcome: {}, reply: {}", o, r);
            }
            return;
        }
        // incomplete + no additional reply required; complete
        if (r.isUnsolicited() || r.isBroadcast()) {
            LOG.warn("Completed from unsolicited or broadcast; outcome: {}, reply: {}", o, r);
        }
    }
    
    void processOneCommand() throws IOException {
        replyInDispatch = true;
        ReplyOutcome outcome = loopUnsolicitedMessages();
        if (outcome == null) {
            return;
        }
        XNetPlusReply solicited = r;
        try {
            
            makeTransition(outcome);
            while (!outcome.isComplete() && receiver.isActive()) {
                /*
                if (r.isUnsolicited()) {
                    LOG.warn("ReplyHandler thinks the message is unsolicited, but is' NOT a broadcast");
                }
                */
                if (outcome.isAdditionalReplyRequired()) {
                    LOG.debug("Unfinished command, required additional reply");
                    r = receiver.take();
                } else {
                    LOG.debug("Unfinished command, peeking for next reply");
                    r = receiver.takeWithTimeout(30);
                    if (r == null) {
                        if (!receiver.isActive()) {
                            return;
                        }
                        // force-complete.
                        outcome.setComplete(true);
                        break;
                    }
                }
                outcome = dispatcher.distributeReply(this, r, mLastSender);
                if (outcome == null) {
                    return;
                }
                LOG.debug("Additional reply outcome: {}", outcome);
                makeTransition(outcome);
                if (outcome.isSolicited()) {
                    solicited = r;
                }
            }
        } finally {
            dispatcher.commandFinished(this, outcome);
            if (outcome.getException() != null) {
                Throwable t = outcome.getException();
                LOG.error("An error occurred during reply processing: {}", t.getMessage());
                LOG.error("Stacktrace:", t);
            }
            if (receiver.isActive()) {
                receiver.resetExpectedReply(solicited);
                commit();
            }
        }
    }
    
    void commit() {
        dispatcher.commit(this, !replyInDispatch);
    }
    
    private boolean handleSolicitedStateTransition(XNetReply msg) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Transitioning from: {}", this);
        }
        switch (mCurrentState) {
            case WAITMSGREPLYSTATE:
                {
                    if (msg.isRetransmittableErrorMsg()) {
                        LOG.error("Automatic Recovery from Error Message: {}.  Retransmitted {} times.", msg, retransmitCount);
                        // reset the incoming message flag, so asynchronous are unsolicited again:
                        receiver.resetExpectedReply(r);
                        synchronized (this) {
                            mCurrentState = AbstractMRTrafficController.AUTORETRYSTATE;
                            if (retransmitCount > 0) {
                                try {
                                    wait(retransmitCount * 100L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); // retain if needed later
                                }
                            }
                            replyInDispatch = false;
                            retransmitCount++;
                        }
                    } else {
                        // update state, and notify to continue
                        mCurrentState = AbstractMRTrafficController.NOTIFIEDSTATE;
                        replyInDispatch = false;
                        retransmitCount = 0;
                    }
                    break;
                }
            case WAITREPLYINPROGMODESTATE:
                {
                    // entering programming mode
                    mCurrentMode = AbstractMRTrafficController.PROGRAMINGMODE;
                    replyInDispatch = false;
                    // check to see if we need to delay to allow decoders to become
                    // responsive
                    int warmUpDelay = progModeDelayTime;
                    if (!warmedUp && warmUpDelay != 0) {
                        warmedUp = true;
                        try {
                            synchronized (this) {
                                wait(warmUpDelay);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // retain if needed later
                        }
                    }
                    // update state, and notify to continue
                    mCurrentState = AbstractMRTrafficController.OKSENDMSGSTATE;
                    break;
                }
            case WAITREPLYINNORMMODESTATE:
                {
                    // entering normal mode
                    mCurrentMode = AbstractMRTrafficController.NORMALMODE;
                    replyInDispatch = false;
                    // update state, and notify to continue
                    mCurrentState = AbstractMRTrafficController.OKSENDMSGSTATE;
                    break;
                }
            default:
                LOG.debug("Transition unsuccessful.");
                return false;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Transitioned to: mode: {}, state: {}, replyInDispatch: {}", 
                    modeName(mCurrentMode), stateName(mCurrentState), replyInDispatch);
        }
        return true;
    }
    
    void makeTransition(ReplyOutcome o) {
        checkOutcomeConsistent(o);
        makeTransition(r);
    }

    public void makeTransition(XNetPlusReply msg) {
        if (msg.isUnsolicited() || msg.isBroadcast()) {
            return;
        }
        boolean success = handleSolicitedStateTransition(msg);
        if (!success) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("State transition unsuccessful, trying from orig state {}", stateName(origCurrentState));
            }
            // retry with the original state
            int saveState = mCurrentState;
            mCurrentState = origCurrentState;
            success = handleSolicitedStateTransition(msg);
            if (!success) {
                mCurrentState = saveState;
                // FIXME bug: should occur inside the monitor to ensure cache propagation.
                // will be flushed "at some point", but can be stuck while waiting on a
                // next message.
                replyInDispatch = false;
                LOG.debug("Allowed unexpected reply received in state: {} was {}", mCurrentState, msg);
            }
        }
    }
    
    @Override
    public String toString() {
        String sn = stateName(mCurrentState);
        String mn = modeName(mCurrentMode);
        
        return String.format(
            "mode: %s, state: %s, orgState: %s ", mn, sn, stateName(origCurrentState)
        );
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(ResponseHandler.class);
}
