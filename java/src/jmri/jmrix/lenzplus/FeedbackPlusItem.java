package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.FeedbackItem;
import jmri.jmrix.lenz.XNetReply;

/**
 * Extension of {@link #FeedbackItem} that supports marking as 'consumed'.
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
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

    @Override
    public FeedbackPlusItem pairedAccessoryItem() {
        if (!isAccessory()) {
            return null;
        }
        int a = (number & 0x01) > 0 ? number + 1 : number - 1;
        return new FeedbackPlusItem(reply, a, data);
    }
}
