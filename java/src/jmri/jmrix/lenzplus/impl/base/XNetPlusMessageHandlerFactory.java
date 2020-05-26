package jmri.jmrix.lenzplus.impl.base;

import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandService;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.MessageHandlerFactory;
import jmri.jmrix.lenzplus.comm.ReplyHandler;
import org.openide.util.lookup.ServiceProvider;

/**
 * Plugs reply handlers to the {@link QueueController}. Currently registers
 * only feedback handler, which processes both solicited and unsolicited
 * feedback messages and broadcasts just in order to update internal accessory
 * cache in the controller.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
@ServiceProvider(path = "xnetplus/base", service = MessageHandlerFactory.class)
public class XNetPlusMessageHandlerFactory implements MessageHandlerFactory {

    @Override
    public CommandHandler createCommandHandler(CommandService srv, CommandState c, XNetListener callback) {
        XNetMessage m = c.getMessage();
        
        switch (m.getElement(0)) {
            case XNetConstants.ACC_OPER_REQ:
                return new AccessoryHandler(c, callback);
            case XNetConstants.ACC_INFO_REQ:
                return new AccessoryQueryHandler(c, callback);
                
            case XNetConstants.PROG_WRITE_REQUEST:
            case XNetConstants.PROG_READ_REQUEST:
                return new ProgModeHandler(c, callback);
        }
        
        return new DefaultHandler(c, callback);
    }

    @Override
    public ReplyHandler createReplyHandler(CommandService srv, CommandState c, XNetPlusReply reply) {
        if (reply.isFeedbackBroadcastMessage()) {
            return new FeedbackBroadcastHandler(srv);
        }
        return null;
    }
    
}
