/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.CommandState.Phase;

/**
 * Base class for actions commanded on XPressNet. 
 * The action is an abstraction over command-responses, because for some commands (i.e. Turnouts)
 * multiple responses are permitted and their effects may be moderated by the command that
 * had caused them: for example, feedback responses carry information for 2 turnouts. One turnout
 * is the just commanded one, the state may cause the other turnout to change KnownState etc -
 * although it is strictly speaking just a confirmation message.
 * <p>
 * Actions will handle that by selectively disabling parts of the reply.
 * 
 * @author sdedic
 */
public class CommandHandler {
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
     * {@link Phase#QUEUED} state.
     */
    private /* semifinal */ QueueController actionQueue;

    /**
     * Diagnostics: ID of the commanded object in the layout.
     */
    private int layoutId;
    
    /**
     * The command currenctly in effect. Initially equals to
     * {@link #command}, but may change if the action sends more 
     * commands.
     */
    private CommandState currentCommand;
    
    public CommandHandler(CommandState commandMessage, XNetListener target) {
        this.command = commandMessage;
        this.currentCommand  = command;
        this.target = new WeakReference<>(target);
        this.layoutId = -1;
        // XXX
        // commandMessage.attachAction(this);
    }
    
    protected void setLayoutId(int id) {
        this.layoutId = id;
    }

    /**
     * Returns ID of the target layout object. -1, if the action
     * is not targetted. The meaning of the number is function-specific.
     * @return layout object's ID, or {@code -1}
     */
    public int getLayoutId() {
        return layoutId;
    }
    
    public XNetListener getTarget() {
        XNetListener t = target.get();
        return t != null ? t : TRASH;
    }
    
    public CommandState getCommand() {
        return command;
    }
    
    public List<CommandState> getAllCommands() {
        return Collections.singletonList(getCommand());
    }
    
    public Phase lastPhase() {
        return command.getPhase();
    }

    /**
     * The command was/is being sent to XPressNet. It's not assured that the command
     * station has already seen this command, but from this time on, the command
     * station any reply received <b>might be affected</b> by the command.
     * @param msg the command sent.
     */
    protected void sent(CommandState msg) {
    }
    
    protected boolean handleMessage(XNetPlusReply m) {
        return false;
    }
    
    /**
     * The command has been processed by the command station, that have sent
     * a reply. The function may be called multiple times for a single
     * CommandState instance, if more replies are identified.
     * 
     * @param msg the command state
     */
    protected void processed(CommandState msg, XNetPlusReply reply) {
    }
    
    protected boolean proceedNext() {
        return true;
    }

    /**
     * The command has been finished. Clean up, issue or schedule another
     * Command.
     * @param finsihed finished command
     */
    protected void finished(CommandState finsihed) {
    }
    
    /**
     * Determines if this Handler blocks a newly posted message.
     * @param nMessage
     * @return 
     */
    public boolean blocksMessage(XNetMessage nMessage) {
        return false;
    }
    
    void addMessage(CommandState msgState) {
        // not used
    }
    
    /**
     * Called to attach to an QueueController.
     * @param q 
     */
    /* package-private */ synchronized void attachQueue(QueueController q) {
        if (actionQueue != null && actionQueue != q) {
            throw new IllegalStateException("Cannot attach twice");
        }
        this.actionQueue = q;
    }
    
    public boolean acceptsReply(XNetPlusReply reply) {
        return false;
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
