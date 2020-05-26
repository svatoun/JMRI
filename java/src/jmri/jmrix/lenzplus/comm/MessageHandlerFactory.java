package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface MessageHandlerFactory {
    public CommandHandler createCommandHandler(CommandService srv, CommandState c, XNetListener callback);
    public ReplyHandler createReplyHandler(CommandService srv, CommandState c, XNetPlusReply r);
}
