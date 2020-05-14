/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;

/**
 *
 * @author sdedic
 */
public interface XNetPlusResponseListener extends XNetListener {
    public void completed(XNetPlusMessage msg, XNetPlusReply reply);
    
    public void message(XNetPlusReply reply);
        
    public default void message(XNetReply reply) {
        message(XNetPlusReply.create(reply));
    }
    
    @Override
    public default void message(XNetMessage msg) {
    }

    @Override
    public default void notifyTimeout(XNetMessage msg) {
    }
    
}
