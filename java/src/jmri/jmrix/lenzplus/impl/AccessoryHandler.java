/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.FeedbackPlusItem;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class AccessoryHandler extends CommandHandler {
    private final int desiredState;
    private boolean recheckNeeded;
    
    /**
     * Will contain the "off" command, once the initial accessory command
     * will be confirmed. Initially {@code null}.
     */
    private volatile CommandState offCommand;
    
    public AccessoryHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage, target);
        XNetPlusMessage msg = commandMessage.getMessage();
        int n = msg.getCommandedAccessoryNumber();
        if (n == -1) {
            throw new IllegalStateException();
        }
        setLayoutId(n);
        desiredState = msg.getCommandedTurnoutStatus();
    }
    
    boolean isRecheckNeeded() {
        return recheckNeeded;
    }

    /**
     * If the preprocess is a feedback, and contains a feedback for this
     * accessory, the feedback should not be processed at this moment, since the
     * accessory will change.
     *
     * @param m
     * @return 
     */
    @Override
    public boolean filterMessage(XNetPlusReply m) {
        Optional<FeedbackPlusItem> item = m.selectTurnoutFeedback(getLayoutId());
        if (!item.isPresent()) {
            LOG.debug("Not feedback, or no matching item");
            return false;
        }
        FeedbackPlusItem f = item.get();
        int st = f.getTurnoutStatus();
        if (st == -1) {
            return false;
        }
        int expected = getQueue().getAccessoryState(getLayoutId());
        LOG.debug("* Filtered out {}, expected: {}", f, expected);
        if (st != expected) {
            this.recheckNeeded = true;
        }
        f.consume();
        return true;
    }
    
    private boolean isMatchingOffCommand(XNetPlusMessage msg) {
        return !msg.getCommandedOutputState() &&
                getInitialCommand().getMessage().isSameAccessoryOutput(msg);
    }
    
    @Override
    public synchronized boolean addMessage(CommandState msgState) {
        XNetPlusMessage msg = msgState.getMessage();
        if (offCommand == null && isMatchingOffCommand(msg)) {
            offCommand = msgState;
            insertMessage(msgState, false);
            return true;
        }
        return false;
    }

    @Override
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        // accept OK in any state:
        if (reply.isOkMessage()) {
            return getCommand().getOkReceived() == 0;
        }
        if (offCommand != null) {
            return false;
        }
        // accept feedback preprocess for the initial command:
        return getCommand().getStateReceived() == 0 &&
               reply.feedbackMatchesAccesoryCommand(msg);
    }
    
    protected void postRecheck() {
        LOG.debug("Posting status re-check from layout for {}", getLayoutId());
        getQueue().requestAccessoryStatus(getLayoutId());
    }

    protected CommandState getOffCommand() {
        return offCommand;
    }

    @Override
    public void sent(CommandState msg) {
        if (getCommand() == offCommand) {
            // no action
            return;
        }
        LOG.debug("Recording expected state for {}: {}", getLayoutId(), desiredState);
        getQueue().expectAccessoryState(getLayoutId(), desiredState);
    }
    
    @Override
    public ReplyOutcome processed(CommandState msg, XNetPlusReply reply) {
        if (getOffCommand() == msg) {
            LOG.debug("OFF command was accepted, finishing: {}, {}", msg, reply);
            if (!reply.isOkMessage()) {
                LOG.error("Unexpected reply to OFF message: {}", reply);
            }
            return ReplyOutcome.finished(command, reply);
        } else {
            super.processed(msg, reply);
            ReplyOutcome out = new ReplyOutcome(command, reply);
            
            if (reply.isFeedbackMessage()) {
                // count a non-broadcast feedback as a OK message; there won't be 
                // anything else.
                if (!reply.isBroadcast()) {
                    command.addOkMessage();
                }
                markOutcome(out, r -> 
                    r.selectTurnoutFeedback(getLayoutId()).ifPresent(FeedbackPlusItem::consume)
                );
            }
            if (command.getOkReceived() == 0) {
                out.setAdditionalReplyRequired(true);
                return out;
            } else if (command.getStateReceived() > 0) {
                out.setComplete(true);
            }
            LOG.debug("Accessory processed command {}, outcome {}", command, out);
            return out;
        }
    }

    @Override
    public boolean finished(ReplyOutcome outcome, CommandState finished) {
        super.finished(outcome, finished);
        if (finished == offCommand) {
            // maybe trigger a re-check
            if (recheckNeeded) {
                postRecheck();
            }
            return true;
        }
        // generate OFF command, and attach to this handler.
        XNetPlusMessage msg = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(
                        getLayoutId(), 
                        desiredState == Turnout.CLOSED,
                        desiredState == Turnout.THROWN,
                        false
                ));
        LOG.debug("Generating OFF command: {}", msg);
        insertMessage(offCommand = getQueue().send(this, 
                msg.delayed(30).asPriority(true),
                null), false);
        return false;
    }

    @Override
    public boolean checkConcurrentAction(CommandState st, XNetPlusReply reply) {
        if (!reply.isFeedbackMessage() || st != getInitialCommand()) {
            return false;
        }
        LOG.debug("Inspect concurrency between {} and {}", getInitialCommand(), st);
        XNetPlusMessage m = st.getMessage();
        if (m.getCommandedOutputState()) {
            Optional<FeedbackPlusItem> f = reply.selectTurnoutFeedback(getLayoutId());
            if (f.isPresent() && !reply.feedbackMatchesAccesoryCommand(m)) {
                postRecheck();
                return true;
            };
        }
        return false;
    }
    
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Accessory: ");
        sb.append(getInitialCommand());
        if (offCommand != null) {
            sb.append(", OFF: ").append(offCommand);
        }
        return sb.toString();
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(AccessoryHandler.class);
}

