package jmri.jmrix.lenzplus.comm;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;
import jmri.ProgrammingMode;
import jmri.Turnout;
import jmri.jmrix.SystemConnectionMemo;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTurnoutManager;
import jmri.jmrix.lenzplus.CompletionStatus;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.XNetPlusResponseListener;
import jmri.util.ThreadingUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QueueController distributes replies to appropriate {@link CommandHandler}s and
 * does the necessary bookkeeping.
 * QueueController must be called from appropriate places:
 * <ul>
 * <li>a message is placed into the transmit queue
 * <li>a message is physically sent out to the PC interface
 * <li>a reply is received from the PC interface
 * <li>the message-reply exchange terminates
 * <li>a message is rejected and will be repeated
 * <li>a message times out
 * </ul>
 * The QueueController creates {@link CommandHandler}s and distributes the work
 * to them, provides shared state to CommandHandlers, so they can contribute
 * to processing of each reply from the command station.
 * 
 * @author svatopluk.dedic@gmail.com, Copyrigh (c) 2020
 */
public class QueueController implements CommandService {
    private static final Logger LOG = LoggerFactory.getLogger(QueueController.class);
    
    /**
     * The traffic controller. Used to enque messages.
     */
    private final TrafficController controller;
    
    /**
     * Scheduling queue for commands.
     */
    protected final SchedulingQueue commandQueue;
    
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
     * Timeout to expunge stale messages from transmit queue. This timeout
     * is added to {@link XNetMessage#getTimeout}.
     */
    private long safetyTransmitTimeout = 200;
    
    /**
     * Messages queued will expire after this timeout, without being
     * ever sent.
     */
    private long safetyQueuedExpireTimeout = 10000;
    
    /**
     * Time that must elapse from a command so unsolicited messages
     * that would match a transmitted command are not considered
     * concurrent changes from the layout.
     */
    private long concurrentTimeAfter = 300;
    
    /**
     * Time for detection of concurrent layout operations before the
     * operation is sent to the layout. If a possibly concurrent unsolicited
     * reply is received less than this [ms] before the command is sent, 
     * the reply will be reported as a possible concurrency.
     */
    private long concurrentTimeBefore = 100;
    
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
     * The last message that was sent / is being sent by the transmit thread.
     * Can be only accessed from the Layout thread.
     */
    private volatile CommandState lastSent;
    
    private int concurrentActions;
    
    /**
     * Lookup to access extension services
     */
    protected Lookup services;
    
    private volatile ProgrammingMode mode;
    
    Collection<? extends MessageHandlerFactory> handlerFactories;

    public QueueController(TrafficController controller) {
        this(controller, new SchedulingQueue());
    }
    
    public QueueController(TrafficController controller, SchedulingQueue q) {
        this.controller = controller;
        this.commandQueue = q;
    }
    
    private List<String> pathPrefixes(String s) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add("xnetplus/" + s);
        for (int pos = s.lastIndexOf("/", s.length() - 2); pos > 0; pos = s.lastIndexOf(pos - 1)) {
            prefixes.add("xnetplus/" + s.substring(0, pos));
        }
        prefixes.add("xnetplus");
        return prefixes;
    }
    
    public synchronized Lookup getLookup() {
        if (services == null) {
            services = createLookup(null);
        }
        return services;
    }
    
    /**
     * Creates a composite Lookup. The "flavour" is one or more items, separated by ":". Paths identify service registrations underneath
     * {@code META-INF/namedservices}. For each path, <b>all parent paths</b> are also used, ordered form most specific path (child)
     * to least specific (parent). If more items generate the same (parent) path, the same paths generated by preceding items will be removed.
     * A default path item, {@code base} will be always included last.
     * For example, if we have 2 items: "lzv100" and "usb", the following ordered path sequence will be created:
     * <ol>
     * <li>xnetplus/lzv100
     * <li>xnetplus/usb
     * <li>xnetplus/base
     * <li>xnetplus
     * </ol>
     * This setup allows to override handlers, and merge handlers specific for a station and for an adapter. The last item in the Lookup
     * injects other JMRI services, from {@link TrafficController} and {@link SystemConnectionMemo}.
     * @param flavour one or more path items, or {@code null}.
     * @return composite Lookup.
     */
    protected Lookup createLookup(String flavour) {
        List<String> items = new ArrayList<>();
        if (flavour != null) {
            items.addAll(Arrays.asList(flavour.split(":")));
        }
        items.add("base");

        LinkedHashSet<String> lookupPaths = new LinkedHashSet<>();
        items.forEach(p -> {
            pathPrefixes(p).forEach(s -> {
                lookupPaths.remove(s);
                lookupPaths.add(s);
            });
        });

        List<Lookup> prefixes = 
                lookupPaths.stream().
                map(s -> Lookups.metaInfServices(getClass().getClassLoader(), "META-INF/namedservices/" + s + "/")).
                collect(Collectors.toList());
        // chain access to TC services:
        prefixes.add(controller.getLookup());
        Lookup[] arr = prefixes.toArray(new Lookup[prefixes.size()]);
        return new ProxyLookup(arr);
    }

    public long getStateTimeout() {
        return stateTimeout;
    }

    public void setStateTimeout(long stateTimeout) {
        this.stateTimeout = stateTimeout;
    }

    public long getConcurrentTimeAfter() {
        return concurrentTimeAfter;
    }

    public void setConcurrentTimeAfter(long concurrentTimeAfter) {
        this.concurrentTimeAfter = concurrentTimeAfter;
    }
    
    public synchronized void setTurnoutManager(XNetTurnoutManager mgr) {
        assert turnoutManager == null || turnoutManager == mgr;
        this.turnoutManager = mgr;
    }

    @Override
    public ProgrammingMode getMode() {
        return mode;
    }

    @Override
    public void modeEntered(ProgrammingMode m) {
        this.mode = m;
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
            if (!s.getPhase().passed(Phase.REJECTED)) {
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
    
    synchronized Collection<? extends MessageHandlerFactory> getHandlerFactories() {
        // cache factories, they could eventually keep the shared state for 
        // handlers.
        if (handlerFactories == null) {
            handlerFactories = getLookup().lookupAll(MessageHandlerFactory.class);
        }
        return handlerFactories;
    }
    
    protected CommandHandler forMessage(CommandState cmd, XNetListener callback) {
        return getHandlerFactories().stream().sequential().
                map(f -> f.createCommandHandler(this, cmd, callback)).
                filter(Objects::nonNull).
                findFirst().orElseGet(() -> 
                    new CommandHandler(cmd, callback)
                );
    }
    
    protected List<ReplyHandler> forReply(CommandState s, XNetPlusReply reply) {
        return getHandlerFactories().stream().sequential().
                map(f -> f.createReplyHandler(this, s, reply)).
                filter(Objects::nonNull).
                collect(Collectors.toList());
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
            long concurrencyThreshold = System.currentTimeMillis() - this.concurrentTimeBefore;
            s.markSent(concurrencyThreshold);
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
    
    private synchronized void rejected(CommandState st) {
        LOG.debug("Message REJECTED: {}", st);
        transmittedMessages.remove(st);
        st.toPhase(Phase.REJECTED);
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
    public ReplyOutcome preprocess(List<ReplyHandler> replyHandlers, CommandState s, XNetPlusReply r) {
        LOG.debug("Received reply: {} for {}", r, s);
        assureLayoutThread();
        expireTransmittedMessages();
        
        boolean dropExpiredReply = false;
        XNetPlusMessage cmd = r.getResponseTo();
        synchronized (this) {
            if (cmd != null && !transmittedMessages.contains(s)) {
                LOG.debug("Message {} has expired from transmit list, will discard reply {}", cmd, r);
                dropExpiredReply = true;
            }
        }
        for (ReplyHandler rh : replyHandlers) {
            LOG.debug("Passing to handler {}", rh);
            ReplyOutcome o = rh.preprocess(s, r);
            if (o != null) {
                if (o.isMessageFinished()) {
                    LOG.debug("Handler terminated the reply: {}", o);
                    return o;
                }
                if (o.isComplete()) {
                    break;
                }
            }
        }
        
        if (dropExpiredReply) {
            return ReplyOutcome.finished(r);
        }
        cmd = r.getResponseTo();
        CommandHandler h = null;
        if (cmd != null) {
            h = s.getHandler();
            s.getCompletionStatus().addReply(r);
            if (r.isRetransmittableErrorMsg()) {
                LOG.debug("Got retransmittable error for {}, failing", s);
                rejected(s);
                return ReplyOutcome.finished(s, r).reject();
            }

            // uniform handling: preprocess is rejected, but that means its confirmed,
            // and no further confirmation can come
            if (r.isUnsupportedError()) {
                LOG.debug("Got UNSUPPORTED error for {}, failing", s);
                terminate(s.getHandler(), Phase.FAILED);
                return ReplyOutcome.finished(s, r).fail();
            }
            LOG.debug("Trying to accept by {}", h);
            if (h.acceptsReply(cmd, r)) {
                processAttachedMessage(h, s, r);
                return null;
            }
        }
        
        return processUnsolicited(replyHandlers, s, r);
    }
    
    private void processAttachedMessage(CommandHandler cmd, CommandState ms, XNetPlusReply r) {
        boolean changed = ms.toPhase(Phase.CONFIRMED);
        LOG.debug("Confirmed: {}, state change: {}, handler: {}", ms, changed, cmd);
        if (ms.getPhase() == Phase.CONFIRMED) {
            cmd.sent(ms);
        }
    }
    
    ReplyOutcome processUnsolicited(List<ReplyHandler> replyHandlers, CommandState s, XNetPlusReply r) {
        LOG.debug("Reply is unsolicited.");
        r.markSolicited(false);
        ReplyOutcome o;
        
        
        for (ReplyHandler rh : replyHandlers) {
            o = rh.process(s, r);
            if (o != null) {
                if (o.isMessageFinished()) {
                    return o;
                }
                if (o.isComplete()) {
                    break;
                }
            }
        }
        filterThroughQueued(r, null);
        return ReplyOutcome.finished(r);
    }
    
    private void notifyConcurrentOperation(CommandHandler h, CommandState s, XNetPlusReply r) {
        XNetPlusMessage msg = s.getMessage();
        XNetListener l = null;
        if (msg != null) {
            l = msg.getReplyTarget();
        }
        CompletionStatus cs = s.getCompletionStatus();
        if (l == null && h.getPhase().passed(Phase.FINISHED)) {
            l = h.getTarget();
            cs = h.getInitialCommand().getCompletionStatus();
        }
        LOG.debug("Notify concurrent op {} for command {} to {}", r, s, l);
        if (l instanceof XNetPlusResponseListener) {
            try {
                ((XNetPlusResponseListener)l).concurrentLayoutOperation(cs);
            } catch (Exception ex) {
                LOG.error("Exception occurred during concurrent even delivery", ex);
            }
        } else {
            LOG.warn("Possibly concurrent operation occured. The original JMRI command: {}, "
                    + "the unsolicited concurrent reply: {}", s, r);
        }
    }
    
    void filterThroughQueued(XNetPlusReply r, CommandState except) {
        boolean uns = r.isUnsolicited();
        
        if (uns) {
            List<CommandState> transmitted;

            synchronized (this) {
                transmitted = new ArrayList<>(transmittedMessages);
            }
            long now = System.currentTimeMillis();
            Stream<CommandState> toCheck = transmitted.stream().
                    filter(s -> s != except).
                    filter(s -> s.getTimeSent() + concurrentTimeAfter > now
            );
            if (!toCheck.allMatch(c -> {
                    CommandHandler h = c.getHandler();
                    LOG.debug("Checking concurrency: {}", h);
                    boolean x = h.checkConcurrentAction(c, r);
                    if (x) {
                        r.markConcurrent(h.getRepresentativeCommand().getMessage());
                        h.handleConcurrentMessage(r);
                        if (h.getPhase().isFinal()) {
                            notifyConcurrentOperation(h, c, r);
                        }
                    }
                    return !x;
                })) {
                LOG.debug("** Concurrent action detected");
                concurrentActions++;
            }
        }
        
        commandQueue.getQueued().forEach(s -> {
            CommandHandler h = s.getHandler();
            LOG.debug("Filtering through: {}", h);
            if (uns && h.checkConcurrentAction(s, r)) {
                r.markConcurrent(h.getRepresentativeCommand().getMessage());
                s.markPossiblyConcurrent(r);
            }
            h.filterMessage(r);
        });
        if (!uns) {
            return;
        }
    }
    
    public ReplyOutcome processReply2(XNetPlusReply reply, Runnable callback) {
        ReplyOutcome out = null;
        try {
            XNetPlusMessage msg = reply.getResponseTo();
            CommandState cs = state(msg);
            List<ReplyHandler> rhs = forReply(cs, reply);
            ReplyOutcome out2 = preprocess(rhs, cs, reply);
            boolean shouldCallTarget = false;
            CommandHandler h = null;
            
            if (out2 != null) {
                out = out2;
            } else if (msg != null) {
                h = cs.getHandler();
                processedReplyAction = h;
                LOG.debug("Calling CommandHandler.processed {}", h);
                out = h.processed(cs, reply);
                cs.getCompletionStatus().addReply(out.getTargetReply());
                
                h.filterMessage(reply);

                for (ReplyHandler rh : rhs) {
                    ReplyOutcome o = rh.process(cs, reply);
                    if (o != null) {
                        if (o.isMessageFinished()) {
                            // cannot interfere with the handler
                            break;
                        }
                        if (o.isComplete()) {
                            break;
                        }
                    }
                }
                filterThroughQueued(reply, h);
                
                shouldCallTarget = 
                        (out.isComplete() || !out.isAdditionalReplyRequired()) &&
                        (cs != h.getInitialCommand());
                if (h.getConcurrentReply() != null && 
                    (h.getTimeSent() - h.getTimeConcurrentDetected()) < concurrentTimeAfter) {
                    LOG.debug("Concurrent reply detected");
                    h.handleConcurrentMessage(h.getConcurrentReply());
                }
            }
            try {
                // dangerous, calls out to random listeners in JMRI code
                callback.run();
            } catch (Exception ex) {
                if (out == null) {
                    out = ReplyOutcome.finished(reply);
                }
                out.withError(ex);
            }
            if (shouldCallTarget) {
                // for all but the initial command, notify the target. The initial command
                // will be handled later.
                notifyTarget(out, h, msg);
            }
        } catch (Exception ex) {
            if (out == null) {
                out = ReplyOutcome.finished(reply);
            }
            out.withError(ex);
        } finally {
            processedReplyAction = null;
        }
        return out;
    }
    
    /**
     * Sends out target notification.
     * @param h
     * @param msg
     * @param reply 
     */
    void notifyTarget(ReplyOutcome outcome, CommandHandler h, XNetPlusMessage msg) {
        XNetListener l = null;
        if (msg != null) {
            l = msg.getReplyTarget();
        }
        Phase cp = outcome.getState().getPhase();
        CompletionStatus cs = outcome.getState().getCompletionStatus();
        if (cp == Phase.FINISHED) {
            cs.success();
        }
        XNetPlusReply reply = outcome.getTargetReply();
        if (l == null && h.getPhase().passed(Phase.FINISHED)) {
            l = h.getTarget();
            cs = h.getInitialCommand().getCompletionStatus();
        }
        LOG.debug("Notify reply {} for command {} to {}", reply, outcome.getState(), l);
        if (l != null) {
            try {
                if (l instanceof XNetPlusResponseListener) {
                    XNetPlusResponseListener xnprl = (XNetPlusResponseListener)l;
                    LOG.debug("Notifying PLUS target: {}", l);
                    if (cp != Phase.FINISHED) {
                        if (cp == Phase.REJECTED) {
                            return;
                        }
                        xnprl.failed(cs);
                    } else {
                        xnprl.completed(cs);
                    }
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
        XNetPlusMessage out = outcome.getMessage();
        if (out == null) {
            LOG.debug("PostReply - unsolicited ends .");
            processedReplyAction = null;
            return;
        }
        outcome.setComplete(true);
        CommandState s = outcome.getState();
        CommandHandler action = s.getHandler();
        
        try {
            if (outcome.isMessageFinished()) {
                processedReplyAction = action;
                LOG.debug("Finishing state: {}", s);
                action.finished(outcome, s);
                if (action.advance()) {
                    terminate(action, s.getPhase().isConfirmed());
                    notifyTarget(outcome, action, null);
                }
            }
        } finally {
            processedReplyAction = null;
            LOG.debug("PostReply ends.");
            if (outcome.getException() != null) {
                LOG.warn("Exception was thrown during processing of {}", outcome);
                LOG.warn("Exception stacktrace: ", outcome.getException());
            }
        }
    }
    
    /**
     * Terminates / discards transmitted messages that were
     * long in the watched queue. Ensures eventual cleanup of
     * stale messages.
     */
    void expireTransmittedMessages() {
        List<CommandState> toExpire = new ArrayList<>();
        List<CommandState> toFinish = new ArrayList<>();
        List<CommandState> toRemove;
        long now = System.currentTimeMillis();
        synchronized (this) {
            // expire useless finished messages:
            toRemove = transmittedMessages.stream().
                    filter(c -> 
                        !c.getPhase().isActive() &&
                        c.getTimeSent() + concurrentTimeAfter < now).
                    collect(Collectors.toList());
            if (LOG.isDebugEnabled() && toRemove.isEmpty()) {
                LOG.debug("Discarding terminated messages after concurrent timeout: {}", toRemove);
            }
            transmittedMessages.removeAll(toRemove);
            
            transmittedMessages.stream().forEach(c -> {
                synchronized (c) {
                    if (c.getPhase().isConfirmed()) {
                        if (c.getTimeConfirmed() + this.stateTimeout < now) {
                            toFinish.add(c);
                            c.toPhase(Phase.FINISHED);
                        }
                    } else {
                        if (now > c.getConfirmTimeLimit()) {
                            // also includes CTL == -1 if an unsent (???) command was in the xmit list.
                            toExpire.add(c);
                            c.toPhase(Phase.EXPIRED);
                        }
                    }
                }
            });
            if (LOG.isDebugEnabled() && !toExpire.isEmpty()) {
                LOG.debug("Expiring messages: {}", toExpire);
            }
            transmittedMessages.removeAll(toExpire);
            if (LOG.isDebugEnabled() && !toFinish.isEmpty()) {
                LOG.debug("Finishing messages: {}", toFinish);
            }
            toFinish.addAll(toExpire);
        }
        for (CommandState m : toFinish) {
            CommandHandler command = m.getHandler();
            LOG.debug("Finishing state: {}, handler {}", m, command);
            if (command == null) {
                continue;
            }
            ReplyOutcome finishedOutcome = ReplyOutcome.finished(m, null);
            // ignore the result:
            command.finished(finishedOutcome, m);
            // clean up everything
            terminate(command, m.getPhase());
        }
        debugPrintState();
        LOG.debug("Expiration check ends");
    }
    
    public void terminate(CommandHandler ac, boolean success) {
        terminate(ac, success ? Phase.FINISHED : Phase.EXPIRED);
    }
    
    void terminate(CommandHandler ac, Phase newPhase) {
        LOG.debug("Terminated: {}", ac);
        List<CommandState> messages;
        synchronized (this) {
            messages = new ArrayList<>(ac.getAllCommands());
            messages.forEach(a -> {
                if (a.getPhase().isActive()) {
                    a.toPhase(newPhase);
                }
            });
        }
        ac.toPhase(newPhase);
        LOG.debug("Removing messages: {}", messages);
        commandQueue.removeAll(messages);
        synchronized (this) {
            if (lastSent == ac.getCommand()) {
                lastSent = null;
            }
            if (newPhase != Phase.FINISHED) {
                // expired messages are useless
                messages.stream().filter(c -> c.getPhase() == Phase.EXPIRED || c.getPhase() == Phase.FAILED).forEach(
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

    public int getConcurrentActions() {
        return concurrentActions;
    }
}
