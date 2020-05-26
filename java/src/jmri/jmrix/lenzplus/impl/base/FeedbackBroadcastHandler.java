package jmri.jmrix.lenzplus.impl.base;

import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandService;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyHandler;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class FeedbackBroadcastHandler implements ReplyHandler {
    final CommandService queue;
    
    public FeedbackBroadcastHandler(CommandService q) {
        this.queue = q;
    }
    
    @Override
    public ReplyOutcome process(CommandState cmd, XNetPlusReply reply) {
        reply.feedbacks(true).forEach(f -> {
            int s = f.getTurnoutStatus();
            if (s != -1) {
                queue.expectAccessoryState(f.getAddress(), s);
            }
        });
        return null;
    }
    
}
