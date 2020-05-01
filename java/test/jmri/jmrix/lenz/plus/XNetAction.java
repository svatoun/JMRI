/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz.plus;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;

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
public class XNetAction {
    /**
     * The leading message.
     */
    protected final CommandState   command;

    /**
     * The target recipient for the command's outcome.
     */
    private final Reference<XNetListener>  target;

    /**
     * Queue for actions. Must be set before the action reaches
     * {@link Phase#QUEUED} state.
     */
    private /* semifinal */ ActionQueue actionQueue;

    /**
     * Diagnostics: ID of the commanded object in the layout.
     */
    private int layoutId;
    
    public XNetAction(XNetMessage commandMessage, XNetListener target) {
        this.command = new CommandState(commandMessage);
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
    
    public XNetMessage getCommandMessage() {
        return command.getMessage();
    }

    /**
     * The command was/is being sent to XPressNet. It's not assured that the command
     * station has already seen this command, but from this time on, the command
     * station any reply received <b>might be affected</b> by the command.
     * @param msg the command sent.
     */
    protected void sent(CommandState msg) {
    }
    
    protected void processed(CommandState msg) {
    }

    /**
     * The command has been finished. Clean up.
     * @param finsihed finished command
     */
    protected void finished(CommandState finsihed) {
    }
    
    public boolean acceptsMessage(XNetMessage msg) {
        return false;
    }
    
    /* package-private */ synchronized void attachQueue(ActionQueue q) {
        if (actionQueue != null && actionQueue != q) {
            throw new IllegalStateException("Cannot attach twice");
        }
        this.actionQueue = q;
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
