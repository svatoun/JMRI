package jmri.jmrix.lenzplus;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.lenz.FeedbackItem;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of {@link XNetReply}, which provides additional services to 
 * make layout object's writer life easier.
 * <p>
 * There are more possible states with respect to "solicited" state of the reply.
 * Each Reply can be <b>attached</b> to an outgoing command. A broadcast starts always 
 * unsolicited. Any non-broadcast reply received during transmit window of a command starts 
 * as solicited and will be attached to that command. 
 * <p>
 * Before the Reply reaches {@link XNetListner}s, it will be preprocessed with respect
 * to the command in transit, and commands already queued for transmission. During that
 * stage, the reply may be <i>detached</i> from the command, making it unsolicited. Or
 * a Broadcast may be attached and become a solicited response. 
 * <p>
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
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
    
    private boolean broadcast;

    /**
     * Computed solicited status.
     */
    private Boolean solicitedStatus;
    
    /**
     * The reply is an unsolicited possibly concurrent change.
     */
    private XNetPlusMessage concurrentCommand;
    
    private final XNetPlusReply original;
    
    public enum Continuation {
        /**
         * The reply is not attached to any command. 
         */
        UNATTACHED,
        /**
         * The reply was provoked by a command and is completed, no 
         * other reply is expected.
         */
        FINISH,
        
        /**
         * The reply was provoked by a command, and a further possible reply
         * is expected.
         */
        WAIT_ATTAHCED,
    }
    
    XNetPlusReply(String s) {
        super(s);
        super.resetUnsolicited();
        this.original = null;
    }

    public XNetPlusReply() {
        super.resetUnsolicited();
        this.original = null;
    }

    public XNetPlusReply(XNetReply reply) {
        super(reply);
        this.original = null;
        reply.resetUnsolicited();
        // super.isUnsolicited combines several things. All broadcasts are implicitly unsolicited.
        // if we reset an explicit flag, and the reply is not a takeover, it mus be broadcast in order to
        // be unsolicited.
        broadcast = reply.isUnsolicited() && !reply.isThrottleTakenOverMessage();
    }
    
    private XNetPlusReply(XNetPlusReply r) {
        super(r);
        super.resetUnsolicited();
        this.original = r;
        consumed = r.consumed;
        broadcast = r.broadcast;
        responseTo = r.responseTo;
        solicitedStatus = r.solicitedStatus;
    }
    
    public XNetPlusReply(XNetMessage message) {
        super(message);
        super.resetUnsolicited();
        this.original = null;
    }
    
    public static XNetPlusReply create(XNetReply r) {
        if (r instanceof XNetPlusReply) {
            return (XNetPlusReply)r;
        } else {
            return new XNetPlusReply(r);
        }
    }

    /**
     * Selects a feedback for the specified accessory. <b>Onlu returns</b> feedback
     * that was not yet marked as consumed; see {@link #markFeedbackActionConsumed(int)}.
     * @param accessoryNumber the target accessory DCC number
     * @return optional feedback item
     */
    @Override
    public Optional<FeedbackPlusItem> selectTurnoutFeedback(int accessoryNumber) {
        Optional<FeedbackPlusItem> fi =  super.selectTurnoutFeedback(accessoryNumber);
        return fi.filter(f -> !f.isConsumed());
    }

    public Optional<FeedbackPlusItem> selectTurnoutFeedback(int accessoryNumber, boolean allowConsumed) {
        Optional<FeedbackPlusItem> fi =  super.selectTurnoutFeedback(accessoryNumber);
        return fi.filter(f -> allowConsumed || !f.isConsumed());
    }

    @Override
    protected FeedbackPlusItem createFeedbackItem(int n, int d) {
        return new FeedbackPlusItem(this, n, d);
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
        XNetPlusReply.log.debug("Marking consumed accessory: {} on {}", accessoryAddr, this);
        if (!isFeedbackBroadcastMessage()) {
            return markConsumed(CONSUMED_ACTION);
        } else if (isFeedbackMessage()) {
            return markConsumed(getFeedbackConsumedBit(accessoryAddr));
        }
        log.warn("Actions for multiple-entry feedback broadcasts not supported.");
        return false;
    }
    
    /**
     * Determines if a feedback for the given accessory has been consumed.
     * If the message is not a feedback, does not contain the desired feedback, 
     * returns {@code true} to indicate the feedback ought not be processed.
     * @param accessoryAddr
     * @return 
     */
    public boolean isFeedbackActionConsumed(int accessoryAddr) {
        Optional<FeedbackPlusItem> opt = selectTurnoutFeedback(accessoryAddr, true);
        if (opt.isPresent()) {
            return opt.get().isConsumed();
        }
        return false;
    }

    public XNetPlusMessage getResponseTo() {
        return responseTo;
    }
    
    public void setResponseTo(XNetPlusMessage msg) {
        this.responseTo = msg;
    }

    /**
     * Determines if the message is a broadcast. This should be only inspected as a hint,
     * as some LI* interfaces 
     * @return 
     */
    public boolean isBroadcast() {
        return broadcast;
    }
    
    public void setBroadcast() {
        this.broadcast = true;
        super.setUnsolicited();
    }

    /**
     * Resets feedback's unsolicited state, when it is recognized
     * by a command.
     */
    @Override
    public void resetUnsolicited() {
        /// XXX: proc jsem vubec tohle provedl ?
        solicitedStatus = null;
        super.resetUnsolicited();
    }
    
    @Override
    public boolean isUnsolicited() {
        if (solicitedStatus != null) {
            return !solicitedStatus;
        }
        if (super.isUnsolicited()   // the broadcast status
                || this.isThrottleTakenOverMessage()) {
            return true;
        }
        // default state
        return getResponseTo() == null;
    }
    
    public void markSolicited(boolean mark) {
        this.solicitedStatus = mark;
        if (!mark) {
            setResponseTo(null);
        }
    }
    
    public Stream<FeedbackPlusItem> feedbacks() {
        return feedbacks(false);
    }
    
    public Stream<FeedbackPlusItem> feedbacks(boolean allowConsumed) {
        FeedbackIterable it = new FeedbackIterable(this);
        Spliterator<FeedbackPlusItem> spl = Spliterators.spliterator(it.iterator(), it.getSize(), 
                Spliterator.DISTINCT | Spliterator.NONNULL |
                        Spliterator.ORDERED);
        if (allowConsumed) {
            return StreamSupport.stream(spl, false);
        } else {
            return StreamSupport.stream(spl, false).filter(f -> !f.isConsumed());
        }
    }
    
    public Stream<FeedbackPlusItem> allFeedbacks() {
        FeedbackIterable it = new FeedbackIterable(this);
        Spliterator<FeedbackPlusItem> spl = Spliterators.spliterator(it.iterator(), it.getSize(), 
                Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.NONNULL |
                        Spliterator.ORDERED | Spliterator.SUBSIZED);
        return StreamSupport.stream(spl, false);
    }
    
    public boolean feedbackMatchesAccesoryCommand(XNetPlusMessage command) {
        int turnoutId = command.getCommandedAccessoryNumber();
        if (turnoutId == -1) {
            return false;
        }
        return selectTurnoutFeedback(turnoutId).
                map((f) -> 
                    f.getTurnoutStatus() == command.getCommandedTurnoutStatus()
                ).
                orElse(false);
    }
    
    public String toString() {
        if (original == null && !isConsumed()) {
            return super.toString();
        }
        StringBuilder sb = new StringBuilder(super.toString());
        
        boolean d = LoggerFactory.getLogger(AbstractMRReply.class).isDebugEnabled();
        if (d && original != null) {
            sb.append("; cloned from: ").
                    append(Integer.toHexString(System.identityHashCode(original)));
        }
        if (isConsumed()) {
            if (isFeedbackMessage()) {
                sb.append(", consumed FB:");
                int a = getTurnoutMsgAddr(1);
                if (isFeedbackActionConsumed(a)) {
                    sb.append(" ").append(a);
                }
                if (isFeedbackActionConsumed(a + 1)) {
                    sb.append(" ").append(a + 1);
                }
            }
        } else {
            sb.append(", consumed: ").append(Integer.toBinaryString(consumed));
        }
        return sb.toString();
    }

    public boolean isConcurrent() {
        return concurrentCommand != null;
    }

    public XNetPlusReply markConcurrent(XNetPlusMessage concurrentCommand) {
        this.concurrentCommand = concurrentCommand;
        return this;
    }

    private static final class FeedbackIterable implements Iterable<FeedbackPlusItem> {
        private final XNetPlusReply feedbackMesage;

        public FeedbackIterable(XNetPlusReply feedbackMesage) {
            this.feedbackMesage = feedbackMesage;
        }

        public int getSize() {
            return feedbackMesage.getFeedbackMessageItems() * 2;
        }

        @Override
        public Spliterator<FeedbackPlusItem> spliterator() {
            return Spliterators.spliterator(iterator(), getSize(), 
                Spliterator.SIZED | Spliterator.IMMUTABLE | Spliterator.NONNULL | 
                Spliterator.DISTINCT | Spliterator.ORDERED
            );
        }    

        @Override
        public Iterator<FeedbackPlusItem> iterator() {
            return new Iterator<FeedbackPlusItem>() {
                private int cnt = getSize();
                private int index;
                private boolean odd;

                @Override
                public boolean hasNext() {
                    return cnt > 0;
                }

                @Override
                public FeedbackPlusItem next() {
                    if (cnt <= 0) {
                        throw new NoSuchElementException();
                    }
                    cnt--;
                    int n = feedbackMesage.getTurnoutMsgAddr(1);
                    odd = !odd;
                    // warning, reversed !
                    if (odd) {
                        return feedbackMesage.createFeedbackItem(n + 1, feedbackMesage.getElement(2 + index * 2));
                    } else {
                        return feedbackMesage.createFeedbackItem(n, feedbackMesage.getElement(2 + index * 2));
                    }
                }
            };
        }

    }
    
    public <T> T getCommandCallerId() {
        if (responseTo == null) {
            return null;
        } else {
            return (T)responseTo.getCallerId();
        }
    }
    
    public XNetPlusReply copy() {
        return new XNetPlusReply(this);
    }

    static final Logger log = LoggerFactory.getLogger(XNetPlusReply.class);
}
