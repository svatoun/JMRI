package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;

/**
 * Variant of {@link XNetListener}, which supports {@link XNetPlusMessage} and
 * {@link XNetPlusReply}.
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface XNetPlusListener extends XNetListener {
    public void message(XNetPlusReply reply);
        
    @Override
    public default void message(XNetReply reply) {
        message(XNetPlusReply.create(reply));
    }
    
    public void message(XNetPlusMessage msg);
    
    @Override
    public default void message(XNetMessage msg) {
        message(XNetPlusMessage.create(msg));
    }
    
    public void notifyTimeout(XNetPlusMessage msg);
    
    public default void notifyTimeout(XNetMessage msg) {
        notifyTimeout(XNetPlusMessage.create(msg));
    }

}
