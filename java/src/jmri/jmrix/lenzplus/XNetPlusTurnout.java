package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.*;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.concurrent.GuardedBy;

/**
 * An alternative implementation for XPressNet turnouts. This implementation
 * uses advanced queues and does not interfere directly with XPressNet commands.
 * @author sdedic
 */
public class XNetPlusTurnout extends XNetTurnout implements XNetPlusResponseListener {
    /**
     * OFF message has been sent. Need to send more OFF messages because
     * of design decision (reasons unexplained/undocumented) to send at least 2 of them.
     */
    protected static final int OFFSENT = 1;
    protected static final int COMMANDSENT = 2;
    /**
     * All required OFF messages are sent. Can stop sending messages on
     * first OK received.
     */
    protected static final int OFFSENT_LAST = 3;
    protected static final int STATUSREQUESTSENT = 4;
    protected static final int IDLE = 0;
    
    protected int internalState = IDLE;
    
    /**
     * Last commanded state forwarded to the layout, for the purpose of turning 
     * the output OFF. -1 (the default) means that OFF command is formed from
     * the current KnwonState. The value must be set, when the command instruction
     * is queued for transmission. Reset when new command is processed from
     * the turnout queue, or the Turnout goes idle. Correctness rely on the
     * queuing mechanism.
     * <p>
     * This allows to compensate effects of temporary incorrect changes of 
     * Known/Commanded state implied by paired feedback processing, so wrong
     * messages are not sent back to the layout.
     */
    @GuardedBy("this")
    private int lastLayoutCommanded = -1;

    /* Static arrays to hold Lenz specific feedback mode information */
    static String[] modeNames = null;
    static int[] modeValues = null;

    @GuardedBy("this")
    protected int _mThrown = jmri.Turnout.THROWN;
    @GuardedBy("this")
    protected int _mClosed = jmri.Turnout.CLOSED;

    protected String _prefix = "X"; // default
    protected XNetTrafficController tc = null;

    public XNetPlusTurnout(String prefix, int pNumber, XNetTrafficController controller) {  // a human-readable turnout number must be specified!
        super(prefix, pNumber, controller);
        tc = controller;
        _prefix = prefix;
        mNumber = pNumber;

        /* Add additiona feedback types information */
        _validFeedbackTypes |= MONITORING | EXACT | SIGNAL;

        // Default feedback mode is MONITORING
        _activeFeedbackType = MONITORING;

        setModeInformation(_validFeedbackNames, _validFeedbackModes);

        // set the mode names and values based on the static values.
        _validFeedbackNames = getModeNames();
        _validFeedbackModes = getModeValues();

        // Register to get property change information from the superclass
        // Finally, request the current state from the layout.
        tc.getFeedbackMessageCache().requestCachedStateFromLayout(this);
    }

    /**
     * Set the mode information for XpressNet Turnouts.
     */
    private static synchronized void setModeInformation(String[] feedbackNames, int[] feedbackModes) {
        // if it hasn't been done already, create static arrays to hold
        // the Lenz specific feedback information.
        if (modeNames == null) {
            if (feedbackNames.length != feedbackModes.length) {
                log.error("int and string feedback arrays different length");
            }
            modeNames = new String[feedbackNames.length + 3];
            modeValues = new int[feedbackNames.length + 3];
            for (int i = 0; i < feedbackNames.length; i++) {
                modeNames[i] = feedbackNames[i];
                modeValues[i] = feedbackModes[i];
            }
            modeNames[feedbackNames.length] = "MONITORING";
            modeValues[feedbackNames.length] = MONITORING;
            modeNames[feedbackNames.length + 1] = "EXACT";
            modeValues[feedbackNames.length + 1] = EXACT;
            modeNames[feedbackNames.length + 2] = "SIGNAL";
            modeValues[feedbackNames.length + 2] = SIGNAL;
        }
    }

    static int[] getModeValues() {
        return modeValues;
    }

    static String[] getModeNames() {
        return modeNames;
    }

    public int getNumber() {
        return mNumber;
    }

    /**
     * Set the Commanded State.
     * This method overides {@link jmri.implementation.AbstractTurnout#setCommandedState(int)}.
     */
    @Override
    public void setCommandedState(int s) {
        if (log.isDebugEnabled()) {
            log.debug("set commanded state for XNet turnout {} to {}", getSystemName(), s);
        }
        synchronized (this) {
            newCommandedState(s);
        }
        myOperator = getTurnoutOperator(); // MUST set myOperator before starting the thread
        if (myOperator == null) {
            forwardCommandChangeToLayout(s);
            synchronized (this) {
                newKnownState(INCONSISTENT);
            }
        } else {
            myOperator.start();
        }
    }

    /**
     * Handle a request to change state by sending an XpressNet command.
     */
    @Override
    protected synchronized void forwardCommandChangeToLayout(int s) {
        if (s != _mClosed && s != _mThrown) {
            log.warn("Turnout {}: state {} not forwarded to layout.", mNumber, s);
            return;
        }
        // get the right packet
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getTurnoutCommandMsg(mNumber,
                (s & _mClosed) != 0,
                (s & _mThrown) != 0,
                true));
        if (getFeedbackMode() == SIGNAL) {
            msg.setTimeout(0); 
            tc.sendXNetMessage(msg, null);
        } else {
            tc.sendXNetMessage(msg, this);
        }
    }

    @Override
    protected void turnoutPushbuttonLockout(boolean _pushButtonLockout) {
        log.debug("Send command to {} Pushbutton {}T{}", (_pushButtonLockout ? "Lock" : "Unlock"), _prefix, mNumber);
    }

    /**
     * Request an update on status by sending an XpressNet message.
     */
    @Override
    public void requestUpdateFromLayout() {
        // This will handle ONESENSOR and TWOSENSOR feedback modes.
        super.requestUpdateFromLayout();

        // To do this, we send an XpressNet Accessory Decoder Information
        // Request.
        // The generated message works for Feedback modules and turnouts
        // with feedback, but the address passed is translated as though it
        // is a turnout address.  As a result, we substitute our base
        // address in for the address. after the message is returned.
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getFeedbackRequestMsg(mNumber,
                ((mNumber - 1) % 4) < 2));
        tc.sendXNetMessage(msg, null); //status is returned via the manager.

    }

    @Override
    public synchronized void setInverted(boolean inverted) {
        log.debug("Inverting Turnout State for turnout {}T{}", _prefix, mNumber);
        _inverted = inverted;
        if (inverted) {
            _mThrown = jmri.Turnout.CLOSED;
            _mClosed = jmri.Turnout.THROWN;
        } else {
            _mThrown = jmri.Turnout.THROWN;
            _mClosed = jmri.Turnout.CLOSED;
        }
        super.setInverted(inverted);
    }

    @Override
    public boolean canInvert() {
        return true;
    }

    /**
     * Package protected class which allows the Manger to send
     * a feedback message at initilization without changing the state of the
     * turnout with respect to whether or not a feedback request was sent. This
     * is used only when the turnout is created by on layout feedback.
     */
    synchronized void initmessage(XNetReply l) {
        int oldState = internalState;
        message(l);
        internalState = oldState;
    }

    @Override
    public void completed(CompletionStatus s) {
        XNetPlusMessage m = s.getCommand();
        if (!m.getCommandedOutputState()) {
            return;
        }
        switch (getFeedbackMode()) {
            case EXACT:
                completeExactModeFeedback(s);
                break;
            case MONITORING:
                knownStateFromFeedback(s.getReply(), true);
                break;
            case DIRECT:
            default:
                // Default is direct mode
                knownStateFromFeedback(s.getReply(), true);
        }
    }

    @Override
    public void concurrentLayoutOperation(CompletionStatus s) {
        syncAfterConcurrentMessage();
    }

    @Override
    public void failed(CompletionStatus s) {
        if (s.isTimeout()) {
            sendOffMessage(s.getCommand());
        }
    }
    
    /**
     * Handle an incoming message from the XpressNet.
     */
    @Override
    public synchronized void message(XNetPlusReply l) {
        log.debug("received message: {}", l);
        switch (getFeedbackMode()) {
            case EXACT:
                handleExactModeFeedback(l);
                break;
            case MONITORING:
                handleMonitoringModeFeedback(l);
                break;
            case DIRECT:
            default:
                // Default is direct mode
                // no action: command-related actions are handled in completed()
        }
    }

    /**
     * Listen for the messages to the LI100/LI101.
     */
    @Override
    public void message(XNetMessage l) {
    }

    /**
     * Handle a timeout notification.
     */
    @Override
    public synchronized void notifyTimeout(XNetPlusMessage msg) {
        log.debug("Notified of timeout on message {}", msg);
        // If we're in the OFFSENT state, we need to send another OFF message.
        if (!msg.getCommandedOutputState()) {
            sendOffMessage();
        }
    }
    
    /**
     *  With Monitoring Mode feedback, if we see a feedback message, we
     *  interpret that message and use it to display our feedback.
     *  <p>
     *  After we send a request to operate a turnout, We ask the command
     *  station to stop sending information to the stationary decoder
     *  when the either a feedback message or an "OK" message is received.
     *
     *  @param l an {@link XNetReply} message
     */
    private synchronized void handleMonitoringModeFeedback(XNetPlusReply l) {
        /* In Monitoring Mode, We have two cases to check if CommandedState
         does not equal KnownState, otherwise, we only want to check to
         see if the messages we receive indicate this turnout chagned
         state
         */
        log.debug("Handle Message for turnout {} in MONITORING feedback mode ", mNumber);
        if (knownStateFromFeedback(l, true)) {
            log.debug("Turnout {} MONITORING feedback mode - state change from feedback.", mNumber);
        }
    }
    
    @GuardedBy("this")
    private ExactMotionCompleter    motionCompleter;
    
    @GuardedBy("this")
    private ExactMotionCompleter    postCommandResync;
    
    private synchronized void waitMotionComplete(boolean resyncCommandedState) {
        if (motionCompleter == null) {
            motionCompleter = new ExactMotionCompleter();
        }
        motionCompleter.resyncCommandedState |= resyncCommandedState;
    }
    
    private synchronized void syncAfterConcurrentMessage() {
        if (postCommandResync != null) {
            return;
        }
        postCommandResync = new ExactMotionCompleter();
        postCommandResync.inquiry(true);
    }
    
    /**
     * Serves two purposes. It watches out for 'motion complete' signal to
     * set the KnownState to the final state after movement completes. And it
     * serves as a post-command handler in case a concurrent alien turnout message
     * seems to appear on the layout, so that KnownState AND CommandState
     * is properly updated at the end.
     */
    class ExactMotionCompleter implements XNetPlusResponseListener {
        /**
         * Flag that can be set up during lifetime. After motion completes,
         * an low-priority inquiry will be sent after all commands complete.
         */
        @GuardedBy("XNetPlusTurnout.this")
        private boolean resyncCommandedState;
        
        public ExactMotionCompleter() {
        }
        
        void markConcurrent() {
            resyncCommandedState = true;
        }
        
        /**
         * Sends an inquiry into the layout. 
         * @param recheck 
         */
        void inquiry(boolean recheck) {
            log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion not complete", mNumber);
            // If the motion is NOT complete, send a feedback
            // request for this nibble
            int prio = recheck ? 
                    XNetPlusMessage.DEFAULT_PRIORITY + 10 : 
                    XNetPlusMessage.DEFAULT_PRIORITY - 10;
            XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getFeedbackRequestMsg(
                    mNumber, ((mNumber % 4) <= 1))).
                    // delay the query a little
                    delayed(100).
                    // give GREATER priority than motion commands. This will block further
                    // switches until we're satisfied.
                    asPriority(prio);
            tc.sendXNetMessage(msg, this);
        }

        @Override
        public void completed(CompletionStatus s) {
            XNetPlusReply r = s.getReply();
            FeedbackItem fi = r.selectTurnoutFeedback(mNumber, true).orElseThrow(
                    () -> new IllegalStateException("Fedback expected"));
            
            boolean protectCommand = true;
            if (!fi.isMotionComplete()) {
                inquiry(false);
                return;
            }
            synchronized (XNetPlusTurnout.this) {
                if (this == motionCompleter) {
                    // we are motion completer
                    motionCompleter = null;
                } else if (resyncCommandedState) {
                    if (postCommandResync == this) {
                        // we are the resync instance. Update both KnownState and CommandState
                        protectCommand = false;
                        // break the inquiry loop
                        resyncCommandedState = false;
                        postCommandResync = null;
                    } else if (postCommandResync == null) {
                        // no resync is scheduled, it will be us:
                        postCommandResync = this;
                    }
                }
            }
            knownStateFromFeedback(r, protectCommand);
            if (resyncCommandedState) {
                inquiry(true);
            }
        }

        @Override
        public void failed(CompletionStatus s) {
            XNetPlusResponseListener.super.failed(s); //To change body of generated methods, choose Tools | Templates.
        }
        
    }
    
    private synchronized void completeExactModeFeedback(CompletionStatus s) {
        XNetPlusReply reply = s.getReply();
        // implicitly checks for isFeedbackBroadcastMessage()
        Optional<FeedbackPlusItem> opt = reply.selectTurnoutFeedback(mNumber);
        if (opt.isPresent()) {
            FeedbackPlusItem l = opt.get();
            int messageType = l.getType();
            if (messageType == 1 && !l.isMotionComplete()) {
                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion not complete", mNumber);
                waitMotionComplete(s.getConcurrentReply() != null);
            } else {
                knownStateFromFeedback(reply, true);
            }
        }
    }
    
    private boolean ignoreCommanded;

    private boolean knownStateFromFeedback(XNetPlusReply r, boolean protectCommand) {
        Optional<FeedbackPlusItem> opt = r.selectTurnoutFeedback(mNumber, true);
        boolean b;
        if (opt.isPresent()) {
            b = parseFeedbackMessage(opt.get(), r, protectCommand);
        } else {
            b = false;
        }
        if (b && r.isConcurrent()) {
            syncAfterConcurrentMessage();
        }
        return b;
    }

    /**
     *  With Exact Mode feedback, if we see a feedback message, we
     *  interpret that message and use it to display our feedback.
     *  <p>
     *  After we send a request to operate a turnout, We ask the command
     *  station to stop sending information to the stationary decoder
     *  when the either a feedback message or an "OK" message is received.
     *
     *  @param reply The reply message to process
     */
    private synchronized void handleExactModeFeedback(XNetPlusReply reply) {
        // We have three cases to check if CommandedState does
        // not equal KnownState, otherwise, we only want to check to
        // see if the messages we receive indicate this turnout chagned
        // state
        log.debug("Handle Message for turnout {} in EXACT feedback mode ", mNumber);
        // implicitly checks for isFeedbackBroadcastMessage()
        reply.selectTurnoutFeedback(mNumber, true).ifPresent(l -> {
            if (l.getType() == 1 && !l.isMotionComplete()) {
                waitMotionComplete(reply.isConcurrent());
                return;
            }
            parseFeedbackMessage(l, reply, true);
        });
    }
    
    /**
     * Send an "Off" message to the decoder for this output. 
     */
    @Override
    protected synchronized void sendOffMessage() {
    }
    
    private void sendOffMessage(XNetPlusMessage command) {
        int cmd = command.getCommandedTurnoutStatus();
        XNetPlusMessage off = XNetPlusMessage.create(XNetMessage.getTurnoutCommandMsg(mNumber,
                cmd == CLOSED, cmd == THROWN, false)).
                delayed(100).
                asPriority(XNetPlusMessage.HIGH_PRIORITY);
        newKnownState(getCommandedState());
        // Then send the message.
        tc.sendHighPriorityXNetMessage(off, this);
    }
    
    @Override
    protected void newCommandedState(int s) {
        if (!ignoreCommanded) {
            super.newCommandedState(s);
        }
    }
    
    /**
     * Parse the feedback message, and set the status of the turnout
     * accordingly.
     *
     * @param l  turnout feedback item
     * 
     * @return 0 if address matches our turnout -1 otherwise
     */
    private synchronized boolean parseFeedbackMessage(FeedbackPlusItem l, XNetPlusReply r, boolean protectCommanded) {
        log.debug("Message for turnout {}", mNumber);
        int n = -1;
        switch (l.getTurnoutStatus()) {
            case THROWN:
                n = _mThrown;
                break;
            case CLOSED:
                n = _mClosed;
                break;
            default:
                // the state is unknown or inconsistent.  If the command state
                // does not equal the known state, and the command repeat the
                // last command
                XNetPlusMessage cmd = r.getResponseTo();
                int cs = cmd != null ? cmd.getCommandedTurnoutStatus() : getCommandedState();
                if (cs != getKnownState()) {
                    forwardCommandChangeToLayout(cs);
                }
                return false;
        }
        int fn = n;
        newKnownState(n, l.isConsumed() & protectCommanded);
        return true;
    }
    
    private void newKnownState(int curKnownState, boolean onlyKnown) {
        boolean save = ignoreCommanded;
        try {
            ignoreCommanded = onlyKnown;
            newKnownState(curKnownState);
        } finally {
            ignoreCommanded = save;
        }
    }
    
    @Override
    public void newKnownState(int curKnownState) {
        boolean changeCommanded = false;
        synchronized (this) {
            int oldKnownState = getKnownState();
            if (curKnownState != INCONSISTENT
                    && getCommandedState() == oldKnownState) {
                changeCommanded = true;
            }
        }
        super.newKnownState(curKnownState); 
        if (changeCommanded && !ignoreCommanded) {
            newCommandedState(curKnownState);
        }
    }

    // data members
    protected int mNumber;   // XpressNet turnout number

    private static final Logger log = LoggerFactory.getLogger(XNetPlusTurnout.class);

}
