package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.*;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.concurrent.GuardedBy;

/**
 * An alternative implementation for XPressNet turnouts. This implementation
 * uses advanced queues and does not interfere directly with XPressNet commands.
 * @author sdedic
 */
public class XNetPlusTurnout extends XNetTurnout implements XNetListener {
    
    /* State information */
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

        requestList = new ArrayDeque<>();

        /* Add additiona feedback types information */
        _validFeedbackTypes |= MONITORING | EXACT | SIGNAL;

        // Default feedback mode is MONITORING
        _activeFeedbackType = MONITORING;

        setModeInformation(_validFeedbackNames, _validFeedbackModes);

        // set the mode names and values based on the static values.
        _validFeedbackNames = getModeNames();
        _validFeedbackModes = getModeValues();

        // Register to get property change information from the superclass
        _stateListener = new XNetTurnoutStateListener(this);
        this.addPropertyChangeListener(_stateListener);
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
        XNetMessage msg = XNetMessage.getTurnoutCommandMsg(mNumber,
                (s & _mClosed) != 0,
                (s & _mThrown) != 0,
                true);
        if (getFeedbackMode() == SIGNAL) {
            msg.setTimeout(0); // Set the timeout to 0, so the off message can
            // be sent immediately.
            // leave the next line commented out for now.
            // It may be enabled later to allow SIGNAL mode to ignore
            // directed replies, which lets the traffic controller move on
            // to the next message without waiting.
            //msg.setBroadcastReply();
            tc.sendXNetMessage(msg, null);
            sendOffMessage();
        } else {
            queueMessage(msg, COMMANDSENT, this);
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
        XNetMessage msg = XNetMessage.getFeedbackRequestMsg(mNumber,
                ((mNumber - 1) % 4) < 2);
        queueMessage(msg,IDLE,null); //status is returned via the manager.

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

    /**
     * Handle an incoming message from the XpressNet.
     */
    @Override
    public synchronized void message(XNetReply l) {
        log.debug("received message: {}", l);
        if (internalState == OFFSENT) {
            if (l.isOkMessage() && !l.isUnsolicited()) {
                /* the command was successfully received */
                synchronized (this) {
                    newKnownState(getCommandedState());
                }
                sendQueuedMessage();
                return;
            } else if (l.isRetransmittableErrorMsg()) {
                return; // don't do anything, the Traffic
                // Controller is handling retransmitting
                // this one.
            } else {
                /* Default Behavior: If anything other than an OK message
                 is received, Send another OFF message. */
                log.debug("Message is not OK message. Message received was: {}", l);
                sendOffMessage();
            }
        }

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
                handleDirectModeFeedback(l);
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
    public synchronized void notifyTimeout(XNetMessage msg) {
        log.debug("Notified of timeout on message {}", msg);
        // If we're in the OFFSENT state, we need to send another OFF message.
        if (internalState == OFFSENT) {
            sendOffMessage();
        }
    }

    /**
     *  With Direct Mode feedback, if we see ANY valid response to our
     *  request, we ask the command station to stop sending information
     *  to the stationary decoder.
     *  <p>
     *  No effort is made to interpret feedback when using direct mode.
     *
     *  @param l an {@link XNetReply} message
     */
    private synchronized void handleDirectModeFeedback(XNetReply l) {
        /* If commanded state does not equal known state, we are
         going to check to see if one of the following conditions
         applies:
         1) The received message is a feedback message for a turnout
         and one of the two addresses to which it applies is our
         address
         2) We receive an "OK" message, indicating the command was
         successfully sent

         If either of these two cases occur, we trigger an off message
         */

        log.debug("Handle Message for turnout {} in DIRECT feedback mode   ", mNumber);
        if (internalState == STATUSREQUESTSENT && l.isUnsolicited()) {
            // set the reply as being solicited
            l.resetUnsolicited();
        }
        if (getCommandedState() != getKnownState() || internalState == COMMANDSENT) {
            if (l.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} DIRECT feedback mode - OK message triggering OFF message.", mNumber);
            } else {
                // implicitly checks for isFeedbackBroadcastMessage()
                if (!l.selectTurnoutFeedback(mNumber).isPresent()) {
                    return;
                }
                log.debug("Turnout {} DIRECT feedback mode - directed reply received.", mNumber);
                // set the reply as being solicited
                if (l.isUnsolicited()) {
                    l.resetUnsolicited();
                }
            }
            sendOffMessage();
            // Explicitly send two off messages in Direct Mode
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
    private synchronized void handleMonitoringModeFeedback(XNetReply l) {
        /* In Monitoring Mode, We have two cases to check if CommandedState
         does not equal KnownState, otherwise, we only want to check to
         see if the messages we receive indicate this turnout chagned
         state
         */
        log.debug("Handle Message for turnout {} in MONITORING feedback mode ", mNumber);
        if (internalState == IDLE || internalState == STATUSREQUESTSENT) {
            if (l.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                log.debug("Turnout {} MONITORING feedback mode - state change from feedback.", mNumber);
            }
        } else if (getCommandedState() != getKnownState()
                || internalState == COMMANDSENT) {
            if (l.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} MONITORING feedback mode - OK message triggering OFF message.", mNumber);
                sendOffMessage();
            } else {
                // In Monitoring mode, treat both turnouts with feedback
                // and turnouts without feedback as turnouts without
                // feedback.  i.e. just interpret the feedback
                // message, don't check to see if the motion is complete
                // implicitly checks for isFeedbackBroadcastMessage()
                if (l.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                    // We need to tell the turnout to shut off the output.
                    log.debug("Turnout {} MONITORING feedback mode - state change from feedback, CommandedState != KnownState.", mNumber);
                    sendOffMessage();
                }
            }
        }
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
    private synchronized void handleExactModeFeedback(XNetReply reply) {
        // We have three cases to check if CommandedState does
        // not equal KnownState, otherwise, we only want to check to
        // see if the messages we receive indicate this turnout chagned
        // state
        log.debug("Handle Message for turnout {} in EXACT feedback mode ", mNumber);
        if (getCommandedState() == getKnownState()
                && (internalState == IDLE || internalState == STATUSREQUESTSENT)) {
            // This is a feedback message, we need to check and see if it
            // indicates this turnout is to change state or if it is for
            // another turnout.
            if (reply.onTurnoutFeedback(mNumber, this::parseFeedbackMessage)) {
                log.debug("Turnout {} EXACT feedback mode - state change from feedback.", mNumber);
            }
        } else if (getCommandedState() != getKnownState()
                || internalState == COMMANDSENT
                || internalState == STATUSREQUESTSENT) {
            if (reply.isOkMessage()) {
                // Finally, we may just receive an OK message.
                log.debug("Turnout {} EXACT feedback mode - OK message triggering OFF message.", mNumber);
                sendOffMessage();
            } else {
                // implicitly checks for isFeedbackBroadcastMessage()
                reply.selectTurnoutFeedback(mNumber).ifPresent(l -> {
                    int messageType = l.getType();
                    switch (messageType) {
                        case 1: {
                            // The first case is that we receive a message for
                            // this turnout and this turnout provides feedback.
                            // In this case, we want to check to see if the
                            // turnout has completed its movement before doing
                            // anything else.
                            if (!l.isMotionComplete()) {
                                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion not complete", mNumber);
                                // If the motion is NOT complete, send a feedback
                                // request for this nibble
                                XNetMessage msg = XNetMessage.getFeedbackRequestMsg(
                                        mNumber, ((mNumber % 4) <= 1));
                                queueMessage(msg,STATUSREQUESTSENT ,null); //status is returned via the manager.
                                return;
                            } else {
                                log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion complete", mNumber);
                            }
                            break;
                        }
                        case 0: 
                            log.debug("Turnout {} EXACT feedback mode - state change from feedback, CommandedState!=KnownState - motion complete", mNumber);
                            // The second case is that we receive a message about
                            // this turnout, and this turnout does not provide
                            // feedback. In this case, we want to check the
                            // contents of the message and act accordingly.
                            break;
                        default: return;
                    }
                    parseFeedbackMessage(l);
                    // We need to tell the turnout to shut off the output.
                    sendOffMessage();
                });
            }
        }
    }
    
    /**
     * Send an "Off" message to the decoder for this output. 
     */
    protected synchronized void sendOffMessage() {
        // We need to tell the turnout to shut off the output.
        if (log.isDebugEnabled()) {
            log.debug("Sending off message for turnout {} commanded state={}", mNumber, getCommandedState());
            log.debug("Current Thread ID: {} Thread Name {}", java.lang.Thread.currentThread().getId(), java.lang.Thread.currentThread().getName());
        }
        XNetMessage msg = getOffMessage();
        // Set the known state to the commanded state.
        synchronized (this) {
        // To avoid some of the command station busy
        // messages, add a short delay before sending the
        // first off message.
            if (internalState != OFFSENT) {
            jmri.util.ThreadingUtil.runOnLayoutDelayed( () ->
               tc.sendHighPriorityXNetMessage(msg, this), 30);
                newKnownState(getCommandedState());
                internalState = OFFSENT;
                return;
        }
        newKnownState(getCommandedState());
                internalState = OFFSENT;
        }
        // Then send the message.
        tc.sendHighPriorityXNetMessage(msg, this);
    }

    protected synchronized XNetMessage getOffMessage(){
        return ( XNetMessage.getTurnoutCommandMsg(mNumber,
                getCommandedState() == _mClosed,
                getCommandedState() == _mThrown,
                false) );
    }

    /**
     * Parse the feedback message, and set the status of the turnout
     * accordingly.
     *
     * @param l  turnout feedback item
     * 
     * @return 0 if address matches our turnout -1 otherwise
     */
    private synchronized boolean parseFeedbackMessage(FeedbackItem l) {
        log.debug("Message for turnout {}", mNumber);
        if (internalState != IDLE && l.isUnsolicited()) {
            l.resetUnsolicited();
        }
        switch (l.getTurnoutStatus()) {
            case THROWN:
                newKnownState(_mThrown);
                return true;
            case CLOSED:
                newKnownState(_mClosed);
                return true;
            default:
                // the state is unknown or inconsistent.  If the command state
                // does not equal the known state, and the command repeat the
                // last command
                if (getCommandedState() != getKnownState()) {
                    forwardCommandChangeToLayout(getCommandedState());
                } else {
                    sendQueuedMessage();
                }
                return false;
        }
    }
    
    @Override
    public void dispose() {
        this.removePropertyChangeListener(_stateListener);
        super.dispose();
    }

    /**
     * Internal class to use for listening to state changes.
     */
    private static class XNetTurnoutStateListener implements java.beans.PropertyChangeListener {

        XNetPlusTurnout _turnout = null;

        XNetTurnoutStateListener(XNetPlusTurnout turnout) {
            _turnout = turnout;
        }

        /**
         * If we're  not using DIRECT feedback mode, we need to listen for
         * state changes to know when to send an OFF message after we set the
         * known state.
         * If we're using DIRECT mode, all of this is handled from the
         * XpressNet Messages.
         */
        @Override
        public void propertyChange(java.beans.PropertyChangeEvent event) {
            log.debug("propertyChange called");
            // If we're using DIRECT feedback mode, we don't care what we see here
            if (_turnout.getFeedbackMode() != DIRECT) {
                if (log.isDebugEnabled()) {
                    log.debug("propertyChange Not Direct Mode property: {} old value {} new value {}", event.getPropertyName(), event.getOldValue(), event.getNewValue());
                }
                if (event.getPropertyName().equals("KnownState")) {
                    // Check to see if this is a change in the status
                    // triggered by a device on the layout, or a change in
                    // status we triggered.
                    int oldKnownState = ((Integer) event.getOldValue()).intValue();
                    int curKnownState = ((Integer) event.getNewValue()).intValue();
                    log.debug("propertyChange KnownState - old value {} new value {}", oldKnownState, curKnownState);
                    if (curKnownState != INCONSISTENT
                            && _turnout.getCommandedState() == oldKnownState) {
                        // This was triggered by feedback on the layout, change
                        // the commanded state to reflect the new Known State
                        if (log.isDebugEnabled()) {
                            log.debug("propertyChange CommandedState: {}", _turnout.getCommandedState());
                        }
                        _turnout.newCommandedState(curKnownState);
                    } else {
                        // Since we always set the KnownState to
                        // INCONSISTENT when we send a command, If the old
                        // known state is INCONSISTENT, we just want to send
                        // an off message
                        if (oldKnownState == INCONSISTENT) {
                            if (log.isDebugEnabled()) {
                                log.debug("propertyChange CommandedState: {}", _turnout.getCommandedState());
                            }
                            _turnout.sendOffMessage();
                        }
                    }
                }
            }
        }

    }

    // data members
    protected int mNumber;   // XpressNet turnout number
    XNetTurnoutStateListener _stateListener;  // Internal class object

    // A queue to hold outstanding messages
    @GuardedBy("this")
    protected final Deque<RequestMessage> requestList;

    /**
     * Send message from queue.
     */
    protected synchronized void sendQueuedMessage() {

        RequestMessage msg = null;
        // check to see if the queue has a message in it, and if it does,
        // remove the first message
        msg = requestList.poll();
        // if the queue is not empty, remove the first message
        // from the queue, send the message, and set the state machine
        // to the requried state.
        if (msg != null) {
            log.debug("sending message to traffic controller");
            internalState = msg.getState();
            tc.sendXNetMessage(msg.getMsg(), msg.getListener());
        } else {
            log.debug("message queue empty");
            // if the queue is empty, set the state to idle.
            internalState = IDLE;
        }
    }
    
    /**
     * Queue a message.
     */
    protected synchronized void queueMessage(XNetMessage m, int s, XNetListener l) {
        log.debug("adding message {} to message queue.  Current Internal State {}",m,internalState);
        // put the message in the queue
        RequestMessage msg = new RequestMessage(m, s, l);
        requestList.offer(msg);
        // if the state is idle, trigger the message send
        if (internalState == IDLE ) {
            sendQueuedMessage();
        }
    }

    /**
     * Internal class to hold a request message, along with the associated throttle state.
     */
    protected static class RequestMessage {

        private int state;
        private XNetMessage msg;
        private XNetListener listener;

        RequestMessage(XNetMessage m, int s, XNetListener listener) {
            state = s;
            msg = m;
            this.listener = listener;
        }

        int getState() {
            return state;
        }

        XNetMessage getMsg() {
            return msg;
        }

        XNetListener getListener() {
            return listener;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(XNetPlusTurnout.class);

}
