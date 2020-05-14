/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.FeedbackItem;
import jmri.jmrix.lenz.XNetReply;

/**
 *
 * @author sdedic
 */
public class FeedbackPlusItem extends FeedbackItem {

    public FeedbackPlusItem(XNetReply reply, int number, int data) {
        super(reply, number, data);
    }
    
    public void consume() {
        ((XNetPlusReply)reply).markFeedbackActionConsumed(number);
    }
    
    public boolean isConsumed() {
        XNetPlusReply xr = ((XNetPlusReply)reply);
        return xr.isConsumed(xr.getFeedbackConsumedBit(getAddress()));
    }
    
    @Override
    public String toString() {
        String s = super.toString();
        return isConsumed() ?
                s + ", CONSUMED" :
                s;
    }
}
