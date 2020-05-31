package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.*;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.concurrent.GuardedBy;
import jmri.Turnout;

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
    protected void forwardCommandChangeToLayout(int s) {
        forwardCommandChangeToLayout(s, false);
    }
        
    protected synchronized void forwardCommandChangeToLayout(int s, boolean repeat) {
        if (s != _mClosed && s != _mThrown) {
            log.warn("Turnout {}: state {} not forwarded to layout.", mNumber, s);
            return;
        }
        // get the right packet
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getTurnoutCommandMsg(mNumber,
                (s & _mClosed) != 0,
                (s & _mThrown) != 0,
                true));
        if (repeat) {
            // inject before other possibly waiting messages
            msg.asPriority(msg.getPriority() - 10);
        }
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
        offTimeoutCount = 0;    // reset off timeout after some successful command.
        log.debug("Turnout {} mode {}, completing: ", mNumber, getFeedbackModeName(), s);
        XNetPlusMessage m = s.getCommand();
        if (!m.getCommandedOutputState()) {
            sendQueuedMessage();
            return;
        }
        switch (getFeedbackMode()) {
            case EXACT:
                completeExactModeFeedback(s);
                break;
            case MONITORING:
                completeMonitoringModeCommand(s);
                break;
            case DIRECT:
            default:
                // no action, should be handled by the superclass.
                completeDirectModeCommand(s);
                break;
        }
        // just to be sure, respect the queueing in superclass:
        sendQueuedMessage();
    }
    
    /**
     * Completes a direct-mode command by changing KnownState to the 
     * state of the completed command.
     * @param s completion status with the command.
     */
    void completeDirectModeCommand(CompletionStatus s) {
        int n = stateFromRaw(s.getCommand().getCommandedTurnoutStatus());
        newKnownState(n, true);
    }

    /**
     * If the feedback item indicates incomplete motion, postpones the action into
     * {@link #waitMotionComplete}. Otherwise completes the command normally.
     * @param s the completion status.
     */
    void completeExactModeFeedback(CompletionStatus s) {
        XNetPlusReply reply = s.getReply();
        // implicitly checks for isFeedbackBroadcastMessage()
        Optional<FeedbackPlusItem> opt = reply == null ? Optional.empty() : reply.selectTurnoutFeedback(mNumber);
        if (opt.isPresent()) {
            FeedbackPlusItem l = opt.get();
            int messageType = l.getType();
            if (messageType == 1 && !l.isMotionComplete()) {
                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion not complete", mNumber);
                // XXX: must give context of the current command's status
                waitMotionComplete(s.getCommand(), s.getConcurrentReply() == null);
                return;
            }
        }
        completeMonitoringModeCommand(s);
    }
    
    /**
     * Completes the switching command. Changes KnownState to match the
     * completion's feedback state (if present), or the command's state.
     * @param s 
     */
    void completeMonitoringModeCommand(CompletionStatus s) {
        completeSwitchCommand(s.getCommand(), s.getReply(), s.getConcurrentReply() != null);
    }
    
    /**
     * Extract the turnout state from the command, counting with inversion.
     * If the command is null or does not contain the info, uses CommandedState.
     * @param cmd the command
     * @return the turnout state
     */
    int stateFromCommand(XNetPlusMessage cmd) {
        if (cmd == null) {
            return getCommandedState();
        }
        int n = stateFromRaw(cmd.getCommandedTurnoutStatus());
        return n != -1 ? n : getCommandedState();
    }
    
    @Override
    public void concurrentLayoutOperation(CompletionStatus s, XNetPlusReply concurrent) {
        syncAfterConcurrentMessage();
    }
    
    /**
     * Number of timeouts occurred before 
     */
    private int offTimeoutCount;

    @Override
    public void failed(CompletionStatus s) {
        XNetPlusMessage m = s.getCommand();
        if (!(s.isTimeout() && m.getElement(0) == XNetConstants.ACC_OPER_REQ)) {
            // not a timeout, or not a turnout command. That;s a life. Log.
            Supplier<?> o = () -> m.toMonitorString();
            log.warn("Operation failed: {}", o);
            return;
        }
        if (!m.getCommandedOutputState()) {
            if (++offTimeoutCount > 3) {
                // prevent timed-out OFF from looping indefinitely
                offTimeoutCount = 0;
                return;
            }
        }
        sendOffMessage(m);
    }
    
    /**
     * Handle an incoming message from the XpressNet.
     */
    @Override
    public synchronized void message(XNetPlusReply l) {
        log.debug("Turnout {} mode {} recevied: {}",  this, getFeedbackModeName(), l);
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
        log.debug("Turnout {} processing ends.", this);
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
        knownStateFromFeedback(l, false);
    }

    /**
     * Handle to the active motion completer. {@code null} means the
     * state is not watched.
     */
    @GuardedBy("this")
    private ExactMotionCompleter    motionCompleter;
    
    /**
     * Handle to the active post-command resync with the layout. 
     */
    @GuardedBy("this")
    ExactMotionCompleter    postCommandResync;

    /**
     * Performs {@link #completeSwitchCommand(jmri.jmrix.lenzplus.XNetPlusMessage, jmri.jmrix.lenzplus.XNetPlusReply, boolean)} after
     * switch movement completes. If `resyncCommandState` is true, will also fire a post-command inquiry after that.
     * 
     * @param msg
     * @param resyncCommandedState 
     */
    private ExactMotionCompleter waitMotionComplete(XNetPlusMessage msg, boolean resyncCommandedState) {
        ExactMotionCompleter c;
        synchronized (this) {
            if (motionCompleter == null) {
                motionCompleter = new ExactMotionCompleter(msg);
            }
            motionCompleter.resyncCommandedState |= resyncCommandedState;
            c = motionCompleter;
        }
        c.inquiryOnce(false);
        return c;
    }
    
    private void syncAfterConcurrentMessage() {
        if (getFeedbackMode() == DIRECT) {
            // just ignore
            return;
        }
        ExactMotionCompleter c;
        synchronized (this) {
            if (postCommandResync != null) {
                return;
            }
            if (motionCompleter != null) {
                motionCompleter.markConcurrent();
                return;
            }
            c = postCommandResync = new ExactMotionCompleter(null).markConcurrent();
        }
        c.inquiryOnce(true);
    }

    @SuppressWarnings("NonPublicExported")
    /* test-private */ ExactMotionCompleter getMotionCompleter() {
        return motionCompleter;
    }

    @SuppressWarnings("NonPublicExported")
    /* test-private */ public ExactMotionCompleter getPostCommandResync() {
        return postCommandResync;
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
         * Command whose motion is not complete. Possibly {@code null}
         * if the Completer is acting as a resync.
         */
        private final XNetPlusMessage originalCommand;
        /**
         * Flag that can be set up during lifetime. After motion completes,
         * an low-priority inquiry will be sent after all commands complete.
         */
        @GuardedBy("XNetPlusTurnout.this")
        private boolean resyncCommandedState;
        
        private boolean protectCommand = true;
        
        private boolean posted;
        
        public ExactMotionCompleter(XNetPlusMessage originalCommand) {
            this.originalCommand = originalCommand;
        }
        
        ExactMotionCompleter markConcurrent() {
            resyncCommandedState = true;
            return this;
        }
        
        ExactMotionCompleter protectCommand(boolean protect) {
            protectCommand &= protect;
            return this;
        }
        
        void inquiryOnce(boolean recheck) {
            if (posted) {
                return;
            }
            inquiry(recheck);
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
                    delayed(posted ? 100 : 0).
                    // give GREATER priority than motion commands. This will block further
                    // switches until we're satisfied.
                    asPriority(prio);
            tc.sendXNetMessage(msg, this);
            posted = true;
        }

        @Override
        public void completed(CompletionStatus s) {
            XNetPlusReply r = null;
            
            if (s.isSuccess()) {
                r = s.getReply();
                FeedbackItem fi = r.selectTurnoutFeedback(mNumber, true).orElseThrow(
                        () -> new IllegalStateException("Fedback expected"));

                // not complete, ask again
                if (!fi.isMotionComplete() && getFeedbackMode() == XNetTurnout.EXACT) {
                    inquiry(false);
                    return;
                }
            }
            handleCompleted(r);
        }

        @Override
        public void failed(CompletionStatus s) {
            log.warn("Inquiry failed or timed out: {}", s.getCommand());
            handleCompleted(null);
        }
            
        private void handleCompleted(XNetPlusReply r) {
            synchronized (XNetPlusTurnout.this) {
                if (this == motionCompleter) {
                    // we are motion completer
                    motionCompleter = null;
                } else if (postCommandResync == this) {
                    // we are the resync instance. Update both KnownState and CommandState
                    protectCommand = false;
                    // break the inquiry loop
                    resyncCommandedState = false;
                    postCommandResync = null;
                } else {
                    // some error ?
                    return;
                }
            }
            if (originalCommand != null) {
                // completing a command
                completeSwitchCommand(originalCommand, r, resyncCommandedState);
            } else if (r != null) {
                knownStateFromFeedback(r, protectCommand);
            }
            // the knownStateFromFeedback might posted a resync; if it does,
            // the following will have no effect. Or there can be
            // a pending task from other code path, anyway the resync
            // will happen.
            if (resyncCommandedState) {
                syncAfterConcurrentMessage();
            }
        }
    }
    
    /**
     * Temporarily blocks commanded state changes. It's valid just
     */
    private ThreadLocal<Boolean> ignoreCommanded = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

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
                waitMotionComplete(null, reply.isConcurrent()).protectCommand(false);
            }  else {
                knownStateFromFeedback(reply, false); 
            }
        });
    }
    
    /**
     * Completes switch command by changing states. The known state is always set to the state
     * from the feedback, if present. If the feedback is inconsistent, the completed command
     * is repeated. The commanded state will be ALSO updated, if the feedback received does
     * not match the expected state from the command.
     * <p>
     * If there was a concurrent message, a re-synchronization is started.
     * @param cmd
     * @param feedback
     * @param concurrent 
     */
    void completeSwitchCommand(XNetPlusMessage cmd, XNetPlusReply feedback, boolean concurrent) {
        int cState = stateFromCommand(cmd);
        int fState = cState;
        if (feedback != null) {
            Optional<FeedbackPlusItem> opt = feedback.selectTurnoutFeedback(mNumber);
            if (opt.isPresent()) {
                fState = stateFromRaw(opt.get().getTurnoutStatus());
            }
        }
        if (fState == -1) {
            // the feedback is there, but is inconsistent. Repeat the command
            if (cState != getKnownState()) {
                forwardCommandChangeToLayout(cState, true);
                return;
            }
        } else {
            // if the feedback says something different, use the feedback to
            // update commanded state as well
            newKnownState(fState, fState == cState);
        }
        if (fState != cState || concurrent) {
            syncAfterConcurrentMessage();
        }
    }
    
    /**
     * Updates KnownState form the feedback. This method is called from 
     * an unsolicited feedback processing or concurrent resync operation. The resync operation
     * will not protect the commanded state, will always set it to the known one.
     * <p>
     * If command state protection is true (= unsolicited feedback), the command state is
     * only changed if the feedback is not marked as consumed.
     * <p>
     * If a feedback is inconsistent, it will issue another command to switch
     * the turnout to the commanded state. Otherwise, if the reply was marked
     * as concurrent, will re-synchronize JMRI with the layout state.
     * 
     * @param r the received feedback reply
     * @param protectCommand true, if the commanded state should be protected. False forces
     * commanded state to match the known one.
     * @return status
     */
    private boolean knownStateFromFeedback(XNetPlusReply r, boolean protectCommand) {
        Optional<FeedbackPlusItem> opt = r.selectTurnoutFeedback(mNumber);
        boolean b;
        if (opt.isPresent()) {
            FeedbackPlusItem l = opt.get();
            int n = stateFromRaw(l.getTurnoutStatus());
            if (n == -1) {
                // the state is unknown or inconsistent.  If the command state
                // does not equal the known state, and the command repeat the
                // last command
                int cs = getCommandedState();
                if (cs != getKnownState()) {
                    if (cs != Turnout.THROWN && cs != Turnout.CLOSED) {
                        log.warn("{}: Feedback inconsistent, but CommandedState as well", this);
                        return false;
                    }
                    log.debug("Feedback inconsistent, Known != Commanded. Repeating command: {}", cs);
                    forwardCommandChangeToLayout(cs);
                    return false;
                }
            } else {
                log.debug("Change KnownState to: {}", n);
                newKnownState(n, l.isConsumed() & protectCommand);
            }
        }
        if (r.isConcurrent()) {
            log.debug("Scheduling concurrent resync");
            syncAfterConcurrentMessage();
        }
        return true;
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
        if (!ignoreCommanded.get()) {
            super.newCommandedState(s);
        }
    }
    
    /**
     * Interpret the Turnout.* state constants with respect to possible inversion.
     * Return -1 for inconsistent/unknown states
     * @param raw raw state
     * @return value suitable for known and commanded state.
     */
    private int stateFromRaw(int raw) {
        switch (raw) {
            case THROWN:
                return _mThrown;
            case CLOSED:
                return _mClosed;
            default:
                return -1;
        }
    }
    
    private void newKnownState(int curKnownState, boolean onlyKnown) {
        boolean save = ignoreCommanded.get();
        try {
            ignoreCommanded.set(onlyKnown);
            newKnownState(curKnownState);
        } finally {
            ignoreCommanded.set(save);
        }
    }
    
    @Override
    public void newKnownState(int curKnownState) {
        boolean changeCommanded = false;
        synchronized (this) {
            int oldKnownState = getKnownState();
            if (curKnownState != INCONSISTENT
                    && getCommandedState() == oldKnownState) {
                changeCommanded = !ignoreCommanded.get();
            }
        }
        super.newKnownState(curKnownState); 
        if (changeCommanded) {
            newCommandedState(curKnownState);
        } else {
            log.debug("CommandedState change ingored.");
        }
    }

    // data members
    protected int mNumber;   // XpressNet turnout number

    private static final Logger log = LoggerFactory.getLogger(XNetPlusTurnout.class);
    
}
