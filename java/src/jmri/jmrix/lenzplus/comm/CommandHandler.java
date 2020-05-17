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
 * The CommandHandler also helps to determine, if a command-reply sequence is over,
 * an additional reply must be received, or a reply MAY be received (subject to
 * layout conditions, command station type etc).
 * <o>
 * The call sequence is:
 * <ol>
 * <li>{@link #filterMessage} may be called at any time before message is sent. It should
 * filter out information that would provoke unnecessary state changes on upper layers: the 
 * layout objects already may have changed to the anticipated state, although the slow layout
 * link did not sent them yet. Processing a layout feedback to an earlier command would flip
 * the layout objects back and forth.
 * <li>{@link #sent} when a message is sent out. The Handler should change the
 * shared state since the message's effects can be visible on the layout from now on.
 * For example, effects of Accessory Operation Request can be reported back any time
 * after {@code sent()} is called. {@code sent()} MAY be called multiple times, if the message
 * times out or is temporarily rejected by the command station and is being repeated.
 * <li>{@link #acceptsReply} to determine if the reply is really a response to the
 * Handler's message. Handler is paired to the reply based on time coincidence, initially; 
 * if the Handler disagrees with the assignment, the reply is processed as solicited.
 * <li>{@link #processed} when a reply comes back. The Handler must inspect the reply
 * and produce a {@link ReplyOutcome} that informs about the message-reply exchange state. The
 * infrastructure either waits for an additional reply, or finishes the message.
 * <li>{@link #finished}, if the message-reply round is over. The Handler may generate
 * implied messages, either stand-alone, or chained to the command. Is called exactly once for
 * each sent() message.
 * <li>{@link #checkConcurrentAction} to double-check for concurrent actions on the layout. May be
 * called any time after the message was {@link #sent} to assess possible concurrent operations. The recommended
 * strategy is to request an update after the command message is confirmed/finished, so that
 * JMRI always sees the proper layout state even when conflicting operations are done by multiple
 * devices.
 * </ol>
 * There's a <b>default CommandHandler</b> that is used if no other handler is willing to handle
 * the message. The default handler is table-driven according to XPressNet 3.6 specification.
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
     * for each {@link XNetPlusReply} received and attached to the {@code msg}
     * command. It must determine if the {@code reply} is a sufficient confirmation
     * for the command, or if yet another reply is required. The instructions
     * are returned in a {@link ReplyOutcome} object.
     * <p>
     * <b>Important note:</b> If the implementor <b>marks consumed</b> part of
     * the reply, it is critical that the {@link ReplyOutcome} is created <b>before
     * </b> marking the parts consumed. When created, {@code ReplyOutcome} 
     * <b>clones the reply</b> including the state and that clone will be passed
     * to the target listener(s) when the command finishes. If the consumed bits
     * are set in the reply passed to the target {@link XNetListener}, the listener
     * may fail process the reply properly.
     *
     * @param msg  the command state
     * @param reply the current reply being processed
     * @return decision on how to proceeed next.
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
        ReplyOutcome outcome = new ReplyOutcome(msg, reply);
        if (reply.isBroadcast() && confirmations < 1) {
            outcome.setAdditionalReplyRequired(true);
        } else {
            outcome.finish();
        }
        return outcome;
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
     * 
     * PENDING
    public boolean blocksMessage(XNetMessage nMessage) {
        return false;
    }
     */
    
    /**
     * Called when a new message is about enter the TrafficController's queue 
     * during reply dispatch. It is likely the message is implied by the XNetPlusMessage
     * being currently replied to, and its handler may take a chance to absorb it.
     * <p>
     * If the handler <b>wants to manage</b> the message, it must return {@code true} and
     * incorporate it in its state/message list, e.g. by calling {@link #insertMessage}.
     * <p>
     * If the handler responds {@code false}, the default procedure to find an appropriate
     * {@link CommandHandler} is used.
     * 
     * @param msgState the new message to check
     * @return {@code true}, if the handler is willing to manage the message.
     */
    public synchronized boolean addMessage(CommandState msgState) {
        return false;
    }
    
    /**
     * Incorporates a message in the handler's managed list. The message can be
     * inserted prior to the current message, or at the end of the list, depending
     * on {@code beforeNow} parameters
     * @param msg the new message to insert
     * @param beforeNow {@code true} will insert the message before the current message,
     * even before the initial one. {@code false} will add the message at the end of the list.
     */
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
     * Called to attach to an QueueController. Must be called when the Handler
     * is associated with the {@link XNetPlusMessage}.
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
     * @return {@code true}, if the Handler recognizes the reply.
     */
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        return true;
    }
    
    /**
     * Checks for a possible concurrent operation. Called on handlers of transmitted
     * messages to see if an <b>unsolicited</b> {@code reply} is not an operation
     * done concurrently by another control device attached to the layout, and 
     * handle the interference with JMRI operations.
     * <p>
     * The Handler may take an appropriate action: repeat the command, inform
     * the Layout object, solicit another state info from the layout etc.
     * 
     * @param st the current command
     * @param reply the unsolicited reply from layout
     * @return true, if the reply indicates a concurrent/conflicting operation.
     */
    public boolean checkConcurrentAction(CommandState st, XNetPlusReply reply) {
        return false;
    }

    /**
     * Returns the initial command's message. The handler is always represented by
     * its initial command.
     * @return message of the initial command.
     */
    @Override
    public XNetPlusMessage getMessage() {
        return getInitialCommand().getMessage();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Handler[").append(Integer.toHexString(System.identityHashCode(this))).
                append("]: ").append(getCommand().toString());
        if (getCommand() != getInitialCommand()) {
            sb.append("Initial command: ").
                    append(Integer.toHexString(System.identityHashCode(getInitialCommand())));
        }
        return sb.toString();
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
