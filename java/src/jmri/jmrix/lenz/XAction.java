/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction of a command on XpressNet. A command consists of one to 
 * several messages generated over the time. The messages are sent to
 * XPressnet queues in the sequence defined by the command. A message
 * may be <b>delayed</b> (delaying the foloowing messages as well).
 * A command may have at most one message active - in the transmit
 * queue, or being transmitted. Each message must be confirmed 
 * by the XPressNet interface - the confirmation is command/manufacturer
 * dependent.
 * 
 * @author sdedic
 */
public class XAction {
    /**
     * The leading message.
     */
    protected final XNetMessage   commandMessage;
    
    /**
     * The target recipient for the command's outcome.
     */
    private final Reference<XNetListener>  target;

    /**
     * Diagnostics: ID of the commanded object in the layout.
     */
    private int layoutId;
    
    /**
     * Internal queue of additional messages that will be sent
     * after the leading one.
     */
    private List<XNetMessage>   messages;
    
    /**
     * Position into the additional message queue.
     */
    private int pos = -1;
    
    /**
     * Trivial listener, which does nothing.
     */
    private static final XNetListener TRASH = new XNetListener() {
        @Override
        public void message(XNetReply msg) {
        }

        @Override
        public void message(XNetMessage msg) {
        }

        @Override
        public void notifyTimeout(XNetMessage msg) {
        }
    };
    
    public XAction(XNetMessage commandMessage, XNetListener target) {
        this.commandMessage = commandMessage;
        this.target = new WeakReference<>(target);
        this.layoutId = -1;
        commandMessage.attachAction(this);
    }
    
    public XAction(XNetMessage commandMessage, XNetListener target, int layoutId) {
        this.commandMessage = commandMessage;
        this.target = new WeakReference<>(target);
        this.layoutId = layoutId;
        commandMessage.attachAction(this);
    }
    
    void addMessage(XNetMessage msg) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(msg);
    }
    
    public synchronized XNetMessage getCurrentMessage() {
        if (pos == -1) {
            return getCommandMessage();
        } else if (pos < messages.size()) {
            return messages.get(pos);
        } else {
            return null;
        }
    }
    
    public boolean canContinue() {
        XNetMessage cur = getCurrentMessage();
        return cur.getPhase().ordinal() >= cur.getPhase().FINISHED.ordinal();
    }
    
    /**
     * Proceeds with the next command, if any. Returns {@code true},
     * if the action should be terminated.
     * @param q the queue instance
     * @return true, if terminate
     */
    boolean proceedNext(XActionQueue q) {
        if (pos < messages.size() - 1) {
            if (!canContinue()) {
                return false;
            }
            ++pos;
            XNetMessage m = messages.get(pos);
            q.send(m, getTargetOrTrash());
            return true;
        } else {
            return false;
        }
    }
    
    protected void setLayoutId(int id) {
        this.layoutId = id;
    }
    
    public int getLayoutId() {
        return layoutId;
    }
    
    // ----------------------------------------------------
    // Overridable methods, which are called when a message
    // is being operated

    /**
     * Prepares the command for the execution.
     * @param queue 
     */
    protected StateUpdater prepare(XActionQueue queue, XNetMessage msg) {
        return null;
    }
    
    protected void sent(XActionQueue queue, XNetMessage msg) {
    }
    
    protected void processed(XActionQueue queue, XNetReply msg) {
    }
    
    protected void finished(XActionQueue queue, XNetMessage finsihed) {
    }
    
    public boolean acceptsMessage(XActionQueue q, XNetMessage msg) {
        return false;
    }
    
    public XNetListener getTarget() {
        return target.get();
    }
    
    public XNetListener getTargetOrTrash() {
        XNetListener t = getTarget();
        if (t != null) {
            return t;
        } else {
            return TRASH;
        }
    }
    
    public synchronized List<XNetMessage> getAllMessages() {
        if (messages == null || messages.isEmpty()) {
            return Collections.singletonList(commandMessage);
        } else {
            return new ArrayList<>(messages);
        }
    }
    
    public XNetMessage getCommandMessage() {
        return commandMessage;
    }

    /**
     * Determines if the initial command is finished.
     * @return 
     */
    public boolean isCommandFinished() {
        return pos == -1 && commandMessage.getPhase().ordinal() < XNetMessage.Phase.CONFIRMED.ordinal();
    }
    
    public boolean acceptsLayoutId(int id) {
        return id == this.layoutId;
    }
    
    public static class AccessoryInfo extends XAction {
        public AccessoryInfo(XNetMessage commandMessage, XNetListener target) {
            super(commandMessage, target, getCommandedAccesoryId(commandMessage));
        }

        public boolean acceptsLayoutId(int n) {
            int li = getLayoutId();
            return n == li || n == li + 1;
        }
    }
    
    public static final StateUpdater EMPTY = (r) -> {};
    
    public static interface StateUpdater extends Consumer<XNetReply>{}
    
    public static class Accessory extends XAction {
        // PENDING: probably remove; just the 1st message has it.
        private final boolean turnOn;
        private final int commandedState;
        
        private int knownState;
        private int pairedKnownState;
        
        public Accessory(XNetMessage commandMessage, XNetListener target) {
            super(commandMessage, target, getCommandedAccesoryId(commandMessage));
            int lo = commandMessage.getElement(2);
            this.turnOn = (lo & 0x08) > 0;
            this.commandedState = (lo & 0x01) > 0 ? XNetTurnout.THROWN : XNetTurnout.CLOSED;
        }
        
        public int getPairedNumber() {
            int n = getLayoutId();
            return (n & 0x01) > 0 ? n + 1 : n -1;
        }

        public boolean isTurnOn() {
            return turnOn;
        }

        public int getCommandedState() {
            return commandedState;
        }

        public int getKnownState() {
            return knownState;
        }

        public int getPairedKnownState() {
            return pairedKnownState;
        }
        
        private void prepareExpectedState(XActionQueue queue, XNetReply reply) {
            this.knownState = queue.getAccessoryState(getLayoutId());
            this.pairedKnownState = queue.getAccessoryState(getPairedNumber());
            // the command is being sent to the layout, change the accessory
            // state in the queue:
            queue.setAccessoryState(getLayoutId(), getCommandedState());
            
        }

        @Override
        protected StateUpdater prepare(XActionQueue queue, XNetMessage msg) {
            if (isCommandFinished()) {
                // not necessary to save state for turn off command.
                return EMPTY;
            }
            return (reply) -> prepareExpectedState(queue, reply);
        }

        @Override
        protected void processed(XActionQueue queue, XNetReply msg) {
            switch (getCommandMessage().getPhase()) {
                case CONFIRMED:
                    getTargetOrTrash().setMessageState(XNetTurnout.COMMANDSENT);
                    break;
                case CONFIRMED_AGAIN:
                    getTargetOrTrash().setMessageState(XNetTurnout.OFFSENT);
                    break;
            }
            getTargetOrTrash().message(msg);
            if (queue.acceptsFeedback(this, msg)) {
                getCommandMessage().toPhase(XNetMessage.Phase.FINISHED);
            }
        }

        @Override
        public boolean acceptsMessage(XActionQueue q, XNetMessage msg) {
            if (msg.getElement(0) != XNetConstants.ACC_OPER_REQ) {
                return false;
            }
            if (!acceptsLayoutId(getCommandedAccesoryId(msg))) {
                return false;
            }
            return (msg.getElement(2) & 0x08) == 0;
        }
    
    }

    public static int getQueriedAccesoryId(XNetMessage msg) {
        assert msg.getOpCode() == XNetConstants.ACC_INFO_REQ;
        int quadAddr = (msg.getElement(1) * 4);
        int lo = (msg.getElement(2) & 0x01);
        return quadAddr + (lo * 2) + 1;
    }

    public static int getCommandedAccesoryId(XNetMessage msg) {
        assert msg.getOpCode() == XNetConstants.ACC_OPER_REQ;
        int lo = msg.getElement(2) >> 1;
        int quadAddr = (msg.getElement(1) * 4);
        int loAddr =  (lo) & 0x03;
        return quadAddr + loAddr + 1;
   }
}
