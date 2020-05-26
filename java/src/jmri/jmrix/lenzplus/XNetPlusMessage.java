package jmri.jmrix.lenzplus;

import jmri.Turnout;
import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;

/**
 * An extension of {@link XNetMessage} that holds extended processing info.
 * This extended version allows to refine parameters for sending message without
 * extending the basic send API for each additional flag or feature. Additional 
 * parameters are persisted within the message, and will be acted upon by
 * the TrafficController & co.
 * 
 * @author svatopluk.dedic@gmail.com Copyright(c) 2020
 */
public class XNetPlusMessage extends XNetMessage {
    private volatile int initialDelay;
    private XNetListener replyTarget;
    private Object callerId;
    
    public XNetPlusMessage() {
        super(1);
    }
    
    public XNetPlusMessage(XNetMessage message) {
        super(message);
    }

    public XNetPlusMessage(String s) {
        super(s);
    }
    
    public static XNetPlusMessage create(XNetMessage msg) {
        if (msg instanceof XNetPlusMessage) {
            return (XNetPlusMessage)msg;
        } else {
            return new XNetPlusMessage(msg);
        }
    }
    
    /**
     * Returns true, if the message is delayed.
     * @return true for delayed messages.
     */
    public boolean isDelayed() {
        return initialDelay > 0;
    }
    
    /**
     * Returns message delay, in milliseconds. 0 means the message is to be sent
     * immediately.
     * @return message delay.
     */
    public int getDelay() {
        return initialDelay;
    }
    
    /**
     * Indicates the message should be sent after a delay. 
     * @param millis delay before the message enters the message queue, in milliseconds.
     * @return this instance.
     */
    public XNetPlusMessage delayed(int millis) {
        this.initialDelay = millis;
        return this;
    }
    
    public XNetPlusMessage withCallerId(Object id) {
        this.callerId = id;
        return this;
    }

    public Object getCallerId() {
        return callerId;
    }

    /**
     * Returns the recipient to be informed about message's delivery/processing.
     * @return the recipient instance or {@code null}.
     */
    public XNetListener getReplyTarget() {
        return replyTarget;
    }
    
    /**
     * Specifies the target where the replies should be routed to.
     * @param target target listener
     * @return this instance
     */
    public synchronized XNetPlusMessage replyTo(XNetListener target) {
        this.replyTarget = target;
        return this;
    }
    
    /**
     * Decodes the affected accessory address from an Accessory Operation Request message.
     * Returns {@link Turnout#UNKNOWN} if the message is not an accessory operation one.
     * For Accessories, returns {@link Turnout#CLOSED or Turnout#THROWN} depending on
     * the affected output.
     * 
     * @return {@link Turnout#CLOSED}, {@link Turnout#THROWN} - or {@link Turnout#UNKNOWN} if
     * the message is not accessory operation.
     */
    public int getCommandedTurnoutStatus() {
        if (getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return Turnout.UNKNOWN;
        }
        return (getElement(2) & 0x01) == 0 ? Turnout.CLOSED : Turnout.THROWN;
    }
    
    /**
     * Decodes the desired output state from Accessory Operation Request message.
     * Returns {@code false} for other types of messages. Returns {@code true},
     * if the output should be activated.
     * @return desired output state.
     */
    public boolean getCommandedOutputState() {
        if (getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return false;
        }
        return (getElement(2) & 0x08) != 0;
    }
    
    /**
     * Decodes the target accessory address from the Accessory Operation Request.
     * For other requests, returns {@code -1}.
     * @return Number of the accessory device targetted by the operation, or {@code -1} if
     * not accessory operation request.
     */
    public int getCommandedAccessoryNumber() {
        if (getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return -1;
        }
        int d2 = getElement(2);
        int n = getElement(1) << 2 | ((d2 & 0b0110) >> 1);
        return n + 1;
    }
    
    public int getAccessoryQueryBase() {
        if (getElement(0) != XNetConstants.ACC_INFO_REQ) {
            return -1;
        }
        return getElement(1) * 4 + ((getElement(2) & 0x01) * 2) + 1;
    }
    
    public boolean isAccessoryQuery(int accessoryId) {
        int b = getAccessoryQueryBase();
        int base = getElement(1) * 4 + ((getElement(2) & 0x01) * 2) + 1;
        return accessoryId == base || accessoryId == base +1;
    }
    
    /**
     * Determines if the same accessory address and the same input is used
     * in the other message. Returns {@code false} if this message is not
     * accessory operation request regardless of {@code other} contents.
     * @param other the message to compare
     * @return {@code true}, if and only if both messages are accessory operations
     * and they operate on the same device and that device's same output.
     */
    public boolean isSameAccessoryOutput(XNetPlusMessage other) {
        int n = getCommandedAccessoryNumber();
        return 
               n != -1 && n == other.getCommandedAccessoryNumber() &&
               getCommandedTurnoutStatus() == other.getCommandedTurnoutStatus();
    }
}
