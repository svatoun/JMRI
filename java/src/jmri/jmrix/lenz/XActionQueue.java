/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.lenz.XAction.StateUpdater;
import jmri.util.ThreadingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains the command queue on XPressNet and handles possible
 * confirmations.
 * <p>
 * The object tracks accessory state, similar to XNetFeedbackMessageCache; but the
 * state is changed <b>based on commands</b> immediately when a non-error reply
 * is detected as command response. The tracked state is the <b>expected one</b>
 * so that feedback infos can be analyzed if they are mere confirmations, or 
 * unsolicited, out-of-band layout change messages.
 * <p>
 * 
 * @author sdedic
 */
public class XActionQueue implements XNetListener {
    private static final Logger LOG = LoggerFactory.getLogger(XActionQueue.class);
    
    /**
     * The traffic controller. Used to enque messages.
     */
    private final XNetTrafficController controller;
    
    /**
     * Service used to schedule delayed messages.
     */
    private final ScheduledExecutorService  schedulerService = Executors.newSingleThreadScheduledExecutor();
    
    /**
     * Turnout manager. Cannot be final, set from random thread, 
     * accessed from layout thread. Must not be changed during operation.
     */
    private /* final */ XNetTurnoutManager    turnoutManager;

    /**
     * Transitional. If not enabled, will ignore all processing, effectively
     * degrading the implementation to the JMRI 4.18 state. Compatibility mode.
     */
    private boolean enabled;
    
    /**
     * Created messages. They have already entered the transmit queue.
     * This field can be altered from the transmit thread.
     */
    @GuardedBy("this")
    private final List<XNetMessage>   queuedMessages = new ArrayList<>();
    
    /**
     * Generated messages, which are scheduled to some later time. Used to
     * potentially cancel the scheduled message.
     * <p>
     * This field can be altered from the transmit thread.
     */
    @GuardedBy("this")
    private final Map<XNetMessage, Future>   delayedMessages = new HashMap<>();
    
    /**
     * Messages that are being sent or were sent.
     * This field can be altered from the transmit thread.
     */
    @GuardedBy("this")
    private final List<XNetMessage>   transmittedMessages = new ArrayList<>();
    
    
    /**
     * The default timeout after which a CONFIRMED message
     * becomes FINISHED.
     */
    private long stateTimeout = 100;
    
    /**
     * Expected state of accessories. The state changes immediately when an 
     * accessory command <b>is sent</b>.
     * <p>
     * Can be only accessed from the Layout thread.
     */
    private final Map<Integer, Integer> accessoryState = new HashMap<>();

    /**
     * The command being currently serviced.
     * Can be accessed only from the Layout thread.
     */
    private XAction    currentCommand;
    
    /**
     * The action in effect while processing a XNetReply
     * through all the listeners. Used to join messages
     * in the output queue.
     */
    private XAction    processedReplyAction;
    
    /**
     * Code fragment, which will be run after non-error command.
     * Can be accessed only from the Layout thread.
     */
    private StateUpdater   updateAccessoryState;

    /**
     * The last message that was sent / is being sent by the transmit thread.
     * Can be only accessed from the Layout thread.
     */
    private XNetMessage lastSent;
    
    
    
    public XActionQueue(XNetTrafficController controller) {
        this.controller = controller;
    }
    
    public synchronized void setTurnoutManager(XNetTurnoutManager mgr) {
        assert turnoutManager == null || turnoutManager == mgr;
        this.turnoutManager = mgr;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Sends the message to the XPressnet. The actual effect will depend on whether the
     * message is {@link XNetMessage#isPriorityMessage} or {@link XNetMessage#isDelayed()}.
     * Delayed messages are just recorded and <b>scheduled</b> to a later time.
     * <p>
     * This method is intended to be called from the Traffic Controller, can be called
     * from an arbitrary thread.
     * @param msg the message to sent.
     */
    boolean send(XNetMessage msg, XNetListener callback) {
        synchronized (this) {
            if (transmittedMessages.remove(msg)) {
                // support for retransmissions.
                if (lastSent == msg) {
                    lastSent = null;
                }
                if (currentCommand == msg.getAction()) {
                    currentCommand = null;
                }
            }
            
            // creates / attaches an action, if necessary.
            XAction cmd = forMessage(msg);
            if (cmd != null && cmd == processedReplyAction) {
                // XX 
                processedReplyAction.addMessage(msg);
                return false;
            }
            
            if (msg.isDelayed()) {
                synchronized (this) {
                    Future existing = delayedMessages.remove(msg);
                    if (existing != null) {
                        throw new IllegalStateException("Cannot reschedule message.");
                    }
                    int d = msg.getDelay();
                    if (d < 0) {
                        d = -d;
                    }
                    LOG.debug("Scheduling {} after {}ms", msg, d);
                    Future<?> future = schedulerService.schedule(
                        () -> postMessage(msg, callback), d, TimeUnit.MILLISECONDS);
                    delayedMessages.put(msg, future);
                }
                return false;
            }
        }
        controller.doSendMessage(msg, callback);
        return true;
    }
    
    void postMessage(XNetMessage msg, XNetListener callback) {
        synchronized (this) {
            Future existing = delayedMessages.remove(msg);
            if (existing == null) {
                // cancelled.
                LOG.debug("Message {} has been cancelled, dropping.", msg);
                return;
            }
        }
        LOG.debug("Adding scheduled message {}", msg);
        controller.doSendMessage(msg, callback);
    }
    
    public synchronized void clear() {
        this.lastSent = null;
        this.currentCommand = null;
    }
    
    /**
     * Prepares the message for transmission. This method must be called
     * from the transmission thread. It is critical that the message enters
     * transmitted queue atomically with posting the task that calls out
     * to listeners into Layout thread.
     * @param msg 
     */
    public void preSendMessage(XNetMessage msg) {
        assert ThreadingUtil.isLayoutThread();
        synchronized (this) {
            queuedMessages.remove(msg);
            lastSent = msg;
            // no command may be available yet, don't know if necessary to track the message
            transmittedMessages.add(msg);
        }
        msg.toPhase(XNetMessage.Phase.SENT);
    }

    /**
     * Binds outgoing message to a command. This method is called 
     * as the first listener of XNetTrafficController.
     * @param msg 
     */
    @Override
    public void message(XNetMessage msg) {
        assert ThreadingUtil.isLayoutThread();
        
        XAction.StateUpdater state = null;
        XAction cmd = null;
        if (isEnabled()) {
            cmd = forMessage(msg);
            if (cmd != null) {
                 state = cmd.prepare(this, msg);
            }
        }
        if (cmd == null || state == null) {
            // the message is uninteresting; 
            synchronized (this) {
                transmittedMessages.remove(msg);
            }
            this.currentCommand = null;
            return;
        }
        this.currentCommand = cmd;
        this.updateAccessoryState = state;
    }
    
    /**
     * The method must be called to allow the message to be retransmitted.
     * The necessary queue bookkeeping will be performed.
     * @param msg 
     */
    void retransmit(XNetMessage msg) {
        assert lastSent == msg;
        synchronized (this) {
            transmittedMessages.remove(msg);
            queuedMessages.add(msg);
        }
        msg.toPhase(XNetMessage.Phase.QUEUED);
    }

    @Override
    public void notifyTimeout(XNetMessage msg) {
        assert ThreadingUtil.isLayoutThread();
        msg.toPhase(XNetMessage.Phase.EXPIRED);
        synchronized (this) {
            lastSent = null;
            queuedMessages.remove(msg);
            transmittedMessages.remove(msg);
        }
    }
    
    public int getAccessoryState(int number) {
        assert ThreadingUtil.isLayoutThread();
        return accessoryState.getOrDefault(number, XNetTurnout.UNKNOWN);
    }

    // should be only called from command processing.
    public void setAccessoryState(int number, int state) {
        assert ThreadingUtil.isLayoutThread();
        accessoryState.put(number, state);
    }

    /**
     * Fetches or creates a XCommand associated with the message. Commands
     * with no special handling will have get no XCommand associated.
     * @param msg the XPressnet message
     * @return the command or {@code null}.
     */
    public final XAction forMessage(XNetMessage msg) {
        if (!isEnabled()) {
            return null;
        }
        XAction cmd;
        synchronized (msg) {
            cmd = msg.getAction();
            if (cmd == null) {
                if (processedReplyAction != null && processedReplyAction.acceptsMessage(this, msg)) {
                    cmd = processedReplyAction;
                } else {
                    cmd = createCommand(msg);
                }
                msg.attachAction(cmd);
            }
        }
        return cmd;
    }
    
    /**
     * Factory to create command handlers.
     * @param msg
     * @return 
     */
    protected XAction createCommand(XNetMessage msg) {
        switch (msg.getElement(0)) {
            case XNetConstants.ACC_OPER_REQ: {
                return new XAction.Accessory(msg, null);
            }
            case XNetConstants.ACC_INFO_REQ:
                return new XAction.AccessoryInfo(msg, null);
                
            default:
                return null;
        }
    }
    
    /**
     * Terminates / discards transmitted messages that were
     * long in the watched queue. Ensures eventual cleanup of
     * stale messages.
     */
    private void expireTransmittedMessages() {
        List<XNetMessage> toExpire = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        synchronized (this) {
            for (XNetMessage msg : transmittedMessages) {
                if (msg.getTimeSent() + stateTimeout >= currentTime) {
                    toExpire.add(msg);
                }
            }
            transmittedMessages.removeAll(toExpire);
        }
        for (XNetMessage m : toExpire) {
            synchronized (m) {
                if (m.getPhase() == XNetMessage.Phase.CONFIRMED) {
                    m.toPhase(XNetMessage.Phase.FINISHED);
                } else {
                    m.toPhase(XNetMessage.Phase.EXPIRED);
                }
            }
            XAction command = m.getAction();
            if (command == null) {
                continue;
            }
            command.finished(this, m);
            if (command.proceedNext(this)) {
                terminate(command, m.getPhase() == XNetMessage.Phase.FINISHED);
            }
        }
    }
    
    /**
     * Process a message reply.
     * @param msg 
     */
    @Override
    public void message(XNetReply msg) {
        assert ThreadingUtil.isLayoutThread();
        expireTransmittedMessages();
        if (!isEnabled()) {
            return;
        }
        
        if (msg.isOkMessage()) {
            // OK message is always paired to the command that has been just sent.
            confirmCommand(msg);
            return;
        } 
        processSingleFeedback(msg);
        processMultipleFeedbacks(msg);
    }
    
    public void postReply(XNetReply msg) {
        processedReplyAction = null;
        XNetMessage out = msg.getResponseTo();
        if (out == null) {
            return;
        }
        XAction action = out.getAction();
        if (action == null) {
            return;
        }
        if (action.proceedNext(this)) {
            terminate(action, action.getCurrentMessage().getPhase() == XNetMessage.Phase.FINISHED);
        }
    }
    
    public void terminate(XAction ac, boolean success) {
        List<XNetMessage> messages = new ArrayList<>(ac.getAllMessages());
        synchronized (this) {
            transmittedMessages.removeAll(messages);
            queuedMessages.removeAll(messages);
            messages.stream().filter(delayedMessages::containsKey).forEach(m -> {
                delayedMessages.remove(m).cancel(true);
            });
        }
        messages.stream().forEach((m) -> {
            if (m.getPhase().ordinal() < XNetMessage.Phase.FINISHED.ordinal()) {
                
            }
        });
    }
    
    public XNetTurnout getTurnout(int number) {
        synchronized (this) {
            if (turnoutManager == null) {
                return null;
            }
        }
        XNetTurnout tnt;
        tnt = (XNetTurnout)turnoutManager.getBySystemName(turnoutManager.getSystemNamePrefix() + number);
        return tnt;
    }
    
    final class BatchFeedbackProcessor {
        private final XNetReply feedback;
        
        private List<XNetListener>  reportList = new LinkedList<>();
        
        public BatchFeedbackProcessor(XNetReply feedback) {
            this.feedback = feedback;
        }
        
        void process() {
            for (int i = 1; i < feedback.getNumDataElements(); i += 2) {
                SimpleFeedbackProcessor proc = new SimpleFeedbackProcessor(feedback, i);
                if (!proc.findFeedbacksToIgnore()) {
                    XNetTurnout tnt;
                    if (proc.shouldReportFeecback(true)) {
                        tnt = getTurnout(proc.getOddAddress());
                        if (tnt != null) {
                            reportList.add(tnt);
                        }
                    }
                    if (proc.shouldReportFeecback(false)) {
                        tnt = tnt = getTurnout(proc.getOddAddress() + 1);
                        if (tnt != null) {
                            reportList.add(tnt);
                        }
                    }
                }
            }
        }
        
        public List<XNetListener> getReportedList() {
            return reportList;
        }
    }
    
    /**
     * Helper class that builds up a state that should not
     * mess the Queue object between messages.
     */
    final class SimpleFeedbackProcessor {
        /**
         * The feedback reply
         */
        private final XNetReply feedback;
        
        /**
         * Odd (first) turnout address in the feedback info.
         */
        private final int oddAddr;
        
        /**
         * Reported state of the odd (first) turnout.
         */
        private final int oddState;
        
        /**
         * Reported state of the even (second) turnout.
         */
        private final int evenState;
        
        /**
         * True, if the odd state should be ignored.
         */
        private boolean oddIgnored;

        /**
         * True, if the even state should be ignored.
         */
        private boolean evenIgnored;
        
        /**
         * Should report and process odd state.
         */
        private boolean reportOdd;

        /**
         * Should report and process even state.
         */
        private boolean reportEven;
        
        public SimpleFeedbackProcessor(XNetReply feedback) {
            this(feedback, 1);
        }

        public SimpleFeedbackProcessor(XNetReply feedback, int start) {
            this.feedback = feedback;

            oddAddr = feedback.getTurnoutMsgAddr(start);
            oddState = feedback.getTurnoutStatus(start, 1);
            evenState = feedback.getTurnoutStatus(start, 0);
        }
        
        /**
         * Finds a transmitted command, that can be confirmed
         * by this feedback.
         * @return accessory command
         */
        XAction.Accessory findTransmittedToConfirm(boolean first) {
            if (!feedback.isFeedbackMessage()) {
                return null;
            }
            List<XNetMessage> tr;
            synchronized (this) {
                tr = new ArrayList<>(transmittedMessages);
            }
            for (XNetMessage m : tr) {
                XAction cmd = m.getAction();
                if (!(cmd instanceof XAction.Accessory)) {
                    continue;
                }
                XAction.Accessory ac = (XAction.Accessory)cmd;
                if (first && !acceptsFeedback(cmd, feedback)) {
                    continue;
                }
                if (ac.getLayoutId() == oddAddr) {
                    if (ac.getCommandedState() == oddState &&
                        acceptsFeedback(ac, feedback)) {
                        if (ac.getPairedKnownState() == evenState) {
                            // the reported state is the same as it was at the time command was sent
                            // should not be reported anywhere as this feedback is just confirmation.
                            evenIgnored = true;
                        }
                        feedback.setResponseTo(ac.getCurrentMessage());
                        oddIgnored = true;
                        return ac;
                    }
                }
                if (ac.getLayoutId() == oddAddr + 1) {
                    if (ac.getCommandedState() == evenState && 
                        acceptsFeedback(ac, feedback)) {
                        if (ac.getPairedKnownState() == oddState) {
                            oddIgnored = true;
                        }
                        feedback.setResponseTo(ac.getCurrentMessage());
                        evenIgnored = true;
                        return ac;
                    }
                }
            }
            return null;
        }
        
        /**
         * Determines if parts can be ignored.
         * @return true, if whole feedback item can be ignored.
         */
        boolean findFeedbacksToIgnore() {
            for (XNetMessage m : queuedMessages) {
                XAction cmd = m.getAction();
                if (!(cmd instanceof XAction.Accessory)) {
                    continue;
                }
                XAction.Accessory ac = (XAction.Accessory)cmd;
                if (ac.acceptsLayoutId(oddAddr)) {
                    // the reported state is the future commanded one.
                    // do not consider this an ACK, but do process the feedback.
                    // the other feedback must be the currently known state of the paired turnout
                    int os = getAccessoryState(oddAddr + 1);
                    if ((os == XNetTurnout.UNKNOWN) || os == evenState) {
                        if (ac.getCommandedState() == oddState) {
                            reportEven = true;
                        } else {
                            evenIgnored = true;
                        }
                        break;
                    }
                } 
                if (ac.acceptsLayoutId(oddAddr + 1)) {
                    int os = getAccessoryState(oddAddr);
                    if ((os == XNetTurnout.UNKNOWN) || os == evenState) {
                        if  (ac.getCommandedState() == evenState) {
                            reportOdd = true;
                        } else {
                            oddIgnored = true;
                        }
                        break;
                    }
                }
            }
            return oddIgnored && evenIgnored;
        }
        
        int getOddAddress() {
            return oddAddr;
        }
        
        int getEvenAddress() {
            return oddAddr + 1;
        }
        
        boolean shouldReportFeecback(boolean odd) {
            if (odd) {
                return reportOdd && !oddIgnored;
            } else {
                return reportEven && !evenIgnored;
            }
        }
    }
    
    private void processMultipleFeedbacks(XNetReply feedback) {
        if (!feedback.isFeedbackBroadcastMessage() || feedback.isFeedbackMessage()) {
            return;
        }
        BatchFeedbackProcessor proc = new BatchFeedbackProcessor(feedback);
        proc.process();
        
        if (proc.getReportedList().isEmpty()) {
            return;
        }
        for (XNetListener l : proc.getReportedList()) {
            l.setMessageState(XNetTurnout.IDLE);
            l.message(feedback);
        }
        feedback.markConsumed();
    }
    
    /**
     * Finds a command suitable for accepting the feedback.
     * <ol>
     * <li>attempts to find a <b>transmitted</b> command whose expected values match the feedback.
     * If found, that command is confirmed.
     * <li>attempts to find a <b>queued</b> command for the reported turnouts. 
     * <li>If the KnownState corresponds to the delivered feedback state, ignore. The turnout would
     * flip INCONSISTENT > definite that would confuse clients.
     * <li>If the CommandedState corresponds to the delivered feedback, process as unsolicited
     * feedback.
     * <li>if no command is queued, process the feedback as an unsolicited one.
     */
    private void processSingleFeedback(XNetReply feedback) {
        if (!feedback.isFeedbackMessage()) {
            return;
        }
        SimpleFeedbackProcessor proc = new SimpleFeedbackProcessor(feedback);
        
        XAction accessory = proc.findTransmittedToConfirm(true);
        boolean query = false;
        if (accessory == null) {
            // second stage: if there was an inquiry posted, pair
            // its command with the 
            if (lastSent != null && 
                lastSent.getOpCode() == XNetConstants.ACC_INFO_REQ &&
                currentCommand != null &&
                currentCommand.acceptsLayoutId(feedback.getTurnoutMsgAddr())) {
                accessory = currentCommand;
            }
        }
        if (!query && accessory == null) {
            accessory = proc.findTransmittedToConfirm(false);
        }
        if (accessory != null) {
            XNetMessage msg = accessory.getCommandMessage();
            feedback.setResponseTo(msg);
            msg.addStateMessage();
            msg.toPhase(XNetMessage.Phase.CONFIRMED);
            accessory.processed(this, feedback);
            feedback.markConsumed();
        }
        
        if (proc.findFeedbacksToIgnore()) {
            feedback.markConsumed();
            return;
        }
        fireTurnoutMessage(feedback, proc.shouldReportFeecback(true), proc.getOddAddress());
        fireTurnoutMessage(feedback, proc.shouldReportFeecback(false), proc.getOddAddress());
        feedback.markConsumed();
    }
    
    private void fireTurnoutMessage(XNetReply feedback, boolean fire, int address) {
        if (!fire) {
            return;
        }
        XNetTurnout tnt = getTurnout(address);
        if (tnt == null) {
            return;
        }
        tnt.setMessageState(XNetTurnout.IDLE);
        tnt.message(feedback);
    }
    
    /**
     * Determines if the command accepts further state messages. The default
     * implementation requires that both OK and feedback arrives to as a response
     * to the message.
     * 
     * @param ac
     * @param feedback
     * @return 
     */
    protected boolean acceptsFeedback(XAction ac, XNetReply feedback) {
        return ac.getCommandMessage().getStateReceived() == 0 ||
               ac.getCommandMessage().getOkReceived() == 0;
    }
    
    XNetMessage confirmWith(XNetMessage msg, XNetReply reply) {
        reply.setResponseTo(msg);
        msg.toPhase(XNetMessage.Phase.CONFIRMED);
        return msg;
    }
    
    private void confirmCommand(XNetReply reply) {
        if (!reply.isOkMessage() || currentCommand == null) {
            return;
        }
        XNetMessage last = lastSent;
        if (last == null) {
            // FIXME: log
            return;
        }
        confirmWith(last, reply).addOkMessage();
        currentCommand.processed(this, reply);
    }
    
    public abstract class ReplyState {
        final XNetMessage   lastSent;
        final XNetReply     reply;
        final List<XNetMessage> transmitted;
        final List<XNetMessage> queued;
        
        protected ReplyState(XNetReply reply) {
            synchronized (XActionQueue.class) {
                this.lastSent = XActionQueue.this.lastSent;
                this.reply = reply;
                this.transmitted = new ArrayList<>(transmittedMessages);
                this.queued = new ArrayList<>(queuedMessages);
            }
        }

        public XNetMessage getLastSent() {
            return lastSent;
        }

        public XNetReply getReply() {
            return reply;
        }

        public List<XNetMessage> getTransmitted() {
            return transmitted;
        }
        
        public List<XNetMessage> getQueued() {
            return transmitted;
        }
        
        protected final XActionQueue getQueue() {
            return XActionQueue.this;
        }
        
        protected abstract XNetMessage findConfirmedMessage();
        
        protected abstract void execute();
    }
    
    public interface ReplyProcessorFactory {
        public ReplyState create(XActionQueue queue, XNetReply message);
    }
    
    public abstract static class FeedbackState extends ReplyState {
        private int ignoreBits;
        
        public FeedbackState(XActionQueue q, XNetReply reply) {
            q.super(reply);
        }
        
        public int getIgnoreBits() {
            return ignoreBits;
        }
        
        public void ignoreFeedback(int position) {
            ignoreBits |= XNetReply.CONSUMED_STATE_1 << (position - 1);
        }
        
        protected void messageToTurnout(int mask, int tnt) {
            if ((ignoreBits & mask) > 0) {
                return;
            }
            XNetTurnout t = getQueue().getTurnout(tnt);
            if (t != null) {
                t.setMessageState(XNetTurnout.IDLE);
                t.message(getReply());
            }
        }
        
        protected void execute() {
            int cnt = reply.getFeedbackMessageItems();
            int mask = XNetReply.CONSUMED_STATE_1;
            for (int i = 1; i <= cnt; i++) {
                int oddAddr = reply.getFeedbackEncoderMsgAddr(i);
                messageToTurnout(oddAddr, mask);
                mask <<= 1;
                messageToTurnout(oddAddr + 1, mask);
            }
        }
    }
}
