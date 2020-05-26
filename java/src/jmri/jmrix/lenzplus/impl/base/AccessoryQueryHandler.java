package jmri.jmrix.lenzplus.impl.base;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class AccessoryQueryHandler extends CommandHandler {

    public AccessoryQueryHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage, target);
        XNetPlusMessage m = commandMessage.getMessage();
        int b = m.getAccessoryQueryBase();
        setLayoutId(b);
        // this is why we have this Handler: will group with accessory on/off commands.
        commandMessage.setCommandGroupKey(b);
    }

    @Override
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        return reply.isFeedbackMessage() && reply.selectTurnoutFeedback(getLayoutId()).isPresent();
    }

    @Override
    public ReplyOutcome processed(CommandState msg, XNetPlusReply reply) {
        return ReplyOutcome.finished(msg, reply);
    }
}
