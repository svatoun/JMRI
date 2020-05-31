package jmri.jmrix.lenzplus.comm;

import java.util.function.Consumer;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;

/**
 * Defines the outcome and desired action of the received reply's processing.
 * It is produced by {@link CommandHandler#processed} and collects info for 
 * {@link ResponseHandler} so it can either wait for an additional message from 
 * the layout or finish message processing. Other intermediary objects can
 * inspect the CommandHandler's decision and act appropriately.
 * <p>
 * If an exception occurs reply handling in "outside" code (i.e. XNetListeners),
 * the exception should be recorded using {@link #withError}; all errors
 * will be logged centrally.
 * 
 * @author svatopluk.dedic@gmail.com, Copyrigh (c) 2020
 */
public final class ReplyOutcome {
    private final CommandState state;
    private final XNetPlusReply targetReply;
    private final XNetPlusReply reply;
    private boolean targetNotified;
    
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
        if (state != null && reply != null) {
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
    
    public Phase getPhase() {
        return state == null ? Phase.FINISHED : state.getPhase();
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
    
    public XNetPlusMessage getMessage() {
        return state == null ? null : state.getMessage();
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
    
    public boolean isMessageFinished() {
        if (!complete) {
            return false;
        }
        if (state == null) {
            return true;
        }
        Phase p = getPhase();
        return p != Phase.REJECTED;
    }

    public ReplyOutcome reject() {
        setComplete(true);
        if (state != null) {
            state.toPhase(Phase.REJECTED);
        }
        return this;
    }
    
    public ReplyOutcome fail() {
        setComplete(true);
        if (state != null) {
            state.toPhase(Phase.FAILED);
        }
        return this;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
    
    public ReplyOutcome finish() {
        // DO NOT phase the state to FINISH, finish outcomes
        // are created speculatively.
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

    public boolean isTargetNotified() {
        return targetNotified;
    }

    public void markTargetNotified() {
        this.targetNotified = true;
    }

    public Consumer<XNetPlusReply> getMarker() {
        return marker;
    }

    public void setMarker(Consumer<XNetPlusReply> marker) {
        this.marker = marker;
    }
    
    @Override
    public String toString() {
        return "Outcome[complete: " + complete + ", solicited: " + isSolicited() + ", additional: " 
                + additionalReplyRequired + "]: " + state;
    }
}
