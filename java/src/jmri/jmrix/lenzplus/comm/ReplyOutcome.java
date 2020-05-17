/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import java.util.function.Consumer;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 * Defines the outcome and desired action of the received reply's processing.
 * 
 * @author sdedic
 */
public final class ReplyOutcome {
    private final CommandState state;
    private final XNetPlusReply targetReply;
    private final XNetPlusReply reply;
    
    private Consumer<XNetPlusReply> marker;
    private Throwable exception;
    private boolean complete;
    private boolean additionalReplyRequired;
    
    public static ReplyOutcome finished(XNetPlusReply r) {
        return new ReplyOutcome(r);
    }

    public static ReplyOutcome finished(XNetPlusReply r, Throwable ex) {
        return new ReplyOutcome(r).withError(ex);
    }

    public static ReplyOutcome finished(CommandState state, XNetPlusReply r) {
        return new ReplyOutcome(state, r).finish();
    }

    public ReplyOutcome(CommandState state, XNetPlusReply reply) {
        this.state = state;
        // makes a copy
        this.reply = reply;
        if (state != null) {
            this.targetReply = reply.copy();
        } else {
            this.targetReply = reply;
        }
    }

    public ReplyOutcome(XNetPlusReply reply) {
        this.reply = reply;
        this.targetReply = reply;
        this.state = null;
        complete = true;
    }
    
    ReplyOutcome mark(Consumer<XNetPlusReply> marker) {
        this.marker = marker;
        return this;
    }
    
    public void markConsumed() {
        if (marker != null) {
            marker.accept(reply);
        } else {
            reply.markConsumed();
        }
    }
    
    public ReplyOutcome withError(Throwable ex) {
        if (this.exception != null) {
            this.exception.addSuppressed(ex);
        } else {
            this.exception = ex;
        }
        return this;
    }

    public Throwable getException() {
        return exception;
    }

    public CommandState getState() {
        return state;
    }

    public XNetPlusReply getReply() {
        return reply;
    }

    public XNetPlusReply getTargetReply() {
        return targetReply;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
    
    public ReplyOutcome finish() {
        setComplete(true);
        return this;
    }

    public boolean isAdditionalReplyRequired() {
        return additionalReplyRequired;
    }

    public void setAdditionalReplyRequired(boolean additionalReplyRequired) {
        this.additionalReplyRequired = additionalReplyRequired;
    }
    
    public boolean isSolicited() {
        return state != null;
    }
    
    @Override
    public String toString() {
        return "Outcome[complete: " + complete + ", solicited: " + isSolicited() + ", additional: " 
                + additionalReplyRequired + "]: " + state;
    }
}
