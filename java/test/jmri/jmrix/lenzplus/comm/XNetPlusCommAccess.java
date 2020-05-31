package jmri.jmrix.lenzplus.comm;

import java.util.List;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;

/**
 * Provides access to package-private methods for test classes.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusCommAccess {
    public static boolean toPhase(CommandState s, Phase p) {
        return s.toPhase(p);
    }
    
    public static CommandState state(CommandService cs, XNetPlusMessage m) {
        return ((QueueController)cs).state(m);
    }
    
    public static void attachHandler(CommandState s, CommandHandler h) {
        s.attachHandler(h);
    }
    
    public static void attachQueue(CommandHandler h, CommandService q) {
        h.attachQueue(q);
    }

    public static boolean advance(CommandHandler h) {
        return h.advance();
    }
    
    public static ReplyOutcome callPreprocess(QueueController c, List<ReplyHandler> replyHandlers, CommandState s, XNetPlusReply r) {
        return c.preprocess(replyHandlers, s, r);
    }
}
