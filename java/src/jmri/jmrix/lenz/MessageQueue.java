/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;


import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetConstants.Phase;
import static jmri.jmrix.lenz.XNetConstants.Phase.*;
import jmri.util.ThreadingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class MessageQueue implements XNetListener {
    private static final Logger LOG = LoggerFactory.getLogger(MessageQueue.class);
    
    private static AtomicLong   commandIdGenerator = new AtomicLong(1);
    
    /**
     * Represents a single command in the transmit queue. A command may be
     * chained to some leading one, such as "off" command to its corresponding
     * "on" command.
     */
    public static class CommandItem {
        /**
         * Target of the command.
         */
        private final Reference<XNetListener>  targetRef;
        private final XNetMessage   transmitted;
        private final long id;
        
        /**
         * Link to a previous command item
         */
        CommandItem leadingItem;

        private Phase   phase = Phase.QUEUED;
        
        /**
         * When the command was sent to the command station.
         */
        private long    commandSentTime = -1;
        
        /**
         * Time of reception of the last recognized response (OK or state).
         */
        private long    lastResponseTime = -1;

        /**
         * Confirmation 1/4/5 received. Each command may get at most
         * one confirmation.
         */
        private boolean okReceived;
        
        /**
         * Number of state messages received.
         */
        private int statesReceived;
        
        /**
         * List of chained commands.
         */
        private List<CommandItem>   commandChain = Collections.emptyList();

        public CommandItem(XNetListener target, XNetMessage transmitted) {
            this.targetRef = new WeakReference<>(target);
            this.transmitted = transmitted;
            this.id = commandIdGenerator.getAndIncrement();
        }
        
        public <T extends CommandItem> T addChainedCommand(T other) {
            commandChain.add(other);
            other.leadingItem = this;
            return other;
        }

        public XNetListener getTarget() {
            return targetRef.get();
        }

        public XNetMessage getTransmitted() {
            return transmitted;
        }


        public Phase getPhase() {
            return phase;
        }

        public boolean isOkReceived() {
            return okReceived;
        }

        public long getLastResponseTime() {
            return lastResponseTime;
        }

        public long getCommandSentTime() {
            return commandSentTime;
        }
        
        public long getLastCommandTime() {
            return lastResponseTime > 0 ? lastResponseTime : commandSentTime;
        }
        
        protected void notifySent() {
            assert phase == Phase.QUEUED || phase == Phase.OFFQUEUED;
            phase = Phase.SENT;
            commandSentTime = System.currentTimeMillis();
        }
        
        protected void confirmOK() {
            assert phase == Phase.SENT || phase == Phase.OFFSENT;
            lastResponseTime = System.currentTimeMillis();
        }

        public boolean isStateReceived() {
            return statesReceived > 0;
        }

        public void stateReceived() {
            statesReceived++;
        }
        
        public CommandItem  getChainedCommand() {
            if (commandChain.isEmpty()) {
                return null;
            } else {
                return commandChain.get(0);
            }
        }
    }
    
    /**
     * Track states of individual accessoires. This Map is updated
     * when an accessory "on" command is sent to the layout: from now on,
     * if a state reply is expected to contain the updated state, it will
     * be treated as a confirmation of the command, or a consequence of that
     * command, not a unsolicited feedback.
     */
    // @GuardedBy(this)
    private Map<Integer, Integer> expectedAccessoryStates = new HashMap<>();
    
    public static class AccessoryCommand extends CommandItem {
        private final int originalKnownState;
        private final int originalState;
        private final int commandedState;
        private final int transmittedState;

        private int knownState;
        private int pairedKnownState;
        private int pairedCommandedState;

        /**
         * Number of 'turnout off' commands sent.
         */
        private int offCommandsSent;

        public AccessoryCommand(XNetMessage transmitted, XNetTurnout target, 
                int transmittedState, int commanded) {
            super(target, transmitted);
            this.commandedState = commanded;
            this.transmittedState= transmittedState;
            this.originalKnownState = target.getKnownState();
            this.originalState = target.getCommandedState();
            this.knownState = target.getKnownState();
        }

        public int getKnownState() {
            return knownState;
        }

        public void setKnownState(int knownState) {
            this.knownState = knownState;
        }
        
        public XNetTurnout getTurnout() {
            return (XNetTurnout)getTarget();
        }

        public int getOriginalState() {
            return originalState;
        }

        public int getCommandedState() {
            return commandedState;
        }
        
        public boolean isOutputOn() {
            XNetMessage m = getTransmitted();
            if (m.getOpCode() != XNetConstants.ACC_OPER_REQ) {
                return false;
            }
            return (getTransmitted().getElement(1) & 0x08) > 0;
        }
        
        void setPairedState(int known, int commanded) {
            this.pairedCommandedState = commanded;
            this.pairedKnownState = known;
        }
        
        public int getAccessoryAddress() {
            return getTurnout().getNumber();
        }

        public int getPairedKnownState() {
            return pairedKnownState;
        }

        public int getPairedCommandedState() {
            return pairedCommandedState;
        }
        
        protected void notifySent() {
            getTurnout().setInternalState(transmittedState);
        }

    }
    
    private int confrmationTimeoutMs = 50;
    
    /**
     * Associations between outgoing XNetMessage and the command. Command is created
     * in Turnout, but this association comes handy when the message is received from
     * XNetListener interface when it's actually transmitted.
     */
    private final Map<XNetMessage, CommandItem>   commands = new WeakHashMap<>();
    
    /**
     * List of transmitted commands, which await confirmation or feedback.
     */
    private final Deque<CommandItem>   transmittedList = new ArrayDeque<>();
    
    /**
     * Commands that await (some) confirmation.
     */
    private final Deque<CommandItem>   commandsToConfirm = new ArrayDeque<>();
    
    public CommandItem createAccessoryCommand(XNetMessage msg, XNetTurnout turnout) {
        int cmd = (msg.getElement(1) & 0x01) > 0 ? XNetTurnout.THROWN : XNetTurnout.CLOSED;
        AccessoryCommand item = new AccessoryCommand(msg, turnout, XNetTurnout.COMMANDSENT, cmd);
        return registerCommand(msg, item);
    }
    
    public CommandItem registerCommand(XNetMessage msg, CommandItem item) {
        commands.put(msg, item);
        return item;
    }
    
    protected CommandItem processOutgoingCommand(CommandItem cmd) {
        if (cmd instanceof AccessoryCommand) {
            cmd = processTurnoutAccessoryCommand((AccessoryCommand)cmd);
        }
        return cmd;
    }
    
    public synchronized int getExpectedAccessoryState(int address) {
        return expectedAccessoryStates.getOrDefault(address, Turnout.UNKNOWN);
    }
    
    protected CommandItem processTurnoutAccessoryCommand(AccessoryCommand cmd) {
        XNetMessage xm = cmd.getTransmitted();
        assert xm.getOpCode() == XNetConstants.ACC_OPER_REQ;
        if (cmd.isOutputOn()) {
            int accAddr = cmd.getAccessoryAddress();
            int pairedAddr;
            
            if ((accAddr & 0x01) > 0) {
                pairedAddr = accAddr + 1;
            } else {
                pairedAddr = accAddr - 1;
            }
            int expectedPairedState = getExpectedAccessoryState(pairedAddr);
            cmd.setPairedState(expectedPairedState, expectedPairedState);
            
            int knownState = getExpectedAccessoryState(accAddr);
            cmd.setKnownState(knownState);
            
            expectedAccessoryStates.put(accAddr, cmd.getCommandedState());
        }
        return cmd;
    }

    @Override
    public void message(XNetMessage msg) {
        CommandItem item = commands.remove(msg);
        if (item == null) {
            item = new CommandItem(null, msg);
        }
        LOG.debug("Message sent: {}, decoded: {}, command: {}", msg, msg.toMonitorString(), item);
        CommandItem result = processOutgoingCommand(item);
        if (result != item) {
            LOG.debug("Command transformed to: {}", result);
        }
        switch (result.getPhase()) {
            case QUEUED:
                transmittedList.add(result);
                result.notifySent();
                break;
            case OFFQUEUED:
                break;
            default:
                throw new IllegalStateException("Invalid command state: " + result.getPhase().name());
        }
        commandsToConfirm.add(result);
    }

    @Override
    public void notifyTimeout(XNetMessage msg) {
    }
    
    private XNetReply       currentReply;
    private CommandItem     currentItem;

    public CommandItem getCommandForReply() {
        assert ThreadingUtil.isLayoutThread();
        return currentItem;
    }
    
    public CommandItem getChainedCommand() {
        CommandItem cur = getCommandForReply();
        return cur == null ? null : cur.getChainedCommand();
    }

    @Override
    public void message(XNetReply msg) {
        currentReply = msg;
        try {
            if (msg.isOkMessage()) {
                confirmNextCommand(msg);
            } else if (msg.isFeedbackMessage()) {
                matchFeedbackMessage(msg);
            }
        } finally {
            currentReply = null;
            currentItem = null;
        }
    }

/*

    Turnout operation > Feedback
    Turnout operation > OK + Feedback
    Turnout oper

ACK response: 0x20 -> OK
Resume Operations: 0x21, 0x81 -> OK
Sop operations: 0x21, 0x80 -> Track power off, several times
Stop all locomotives: 0x80 -> everything stopped
Emergency Stop Loco (1+2): 0x91, yyy -> OK
Emergency Stop Loco (Xnet): 0x92, yyy, zzz -> OK
    
*/    
    
    /**
     * Confirm the first message in the queue not yet confirmed.
     * @param reply 
     */
    void confirmNextCommand(XNetReply reply) {
        if (commandsToConfirm.isEmpty()) {
            LOG.debug("Unsolicited OK received");
            reply.setUnsolicited();
            return;
        }
        CommandItem item = commandsToConfirm.removeFirst();
        currentItem = item;
        item.confirmOK();
        item.getTarget().message(reply);
    }
    
    /**
     * Locates appropriate turnout for the feedback message. Goes through
     * not expired transmitted commands, in the order they were sent. It matches
     * the feedback status to each command's expected status outcome. The first one
     * that fits exactly will be selected.
     * <p/>
     * If no matching command is found, the method will try to determine if the feedback
     * is predating already transmitted commands that have not been acknowledged by
     * OK or feedback: if the turnout with an unexpected state matches a command, and
     * the other state in the pair corresponds to the expected output state,
     * the message will be <b>discarded</b> in a hope that a real feedback confirmation
     * will come for the command.
     * 
     * @param feedback 
     */
    void matchFeedbackMessage(XNetReply feedback) {
        int addr = 1;
        for (int i = 0; i < 0 /*feedback.getFeedbackMessageItems() */; i++, addr += 2) {
            int t = feedback.getFeedbackMessageType(addr);
            int addrOdd = feedback.getTurnoutMsgAddr(addr);
            
            if (addrOdd == -1) {
                continue;
            }
            int oddState = feedback.getTurnoutStatus(addr, 1);
            int evenState = feedback.getTurnoutStatus(addr, 0);
            AccessoryCommand cmd = null;
            if (oddState == XNetTurnout.THROWN || oddState == XNetTurnout.CLOSED) {
                cmd = checkTurnoutFeedbackResponse(feedback, addrOdd, oddState, evenState, true);
            }
            if (cmd == null) {
                if (evenState == XNetTurnout.THROWN || evenState == XNetTurnout.CLOSED) {
                    cmd = checkTurnoutFeedbackResponse(feedback, addrOdd + 1, evenState, oddState, true);
                }
            }
            
            if (cmd != null) {
                cmd.stateReceived();
                cmd.getTurnout().message(feedback);
                return;
            }
            
            // no turnout command that would correspond to the feedback found;
            // search for pending commands with the opposite commanded state
            // that would supersede this feedback:
            if (oddState == XNetTurnout.THROWN || oddState == XNetTurnout.CLOSED) {
                cmd = checkTurnoutFeedbackResponse(feedback, addrOdd, oppositeState(oddState), oddState, false);
            }
        }
    }
    
    private static int oppositeState(int st) {
        if (st == XNetTurnout.THROWN) {
            return XNetTurnout.CLOSED;
        } else if (st == XNetTurnout.CLOSED) {
            return XNetTurnout.THROWN;
        } else {
            return st;
        }
    }
    
    AccessoryCommand checkTurnoutFeedbackResponse(XNetReply feedback, int turnout, int state, int pairedState, boolean sent) {
        for (CommandItem item : transmittedList) {
            if (!(item instanceof AccessoryCommand)) {
                continue;
            }
            AccessoryCommand tc = (AccessoryCommand)item;
            if (tc.getTurnout().getNumber() != turnout) {
                continue;
            }
            if (sent && (tc.getPhase().ordinal() < Phase.SENT.ordinal())) {
                continue;
            }
            if (tc.getCommandedState() == state && 
                (tc.getPairedKnownState() == XNetTurnout.UNKNOWN || tc.getPairedKnownState() == pairedState)) {
                if (acceptsFeedback(feedback, tc)) {
                    return tc;
                }
            }
        }
        return null;
    }
    
    synchronized void removeCommand(CommandItem item) {
        if (!transmittedList.remove(item)) {
            return;
        }
        LOG.debug("Removing transmitted command: {}", item);
        commandsToConfirm.remove(item);
        commands.remove(item.getTransmitted());
    }
    
    /**
     * Informs that reply reception is finished. Commands that
     * were sucessfully confirmed and do not require special handling
     * will be removed from the transmitted queue.
     */
    public void receiveReplyFinished() {
        ThreadingUtil.runOnLayoutEventually(() -> {
            for (CommandItem cmd : new ArrayList<>(transmittedList)) {
                if (cmd.getPhase().ordinal() >= Phase.CONFIRMED.ordinal()) {
                    if (!receivesDelayedReply(cmd)) {
                        removeCommand(cmd);
                    }
                }
            }
        });
    }
    
    /**
     * Determines if command may receive a delayed reply. The default
     * implementation assumes that 'accessory request ON' may get a delayed
     * reply (feedback).
     * @param command command item
     * @return true, if delayed reply is possible.
     */
    protected boolean receivesDelayedReply(CommandItem command) {
        if (command instanceof AccessoryCommand) {
            // we only handle accessory commands at this point.
            AccessoryCommand ac = (AccessoryCommand)command;
            return ac.isOutputOn();
        } else {
            return false;
        }
    }
    
    /**
     * Purges obsolete items from the transmit list.
     */
    void purgeTransmittedList() {
        long current = System.currentTimeMillis();
        Collection<CommandItem> expiredItems = new ArrayList<>();
        for (Iterator<CommandItem> it = transmittedList.iterator(); it.hasNext(); ) {
            CommandItem cmd = it.next();
            if (cmd.getCommandSentTime() + confrmationTimeoutMs < current) {
                if (commandsToConfirm.remove(cmd)) {
                    expiredItems.add(cmd);
                }
            }
        }
        
        for (CommandItem i : expiredItems) {
            removeCommand(i);
        }
    }
    
    protected final void confirm(CommandItem item, boolean terminate) {
        if (!item.isOkReceived()) {
            CommandItem head = commandsToConfirm.removeFirst();
            assert head == item;
            head.confirmOK();
        }
        if (terminate) {
            transmittedList.remove(item);
        }
    }
    
    protected void assignFeedback(XNetReply reply, CommandItem item) {
        confirm(item, false);
    }

    protected boolean acceptsFeedback(XNetReply reply, CommandItem item) {
        return false;
    }
}
