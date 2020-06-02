package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetTrafficController;

/**
 * Listener of XPressNet events, which report completion of an operation. This type of
 * Listener should be used only if an object is interested in completion events. Unlike {@link XNetListener},
 * which reports each message and reply, this one is designed to report final completion status.
 * <p>
 * The listener is informed by {@link XNetTrafficController} by default. In an unfortunate case, that some 
 * code will call the listener directly, using the {@link XNetListener} API methods, 
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface XNetPlusResponseListener extends XNetPlusListener {
    /**
     * Called in case of a completed command.
     * @param msg the completed command message.
     * @param reply the last reply that lead to command's completion.
     */
    public void completed(CompletionStatus s);

    /**
     * Informs the listener, that a concurrent operation may have occurred
     * in the layout. This method is called <b>after</b> {@link #completed} or
     * {@link #failed}. It may be even called after {@link #completed} was
     * called without any indication of concurrency, if a potential conflict
     * is detected after the command completes.
     * <p>
     * The implementation should react adequately, overriding the layout by another
     * command, or query the layout for the actual object's state.
     * @param s completed command
     * @param concurrent conflicting reply
     */
    public default void concurrentLayoutOperation(CompletionStatus s, XNetPlusReply concurrent) {
    }

    /**
     * Informs that an operation failed. There are only two types of failure: timeout ({@link CompletionStatus#isTimeout})
     * and complete rejection by command station. Note that <b>temporarily busy</b> message from the command
     * station is <b>not reported as failure</b>; the command si repeated several times.
     * @param s status of the command.
     */
    public default void failed(CompletionStatus s) {
        XNetPlusMessage msg = s.getCommand();
        if (s.isTimeout()) {
            XNetPlusReply.log.warn("Command {} timed out for target {}", 
                msg.toMonitorString(), this);
            notifyTimeout(msg);
        } else {
            XNetPlusReply reply = s.getReply();
            XNetPlusReply.log.warn("Command {} failed with message {}, for target {}", 
                msg.toMonitorString(), reply.toMonitorString(), this);
            message(reply);
        }
    }
    
    /**
     * Default bridge to ResponseListener. As listeners interested in response statutes
     * usually do not process the outgoing messages in any way, the default implementation
     * is a no-op.
     * @param l 
     */
    public default void message(XNetPlusMessage l) {
    }
    
    /**
     * Provides a default bridge to the ResponseListener. For non-temporary error replies
     * a CompletionStatus is constructed indicating a failure, and {@link #failed} is called.
     * Temporary error messages are ignored. Any other non-broadcast replies are
     * turned to successful CompletionStatus and {@link #completed} is called.
     * @param l the reply to dispatch.
     */
    public default void message(XNetPlusReply l) {
        XNetPlusReply.log.warn("Message delivered through compat-bridge: {}", l);
        if (l.isRetransmittableErrorMsg()) {
            return;
        }
        CompletionStatus s = new CompletionStatus(l.getResponseTo());
        s.addReply(l);
        if (l.isConcurrent()) {
            s.setConcurrentReply(l);
        }
        if (l.isUnsupportedError()) {
            failed(s);
        } else if (!l.isBroadcast()) {
            completed(s.success());
        }
    }
    
    /**
     * Provides a default bridge to the ResponseListener. Constructs a failed
     * CompletionStatus and calls {@link #failed}.
     */
    @Override
    public default void notifyTimeout(XNetPlusMessage msg) {
        XNetPlusReply.log.warn("Timeout delivered through compat-bridge for command: {}", msg);
        CompletionStatus s = new CompletionStatus(msg);
        failed(s);
    }
}
