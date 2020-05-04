/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTurnoutManager;
import jmri.jmrix.lenzplus.CommandState.Phase;
import jmri.util.ThreadingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ActionQueue controls the flow of actions to/from the command station.
 * It watches for replies from the command station, pairs them to the outgoing
 * messages and their Actions. It confirms, to the {@link XNetPlusTrafficController}
 * that the command (Action) is completed when appropriate replies are received.
 * 
 * @author sdedic
 */
public class QueueController {
    private static final Logger LOG = LoggerFactory.getLogger(QueueController.class);
    
    /**
     * The traffic controller. Used to enque messages.
     */
    private final XNetPlusTrafficController controller;
    
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
     * External mapping from messages to their states. Used to attach bookkeeping
     * info when a message or a notification enters the queue system. Used from 
     * transmit, receive and layout threads.
     */
    @GuardedBy("this")
    private final Map<XNetPlusMessage, CommandState>  commands = new IdentityHashMap<>();
    
    /**
     * Created messages. They have already entered the transmit queue.
     * This field can be altered from the transmit thread.
     */
    @GuardedBy("this")
    private final List<CommandState>   queuedMessages = new ArrayList<>();
    
    /**
     * Generated messages, which are scheduled to some later time. Used to
     * potentially cancel the scheduled message.
     * <p>
     * This field can be altered from the transmit thread AND layout thread.
     */
    @GuardedBy("this")
    private final Map<CommandState, Future>   delayedMessages = new LinkedHashMap<>();
    
    /**
     * Messages that are being sent or were sent.
     * This field can be altered from the transmit thread.
     */
    @GuardedBy("this")
    private final List<CommandState>   transmittedMessages = new ArrayList<>();
    
    
    /**
     * The default timeout after which a CONFIRMED message
     * becomes FINISHED. In milliseconds.
     */
    private long stateTimeout = 1500;
    
    /**
     * Expected state of accessories. The state changes immediately when an 
     * accessory command <b>is sent</b>.
     * <p>
     * Can be only accessed from the Layout thread.
     */
    private final Map<Integer, Integer> accessoryState = new HashMap<>();

    /**
     * The action in effect while processing a XNetReply
     * through all the listeners. Used to join messages
     * in the output queue.
     */
    private CommandHandler    processedReplyAction;
    
    /**
     * Code fragment, which will be run after non-error command.
     * Can be accessed only from the Layout thread.
     */
    private StateUpdater   updateAccessoryState;

    /**
     * The last message that was sent / is being sent by the transmit thread.
     * Can be only accessed from the Layout thread.
     */
    private CommandState lastSent;
    
    public static interface StateUpdater extends Consumer<XNetPlusReply>{}
    
    public QueueController(XNetPlusTrafficController controller) {
        this.controller = controller;
    }
    
    public synchronized void setTurnoutManager(XNetTurnoutManager mgr) {
        assert turnoutManager == null || turnoutManager == mgr;
        this.turnoutManager = mgr;
    }
    
    @GuardedBy("this")
    /* test-private */ CommandState state(XNetPlusMessage msg) {
        return commands.computeIfAbsent(msg, CommandState::new);
    }
    
    /* test-private */ List<CommandState> getTransmittedMessages() {
        synchronized (this) {
            return new ArrayList<>(transmittedMessages);
        }
    }

    /* test-private */ List<CommandState> getQueuedMessages() {
        synchronized (this) {
            return new ArrayList<>(queuedMessages);
        }
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
    boolean send(XNetPlusMessage msg, XNetListener callback) {
        synchronized (this) {
            CommandState state = state(msg);
            LOG.debug("Request to sent {}, callback: {}", state, callback);
            if (state.getPhase().ordinal() > Phase.CREATED.ordinal()) {
                System.err.println("");
            }
            if (transmittedMessages.remove(state)) {
                // support for retransmissions.
                if (lastSent == state) {
                    lastSent = null;
                }
            }
            
            CommandHandler cmd = state.getHandler();
            if (cmd == null) {
                // creates / attaches an action, if necessary.
                cmd = forMessage(state, callback);
                LOG.debug("Command handler {} created for {}", cmd, state);
                state.attachHandler(cmd);
            }
            if (cmd != null && cmd == processedReplyAction) {
                // XX 
                processedReplyAction.addMessage(state);
                return false;
            }
            
            if (msg.isDelayed()) {
                synchronized (this) {
                    Future existing = delayedMessages.remove(state);
                    if (existing != null) {
                        throw new IllegalStateException("Cannot reschedule message.");
                    }
                    int d = msg.getDelay();
                    if (d < 0) {
                        d = -d;
                    }
                    LOG.debug("Scheduling {} after {}ms", msg, d);
                    Future<?> future = schedulerService.schedule(
                        () -> postMessage(state, callback), d, TimeUnit.MILLISECONDS);
                    delayedMessages.put(state, future);
                }
                return false;
            }
            postMessage(state, callback);
        }
        return true;
    }
    
    synchronized void postMessage(CommandState state, XNetListener callback) {
        LOG.debug("Entered queue: {}", state);
        queuedMessages.add(state);
        state.toPhase(Phase.QUEUED);
        controller.doSendMessage(state.getMessage(), callback);
    }
    
    // TODO: refactor to a separate CommandHandlerFactory
    protected CommandHandler forMessage(CommandState cmd, XNetListener callback) {
        XNetMessage m = cmd.getMessage();
        
        switch (m.getElement(0)) {
            case XNetConstants.ACC_OPER_REQ:
                return new AccessoryHandler(cmd, callback);
        }
        
        return new SimpleHandler(cmd, callback);
    }
    
    /**
     * Binds outgoing message to a command. This method is called 
     * as the first listener of XNetTrafficController.
     * @param msg 
     */
    public void message(XNetPlusMessage msg) {
        assert ThreadingUtil.isLayoutThread();

        CommandState s = state(msg);
        LOG.debug("Being sent: {}", s);
        s.toPhase(Phase.SENT);
        transmittedMessages.add(s);
        CommandHandler h = s.getHandler();
        this.lastSent = s;
        debugPrintState();
    }
    
    private synchronized void debugPrintState() {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        LOG.debug("Last sent: {}, current command: {}", lastSent, lastSent.getHandler());
        StringBuilder sb = new StringBuilder();
        transmittedMessages.forEach((s) -> {
            sb.append("\n\t").append(s);
        });
        sb.append("\n\t\t");
        LOG.debug("Trasnmitted queue: {}", sb);
    }
    
    /**
     * Process a message reply.
     * @param msg 
     */
    public void message(XNetPlusReply msg) {
        LOG.debug("Received reply: {}", msg);
        assert ThreadingUtil.isLayoutThread();
        expireTransmittedMessages();
        
        if (msg.isRetransmittableErrorMsg()) {
            rejected();
            return;
        }
        
        // the message was most probably confirmed, or some unsolicited message
        // has arrived.
        lastSent.getHandler().sent(lastSent);
        
        if (msg.isOkMessage()) {
            // OK message is always paired to the command that has been just sent.
            confirmCommand(msg);
            return;
        } else if (msg.isFeedbackMessage()) {         
            // Special handling for feedbacks.
        }
        
        for (CommandState s : transmittedMessages) {
            CommandHandler h = s.getHandler();
            if (h.handleMessage(msg)) {
                break;
            }
        }
        if (msg.getResponseTo() != null) {
            
        } else {
            LOG.debug("Unclaimed response, assuming to confirm the last command: {}", lastSent);
            // assume it's a reply to the last command:
            confirmCommand(msg);
        }
    }
    
    private synchronized void rejected() {
        if (lastSent == null) {
            return;
        }
        transmittedMessages.remove(lastSent);
        lastSent.toPhase(Phase.QUEUED);
    }
    
    private void confirmCommand(XNetPlusReply reply) {
        CommandHandler cc;
        CommandState last;
        synchronized (this) {
            last = lastSent;
            if (last == null) {
                return;
            }
            cc = last.getHandler();
        }
        reply.setResponseTo(last.getMessage());
        reply.resetUnsolicited();
        boolean changed = last.toPhase(Phase.CONFIRMED);
        if (reply.isOkMessage()) {
            last.addOkMessage();
        } else if (reply.isFeedbackBroadcastMessage()) {
            last.addStateMessage();
        }
        LOG.debug("Confirmed: {}", last);
        if (changed) {
            LOG.debug("Calling processed on {}", last);
            cc.processed(last, reply);
        }
    }
    
    
    public void postReply(XNetPlusReply msg) {
        LOG.debug("PostReply for {}", msg);
        assert ThreadingUtil.isLayoutThread();
        processedReplyAction = null;
        XNetPlusMessage out = msg.getResponseTo();
        if (out == null) {
            return;
        }
        CommandState s = state(out);
        CommandHandler action = s.getHandler();
        if (action == null) {
            return;
        }
        if (action.proceedNext()) {
            terminate(action, true);
        }
        LOG.debug("PostReply ends.");
    }

    /**
     * Terminates / discards transmitted messages that were
     * long in the watched queue. Ensures eventual cleanup of
     * stale messages.
     */
    private void expireTransmittedMessages() {
        List<CommandState> toExpire = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        synchronized (this) {
            for (CommandState msg : transmittedMessages) {
                if (msg.getTimeSent() + stateTimeout < currentTime) {
                    toExpire.add(msg);
                }
            }
            transmittedMessages.removeAll(toExpire);
        }
        for (CommandState m : toExpire) {
            synchronized (m) {
                LOG.debug("EXPIRING: {}", m);
                if (m.getPhase() == Phase.CONFIRMED) {
                    m.toPhase(Phase.FINISHED);
                } else {
                    m.toPhase(Phase.EXPIRED);
                }
            }
            CommandHandler command = m.getHandler();
            if (command == null) {
                continue;
            }
            LOG.debug("Finishing state: {}", command);
            command.finished(m);
            if (command.proceedNext()) {
                terminate(command, m.getPhase() == Phase.FINISHED);
            }
        }
        debugPrintState();
        LOG.debug("Expiration check ends");
    }
    
    public void terminate(CommandHandler ac, boolean success) {
        LOG.debug("Terminated: {}", ac);
        List<CommandState> messages = new ArrayList<>(ac.getAllCommands());
        synchronized (this) {
            transmittedMessages.removeAll(messages);
            queuedMessages.removeAll(messages);
            messages.stream().filter(delayedMessages::containsKey).forEach(m -> {
                delayedMessages.remove(m).cancel(true);
            });
            
            if (lastSent == ac.getCommand()) {
                lastSent = null;
            }
        }
        Phase newPhase = success ? Phase.FINISHED : Phase.EXPIRED;
        messages.stream().forEach((m) -> {
            m.toPhase(newPhase);
        });
    }
    
}
