package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 * ReplyHandlers process <b>all</b> replies received from the layout. They can be
 * used to implement filters,
 * 
 * ReplyHandler's
 * {@link #intercept} is called before the associated {@link CommandHandler} is
 * asked to accept the packet. The ReplyHandler may break the connection from the 
 * reply to the associated message, which makes the reply <b>unsolicited</b>.
 * It can also overtake the handling of the reply - the associated CommandHandler
 * will be skipped. Multiple ReplyHandlers can process the reply. A handler may
 * terminate the processing - the following ReplyHandlders will be skipped.
 * <p>
 * The handler may return {@code null}, indicating no decision; next handler will
 * be called. The handler may produce a {@link ReplyOutcome} as a result, but
 * with a limited semantic compared to {@link CommandHandler#process}:
 * <ul>
 * <li>if {@link ReplyOutcome#isMessageFinished()} will be true, the associated
 * message processing will finish as if the {@link CommandHandler#process} returned
 * that outcome. Normal completion callbacks on the message will be called.
 * <li> if {@link ReplyOutcome#isComplete()} will be true, the rest of ReplyHandlers
 * will be skipped.
 * </ul>
 * <p>
 * The {@link #process} is called for <b>unsolicited messages</b> and also for
 * <b>solicited messages</b> once the main {@link CommandHandler#processed} has executed.
 * {@code process()} cannot terminate the message processing; both {@link ReplyOutcome#isMessageFinished()}
 * and {@link ReplyOutcome#isComplete()} will just skip the rest of ReplyHandlers.
 * <p>
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface ReplyHandler {
    public default ReplyOutcome intercept(CommandState s, XNetPlusReply reply) {
        return null;
    }
    
    public ReplyOutcome process(CommandState s, XNetPlusReply reply);
}
