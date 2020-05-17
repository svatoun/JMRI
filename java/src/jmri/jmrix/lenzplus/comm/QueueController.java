/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;


import jmri.jmrix.lenzplus.impl.DefaultHandler;
import jmri.jmrix.lenzplus.impl.AccessoryHandler;
import jmri.jmrix.lenzplus.impl.ProgModeHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;
import jmri.Turnout;
import jmri.TurnoutManager;
import jmri.implementation.AbstractTurnout;
import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTurnoutManager;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.XNetPlusResponseListener;
import jmri.jmrix.lenzplus.XNetPlusTrafficController;
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
public class QueueController implements CommandService {
    private static final Logger LOG = LoggerFactory.getLogger(QueueController.class);
    
    /**
     * The traffic controller. Used to enque messages.
     */
    private final TrafficController controller;
    
    private final SchedulingQueue commandQueue = new SchedulingQueue();
    
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
     * Time that must elapse from a command so unsolicited messages
     * that would match a transmitted command are not considered
     * concurrent changes from the layout.
     */
    private long concurrentUnsolicitedTime = 300;
    
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
    private volatile CommandState lastSent;
    
    private int concurrentActions;
    
    public static interface StateUpdater extends Consumer<XNetPlusReply>{}
    
    public QueueController(TrafficController controller) {
        this.controller = controller;
    }

    public long getStateTimeout() {
        return stateTimeout;
    }

    public void setStateTimeout(long stateTimeout) {
        this.stateTimeout = stateTimeout;
    }

    public long getConcurrentUnsolicitedTime() {
        return concurrentUnsolicitedTime;
    }

    public void setConcurrentUnsolicitedTime(long concurrentUnsolicitedTime) {
        this.concurrentUnsolicitedTime = concurrentUnsolicitedTime;
    }
    
    public synchronized void setTurnoutManager(XNetTurnoutManager mgr) {
        assert turnoutManager == null || turnoutManager == mgr;
        this.turnoutManager = mgr;
    }
    
    @GuardedBy("this")
    /* test-private */ CommandState state(XNetPlusMessage msg) {
        if (msg == null) {
            return null;
        }
        return commands.computeIfAbsent(msg, CommandState::new);
    }
    
    /* test-private */ List<CommandState> getTransmittedMessages() {
        synchronized (this) {
            return new ArrayList<>(transmittedMessages);
        }
    }

    public Future<?> getFutureCommand(CommandState s) {
        return commandQueue.getFutureCommand(s);
    }
    
    public XNetPlusMessage pollMessage() {
        CommandState s = commandQueue.poll();
        return s == null ? null : s.getMessage();
    }
    
    /**
     * Sends the message to the XPressnet. The actual effect will depend on whether the
     * message is {@link XNetMessage#isPriorityMessage} or {@link XNetMessage#isDelayed()}.
     * Delayed messages are just recorded and <b>scheduled</b> to a later time.
     * <p>
     * This method is intended to be called from the Traffic Controller, can be called
     * from an arbitrary thread.
     * @param msg the preprocess to sent.
     */
    public boolean send(XNetPlusMessage msg, XNetListener callback) {
        CommandState s = send(null, msg, callback);
        return s.getPhase().passed(Phase.QUEUED);
    }
    
    public boolean replay(XNetPlusMessage msg) {
        CommandState s;
        synchronized (this) {
            s = state(msg);
            if (s == null) {
                return false;
            }
            if (!s.getPhase().passed(Phase.SENT)) {
                return false;
            }
            transmittedMessages.remove(s);
        }
        commandQueue.replay(s);
        return true;
    }
    
    public CommandState send(CommandHandler h, XNetPlusMessage msg, XNetListener callback) {
        if (h != null && callback != null) {
            throw new IllegalArgumentException("Cannot use handler and callback at the same time");
        }
        CommandState state;
        synchronized (this) {
            state = state(msg);
            LOG.debug("Request to sent {}, callback: {}", state, callback);
            if (transmittedMessages.remove(state)) {
                // support for retransmissions.
                if (lastSent == state) {
                    lastSent = null;
                }
            }
            
            CommandHandler cmd = state.getHandler();
            if (cmd == null) {
                cmd = h;
            }
            if (cmd == null && processedReplyAction != null) {
                if (processedReplyAction.addMessage(state)) {
                    LOG.debug("Accepted {} into {}", state, processedReplyAction);
                    cmd = processedReplyAction;
                }
            }
            if (cmd == null) {
                // creates / attaches an action, if necessary.
                cmd = forMessage(state, callback);
                cmd.attachQueue(this);
                LOG.debug("Command handler {} created for {}", cmd, state);
            }
            state.attachHandler(cmd);
            commandQueue.add(state, false);
        }
        return state;
    }
    
    // TODO: refactor to a separate CommandHandlerFactory
    protected CommandHandler forMessage(CommandState cmd, XNetListener callback) {
        XNetMessage m = cmd.getMessage();
        
        switch (m.getElement(0)) {
            case XNetConstants.ACC_OPER_REQ:
                return new AccessoryHandler(cmd, callback);
            case XNetConstants.PROG_WRITE_REQUEST:
            case XNetConstants.PROG_READ_REQUEST:
                return new ProgModeHandler(cmd, callback);
        }
        
        return new DefaultHandler(cmd, callback);
    }
    
    /**
     * Binds outgoing message to a command. This method is called 
     * as the first listener of XNetTrafficController.
     * @param msg 
     */
    public void message(XNetPlusMessage msg) {
        synchronized (this) {
            CommandState s = state(msg);
            LOG.debug("Being sent: {}", s);
            s.toPhase(Phase.SENT);
            CommandHandler h = s.getHandler();
            if (h.getInitialCommand() == s) {
                h.toPhase(Phase.SENT);
            }
            transmittedMessages.add(s);
            this.lastSent = s;
            debugPrintState();
        }
    }
    
    private synchronized void debugPrintState() {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        LOG.debug("Last sent: {}, current command: {}", lastSent, (lastSent == null ? "N/A" : lastSent.getHandler()));
        StringBuilder sb = new StringBuilder();
        transmittedMessages.forEach((s) -> {
            sb.append("\n\t").append(s);
        });
        sb.append("\n\t\t");
        LOG.debug("Trasnmitted queue: {}", sb);
    }
    
    synchronized void rejected(CommandState st) {
        LOG.debug("Message REJECTED: {}", st);
        transmittedMessages.remove(st);
        st.toPhase(Phase.CREATED);
    }
    
    protected void assureLayoutThread() {
        if (!ThreadingUtil.isLayoutThread()) {
            throw new IllegalStateException("Must be called in layout thread.");
        }
    }
    
    /**
     * Preprocesses a reply.
     * @param r 
     */
    public void preprocess(XNetPlusReply r) {
        LOG.debug("Received reply: {}", r);
        assureLayoutThread();
        expireTransmittedMessages();
        
        XNetPlusMessage cmd = r.getResponseTo();
        CommandState s = null;
        CommandHandler h = null;
        if (cmd != null) {
            s = state(cmd);
            h = s.getHandler();

            if (r.isRetransmittableErrorMsg()) {
                rejected(s);
                return;
            }

            // uniform handling: preprocess is rejected, but that means its confirmed,
            // and no further confirmation can come
            if (r.isUnsupportedError()) {
                terminate(s.getHandler(), true);
                return;
            }
            LOG.debug("Trying to accept by {}", h);
            if (h.acceptsReply(cmd, r)) {
                processAttachedMessage(h, s, r);
                return;
            }
        }
        processUnsolicited(r);
    }
    
    void processUnsolicited(XNetPlusReply r) {
        LOG.debug("Reply is unsolicited.");
        r.markSolicited(false);
        r.setResponseTo(null);
        filterThroughQueued(r, null);
    }
    
    void filterThroughQueued(XNetPlusReply r, CommandState except) {
        Set<CommandHandler> seen = new HashSet<>();
        commandQueue.getQueued().forEach(s -> {
            CommandHandler h = s.getHandler();
            if (seen.add(h)) {
                LOG.debug("Filtering through: {}", h);
                h.filterMessage(r);
            }
        });
        List<CommandState> transmitted;
        
        synchronized (this) {
            transmitted = new ArrayList<>(transmittedMessages);
        }
        long now = System.currentTimeMillis();
        Stream<CommandState> toCheck = transmitted.stream().
                filter(s -> s != except && s.getPhase().isActive()).
                filter(
                    s -> s.getTimeSent() + concurrentUnsolicitedTime > now
        );
        seen.clear();
        if (!toCheck.allMatch(c -> {
                CommandHandler h = c.getHandler();
                LOG.debug("Checking concurrency: {}", h);
                if (seen.add(h)) {
                    return !h.checkConcurrentAction(c, r);
                } else {
                    return true;
                }
            })) {
            LOG.debug("** Concurrent action detected");
            concurrentActions++;
        }
    }
    
    void processAttachedMessage(CommandHandler cmd, CommandState ms, XNetPlusReply r) {
        LOG.debug("Initial filter: {}", cmd);
        cmd.filterMessage(r);
        filterThroughQueued(r, cmd);

        boolean changed = ms.toPhase(Phase.CONFIRMED);
        LOG.debug("Confirmed: {}, state change: {}, handler: {}", ms, changed, cmd);
        if (ms.getPhase() == Phase.CONFIRMED) {
            cmd.sent(ms);
        }
    }
    
    public ReplyOutcome processReply2(XNetPlusReply reply, Runnable callback) {
        ReplyOutcome out = ReplyOutcome.finished(reply);
        try {
            preprocess(reply);
            XNetPlusMessage msg = reply.getResponseTo();
            if (msg == null) {
                return ReplyOutcome.finished(reply);
            }
            CommandState cs = state(msg);
            CommandHandler h = cs.getHandler();
            out = h.processed(cs, reply);
            try {
                // dangerous, calls out to random listeners in JMRI code
                callback.run();
            } catch (Exception ex) {
                out.withError(ex);
            }
            if ((out.isComplete() || !out.isAdditionalReplyRequired()) &&
                cs != h.getInitialCommand()) {
                // for all but the initial command, notify the target. The initial command
                // will be handled later.
                notifyTarget(out, h, msg, reply);
            }
        } catch (Exception ex) {
            out.withError(ex);
        }
        return out;
    }
    
    /*
    public ReplyOutcome processReply(XNetPlusReply reply) {
        assureLayoutThread();
        XNetPlusMessage msg = reply.getResponseTo();
        if (msg == null) {
            return ReplyOutcome.finished(reply);
        }
        CommandState last = state(msg);
        CommandHandler h = last.getHandler();
        ReplyOutcome outcome = h.processed(last, reply);
        if ((outcome.isComplete() || !outcome.isAdditionalReplyRequired()) &&
            last != h.getInitialCommand()) {
            // for all but the initial command, notify the target. The initial command
            // will be handled later.
            notifyTarget(outcome, h, msg, reply);
        }
        if (outcome.isComplete()) {
            last.toPhase(Phase.FINISHED);
        }
        outcome.markConsumed();
        return outcome;
    }
    */
    
    /**
     * Sends out target notification.
     * @param h
     * @param msg
     * @param reply 
     */
    void notifyTarget(ReplyOutcome outcome, CommandHandler h, XNetPlusMessage msg, XNetPlusReply reply) {
        XNetListener l = null;
        if (msg != null) {
            l = msg.getReplyTarget();
        }
        if (l == null && h.getCommand().getPhase().passed(Phase.FINISHED)) {
            l = h.getTarget();
        }
        if (l != null) {
            try {
                if (l instanceof XNetPlusResponseListener) {
                    if (msg == null) {
                        msg = reply.getResponseTo();
                    }
                    LOG.debug("Notifying PLUS target: {}", l);
                    ((XNetPlusResponseListener)l).completed(msg, reply);
                } else {
                    LOG.debug("Notifying XNet target: {}", l);
                    l.message(reply);
                }
            } catch (Exception ex) {
                LOG.error("Error during XNet target notification", ex);
                outcome.withError(ex);
            }
        }
    }
    
    public void replyFinished(ReplyOutcome outcome) {
        assureLayoutThread();
        XNetPlusReply msg = outcome.getReply();
        LOG.debug("PostReply for {}", msg);
        processedReplyAction = null;
        XNetPlusMessage out = msg.getResponseTo();
        if (out == null) {
            return;
        }
        outcome.setComplete(true);
        CommandState s = state(out);
        CommandHandler action = s.getHandler();
        LOG.debug("Finishing state: {}", s);
        action.finished(outcome, s);
        if (action.advance()) {
            terminate(action, true);
            notifyTarget(outcome, action, null, msg);
        }
        LOG.debug("PostReply ends.");
    }
    
    /**
     * Terminates / discards transmitted messages that were
     * long in the watched queue. Ensures eventual cleanup of
     * stale messages.
     */
    void expireTransmittedMessages() {
        List<CommandState> toExpire;
        List<CommandState> toRemove;
        long currentTime = System.currentTimeMillis();
        synchronized (this) {
            // expire useless finished messages:
            toRemove = transmittedMessages.stream().
                    filter(c -> 
                        !c.getPhase().isActive() &&
                        c.getTimeSent() + concurrentUnsolicitedTime < currentTime).
                    collect(Collectors.toList());
            transmittedMessages.removeAll(toRemove);
            toExpire = transmittedMessages.stream().filter(
                c -> (c.getTimeSent() + stateTimeout < currentTime)
                    && c.getPhase().isActive()).
                    collect(Collectors.toList());
            transmittedMessages.removeAll(toExpire);
        }
        for (CommandState m : toExpire) {
            if (!m.getPhase().isActive()) {
                continue;
            }
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
            ReplyOutcome finishedOutcome = ReplyOutcome.finished(m, null);
            if (command.finished(finishedOutcome, m)) {
                terminate(command, m.getPhase() == Phase.FINISHED);
            }
        }
        debugPrintState();
        LOG.debug("Expiration check ends");
    }
    
    public void terminate(CommandHandler ac, boolean success) {
        LOG.debug("Terminated: {}", ac);
        List<CommandState> messages = new ArrayList<>(ac.getAllCommands());
        Phase newPhase = success ? Phase.FINISHED : Phase.EXPIRED;
        commandQueue.removeAll(messages);
        synchronized (this) {
            if (lastSent == ac.getCommand()) {
                lastSent = null;
            }
            if (newPhase == Phase.EXPIRED) {
                // expired messages are useless
                messages.stream().filter(c -> c.getPhase() == Phase.EXPIRED).forEach(
                        c -> transmittedMessages.remove(c));
            }
        }
    }
    
    @Override
    public synchronized void expectAccessoryState(int accId, int state) {
        accessoryState.put(accId, state);
    }

    @Override
    public synchronized int getAccessoryState(int id) {
        return accessoryState.getOrDefault(id, Turnout.UNKNOWN);
    }

    @Override
    public void requestAccessoryStatus(int id) {
        TurnoutManager mgr = controller.lookup(TurnoutManager.class);
        AbstractTurnout tnt = (AbstractTurnout)mgr.getTurnout("X" + id);
        if (tnt != null) {
            tnt.requestUpdateFromLayout();
        }
    }

    public int getConcurrentActions() {
        return concurrentActions;
    }
}
