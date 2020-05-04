/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import jmri.jmrix.lenz.FeedbackItem;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of {@link XNetReply}, which provides additional services to 
 * make layout object's writer life easier.
 * 
 * @author sdedic
 */
public class XNetPlusReply extends XNetReply {
    /**
     * An action has been already taken based on this message.
     */
    public static final int CONSUMED_ACTION = 0x01;
    
    /**
     * Special case for actions provoked by feedbacks, on their even turnouts.
     */
    public static final int CONSUMED_ACTION_EVEN = 0x02;
    
    /**
     * Records that a message was already processed. Individual bits
     * represent various message parts.
     */
    // TBD: there will be other parts, for feedbacks, feedback broadcasts
    private int consumed;
    
    private XNetPlusMessage responseTo;
    
    XNetPlusReply(String s) {
        super(s);
    }

    public XNetPlusReply() {
    }

    public XNetPlusReply(XNetReply reply) {
        super(reply);
    }

    public XNetPlusReply(XNetMessage message) {
        super(message);
    }
    
    /**
     * Determines if the message has been already processed / consumed. Consumed messages
     * should be processed only with a great care. They should be mostly ignored,
     * or used just for informative purposes.
     * @return true, if the message was consumed.
     */
    public boolean isConsumed() {
        return consumed > 0;
    }
    
    /**
     * Checks what part of the message were consumed. The selector values are
     * message and operation specific. Currently used to mark replies to
     * accessory request output off commands, so they do not produce multiple
     * outgoing messages.
     * <p>
     * Might be used to selectively ignore state bis in feedback messages as well.
     * @param mask selector
     * @return true, if the message part was consumed.
     */
    public boolean isConsumed(int mask) {
        return (consumed & mask) > 0;
    }
    
    /**
     * Returns an appropriate 'action consumed' bit. For other than single feedbacks,
     * it always returns {@link #CONSUMED_ACTION}. For single item feedbacks, it returns
     * {@link #CONSUMED_ACTION_EVEN} for even accessory addresses.
     * 
     * @param accessoryAddr accessory address.
     * @return bit for {@link #isConsumed} or {@link #markConsumed(int)}.
     */
    public int getFeedbackConsumedBit(int accessoryAddr) {
        if (!isFeedbackMessage()) {
            return CONSUMED_ACTION;
        }
        return (accessoryAddr & 0x01) != 0 ? CONSUMED_ACTION : CONSUMED_ACTION_EVEN;
    }

    /**
     * Marks the reply as fully consumed.
     */
    public void markConsumed() {
        this.consumed = 0xff;
    }
    
    /**
     * Marks certain parts of message as consumed. The selector values are
     * message-specific, must be within range 0..255. If the message consumed
     * state did not change (it was already consumed), returns true. Can be
     * used in {@code if} condition to check whether to proceed with an
     * action, and mark the reply consumed by a single call.
     * <p>
     * <b>Note:</b> If processing a feedback, consider using {@link #markFeedbackActionConsumed}.
     * Feedback is delivered to both devices in the feedback pair equally, using the
     * specialized method ensures that one device does not lock out the other.
     * @param selector  bits to check/test.
     * @return true, if all aspects were already consumed.
     */
    public boolean markConsumed(int selector) {
        int c = this.consumed;
        assert selector > 0 && selector < 0x100;
        this.consumed |= selector;
        return c == consumed;
    }
    
    /**
     * Special variant, which works better with feedback messages.
     * Feedbacks are processed by both turnouts in the pair; each of them eventually
     * takes an action, and they should not block each other. This method will
     * use {@link #CONSUMED_ACTION} or {@link #CONSUMED_ACTION_EVEN} depending
     * on accessory address. It will do nothing and return {@code false} on
     * feedback broadcasts with more items - this may change in the future.
     * <p>
     * For general description, see {@link #markConsumed(int)}.
     * @param accessoryAddr the turnout number.
     * @return true, if consumed.
     */
    public boolean markFeedbackActionConsumed(int accessoryAddr) {
        if (!isFeedbackBroadcastMessage()) {
            return markConsumed(CONSUMED_ACTION);
        } else if (isFeedbackMessage()) {
            return markConsumed(getFeedbackConsumedBit(accessoryAddr));
        }
        log.warn("Actions for multiple-entry feedback broadcasts not supported.");
        return false;
    }

    public XNetPlusMessage getResponseTo() {
        return responseTo;
    }
    
    void setResponseTo(XNetPlusMessage msg) {
        this.responseTo = msg;
    }

    private static final class FeedbackIterable implements Iterable<FeedbackItem> {
        private final XNetPlusReply feedbackMesage;

        public FeedbackIterable(XNetPlusReply feedbackMesage) {
            this.feedbackMesage = feedbackMesage;
        }

        public int getSize() {
            return feedbackMesage.getFeedbackMessageItems();
        }

        @Override
        public Spliterator<FeedbackItem> spliterator() {
            return Spliterators.spliterator(iterator(), getSize(), 
                Spliterator.SIZED | Spliterator.IMMUTABLE | Spliterator.NONNULL | 
                Spliterator.DISTINCT | Spliterator.ORDERED
            );
        }    

        @Override
        public Iterator<FeedbackItem> iterator() {
            return new Iterator<FeedbackItem>() {
                private int cnt = getSize();
                private int index;
                private boolean odd;

                @Override
                public boolean hasNext() {
                    return cnt > 0;
                }

                @Override
                public FeedbackItem next() {
                    if (cnt <= 0) {
                        throw new NoSuchElementException();
                    }
                    cnt--;
                    int n = feedbackMesage.getTurnoutMsgAddr(index);
                    odd = !odd;
                    // warning, reversed !
                    if (odd) {
                        return feedbackMesage.createFeedbackItem(n + 1, feedbackMesage.getElement(1 + index * 2));
                    } else {
                        return feedbackMesage.createFeedbackItem(n, feedbackMesage.getElement(1 + index * 2));
                    }
                }
            };
        }

    }

    private static final Logger log = LoggerFactory.getLogger(XNetPlusReply.class);
}
