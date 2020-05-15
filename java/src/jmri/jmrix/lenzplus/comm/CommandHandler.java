/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 * Base class for actions commanded on XPressNet. The Handler brings knowledge
 * specific to a certain XPressNet command and assists in processing the
 * incoming replies, so that {@link XNetListener} clients may focus just on
 * their own state updates when processing a {@link XNetPlusReply}.
 * <p>
 * Handlers of <b>all known</b> commands are involved in the process, even
 * though their commands may not be yet transmitted. This is because if a layout
 * change is going to happen on behalf of JMRI, it's not good to e.g. synchronize
 * Turnout's CommandedState to CLOSED, if the Turnout has been already commanded
 * THROWN, but its command was just not delivered yet.
 * <p>
 * The CommandHandler also helps to determine, if a command reply sequence is over,
 * an additional reply must be received, or a reply MAY be received (subject to
 * layout conditions, command station type etc).
 * 
 * @author sdedic
 */
public class CommandHandler extends CommandState {
    /**
     * The initial command message. There may be subsequent commands
     * before the whole Action completes.
     */
    protected final CommandState   command;

    /**
     * The target recipient for the command's outcome. WeakReference
     * so that dangling XNetActions will not keep layout objects in
     * memory.
     */
    private final Reference<XNetListener>  target;

    /**
     * Queue for actions. Must be set before the action reaches
     * {@link Phase#QUEUED} state. Never altered.
     */
    private /* semifinal */ CommandService actionQueue;

    /**
     * Diagnostics: ID of the commanded object in the layout. 
     * Must be initialized during setup of the object.
     */
    private int layoutId;
    
    /**
     * Ordered list of managed commands. Initially {@code null}, so that
     * the {@linl #initialCommand} is the only one. Once more commands
     * are added, will be initialized to a List that includes the
     * {@link #initialCommand}.
     */
    private List<CommandState>  allCommands;
    
    /**
     * Pointer into the list of commands. Contains index of the current command.
     */
    private int cmdIndex;
    
    /**
     * The command currently in effect. Initially equals to
     * {@link #command}, but may change if the action sends more 
     * commands.
     */
    private CommandState currentCommand;
    
    public CommandHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage.getMessage());
        this.command = commandMessage;
        this.currentCommand  = command;
        this.target = new WeakReference<>(target != null ? target : commandMessage.getMessage().getReplyTarget());
        this.layoutId = -1;
    }
    
    protected CommandService getQueue() {
        return actionQueue;
    }
    
    protected void setLayoutId(int id) {
        if (getInitialCommand().getPhase().passed(Phase.QUEUED)) {
            throw new IllegalStateException("layoutID must be set up before the command enters the queue");
        }
        synchronized (this) {
            this.layoutId = id;
        }
    }

    /**
     * Returns ID of the target layout object. -1, if the action
     * is not targeted. The meaning of the number is function-specific.
     * @return layout object's ID, or {@code -1}
     */
    public int getLayoutId() {
        return layoutId;
    }

    /**
     * Returns the notification target. 
     * @return 
     */
    public XNetListener getTarget() {
        XNetListener t = getCommand().getMessage().getReplyTarget();
        if (t == null) {
            t = target.get();
        }
        return t != null ? t : TRASH;
    }
    
    /**
     * Returns the initial command that started the action. This is the command
     * message initiated from upper layers. 
     * @return the initial command.
     */
    public final CommandState getInitialCommand() {
        return command;
    }
    
    /**
     * The currently active command. An action may send out multiple commands:
     * Accessory operation request will send an appropriate (delayed) Output OFF 
     * operation request.
     * @return current command.
     */
    public CommandState getCommand() {
        return currentCommand;
    }
    
    /**
     * Returns all contained commands. Used for management purposes.
     * @return all commands.
     */
    public final synchronized List<CommandState> getAllCommands() {
        if (allCommands == null) {
            return Collections.singletonList(getCommand());
        } else {
            return Collections.unmodifiableList(allCommands);
        }
    }
    
    final boolean advance() {
        getCommand().toPhase(Phase.FINISHED);
        if (allCommands == null || cmdIndex == allCommands.size() - 1) {
            return true;
        }
        if (allCommands.get(cmdIndex) == currentCommand) {
            cmdIndex++;
        }
        currentCommand = allCommands.get(cmdIndex);
        return false;
    }
    
    /**
     * Returns the phase of the last/active command in this action.
     * @return Phase.
     */
    public final Phase lastPhase() {
        return getCommand().getPhase();
    }
    
    /**
     * Called when the command has been sent to the XPressNet. More precisely:
     * the command has been sent, an a {@link XNetPlusReply} appeared that may
     * have been a confirmation to the command.
     * <p>
     * This callback is called at most once, when {@link CommandState} first
     * reaches the {@link CommandState.Phase#CONFIRMED}.
     * <p>
     * The handler must apply any necessary actions to the JMRI shared state to
     * ensure that subsequent evaluation of replies can match the reply's data
     * to the expected layout state to detect inconsistencies.
     *
     * @param msg the command/message that was sent without an error.
     */
    public void sent(CommandState msg) {
        if (getInitialCommand() == msg) {
            toPhase(Phase.SENT);
        }
    }
    
    /**
     * Callback to process the reply and filter or preprocess some of its data.
     * The callback will be called on solicited or unsolicited {@link XNetPlusReply}, after its
     * attachment to a {@link XNetPlusMessage} is computed. In case of a solicited reply,
     * the current handler will be called first. All queued handlers will be called
     * in an unspecified order.
     * s
     * @param m reply to filter.s
     * @return 
     */
    public boolean filterMessage(XNetPlusReply m) {
        return false;
    }

    /**
     * Called to process the reply to the specified message. It is called once
     * for each {@link XNetPlusReply} received and attached to the `msg`
     * command.
     *
     * @param msg   the command state
     * @param reply
     */
    public ReplyOutcome processed(CommandState msg, XNetPlusReply reply) {
        int confirmations = msg.getOkReceived() + msg.getStateReceived();
        if (reply.isOkMessage()) {
            msg.addOkMessage();
        } else if (reply.isFeedbackBroadcastMessage()) {
            msg.addStateMessage();
        }
        if (msg == getInitialCommand()) {
            toPhase(Phase.CONFIRMED);
        }
        if (reply.isBroadcast() && confirmations < 1) {
            ReplyOutcome o = new ReplyOutcome(msg, reply);
            o.setAdditionalReplyRequired(true);
            return o;
        } else {
            return ReplyOutcome.finished(reply);
        }
    }
    
    /**
     * The command has been finished. Clean up, issue or schedule another
     * Command. The default implementation returns true if the last
     * command has been finished.
     * @param finished the finished command
     * @return true, if the action terminates.
     */
    public boolean finished(ReplyOutcome outcome, CommandState finished) {
        return true;
    }
    
    /**
     * Determines if this Handler blocks a newly posted message.
     * @param nMessage
     * @return 
     */
    public boolean blocksMessage(XNetMessage nMessage) {
        return false;
    }
    
    public synchronized boolean addMessage(CommandState msgState) {
        return false;
    }
    
    protected final synchronized void insertMessage(CommandState msg, boolean beforeNow) {
        if (allCommands == null) {
            allCommands = new ArrayList<>(2);
            allCommands.add(getInitialCommand());
        }
        if (beforeNow) {
            if (getCommand().getPhase().passed(Phase.SENT)) {
                throw new IllegalArgumentException("Cannot insert command before " + getCommand());
            }
            allCommands.add(cmdIndex, msg);
        } else {
            allCommands.add(msg);
        }
    }
    
    /**
     * Called to attach to an QueueController.
     * @param q 
     */
    /* package-private */ synchronized void attachQueue(CommandService q) {
        if (actionQueue != null && actionQueue != q) {
            throw new IllegalStateException("Cannot attach twice");
        }
        this.actionQueue = q;
        toPhase(Phase.QUEUED);
    }
    
    /**
     * Determines if the reply can belong to the current command. By default,
     * all replies are accepted.
     * @param msg the message that has been just sent.
     * @param reply the reply which was received.
     * @return 
     */
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        return true;
    }
    
    public boolean checkConcurrentAction(CommandState st, XNetPlusReply reply) {
        return false;
    }

    @Override
    public XNetPlusMessage getMessage() {
        return getInitialCommand().getMessage();
    }
    
    //----------- Trampolines

    protected void markOutcome(ReplyOutcome out, Consumer<XNetPlusReply> c) {
        out.mark(c);
    }
    
    protected boolean toPhase(CommandState s, Phase p) {
        if (!getAllCommands().contains(s)) {
            throw new IllegalArgumentException("Command " + s + " not owned by " + this);
        }
        return s.toPhase(p);
    }

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
}
