package jmri.jmrix.lenzplus;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface XNetPlusResponseListener extends XNetPlusListener {
    /**
     * Called in case of a completed command.
     * @param msg the completed command message.
     * @param reply the last reply that lead to command's completion.
     */
    public default void completed(CompletionStatus s) {
       message(s.getReply()); 
    }
    
    public default void concurrentLayoutOperation(CompletionStatus s, XNetPlusReply concurrent) {
    }
    
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
    
    public default void message(XNetPlusMessage l) {}
    
    public default void message(XNetPlusReply l) {}
    
    @Override
    public default void notifyTimeout(XNetPlusMessage msg) {
    }
    
}
