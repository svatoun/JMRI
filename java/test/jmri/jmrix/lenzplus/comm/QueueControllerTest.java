package jmri.jmrix.lenzplus.comm;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.CompletionStatus;
import jmri.jmrix.lenzplus.JUnitTestBase;
import jmri.jmrix.lenzplus.XNetAdapter;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.XNetPlusResponseListener;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import org.assertj.core.api.Assertions;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.openide.util.Lookup;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class QueueControllerTest extends JUnitTestBase implements TrafficController {
    TestQueueController controller = new TestQueueController(this);
    
    public QueueControllerTest() {
    }

    @Override
    public Lookup getLookup() {
        return Lookup.EMPTY;
    }
    
    private ReplyOutcome noException(ReplyOutcome o) throws Exception {
        if (o == null) {
            return null;
        }
        Throwable e = o.getException();
        if (e instanceof Exception) {
            throw (Exception)e;
        } else if (e != null) {
            Assertions.fail("Unexpected error", e);
        }
        return o;
    }

    private ReplyOutcome preprocess(XNetPlusReply r) throws Exception {
        XNetPlusMessage m = r.getResponseTo();
        CommandState s = controller.state(m);
        return noException(controller.preprocess(Collections.emptyList(), s, r));
    }

    /**
     * Checks that originally unsolicited message is processed correctly
     */
    @Test
    public void testPreprocessUnsolicitedMessage() throws Exception {
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        ReplyOutcome o = preprocess(reply);
        assertNotNull(o);
        assertTrue(reply.isUnsolicited());
    }

    /**
     * Checks that data in an unsolicited message will be filtered during preprocess.
     */
    @Test
    public void testFilterUnsolicitedMessage() throws Exception {
        XNetPlusMessage futureMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        
        // make it queued
        CommandState s = controller.send(null, futureMsg, null);
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        ReplyOutcome o = preprocess(reply);
        assertNotNull(o);
        assertTrue(reply.isUnsolicited());
        // check the filtering: 
        assertFalse(reply.selectTurnoutFeedback(5).isPresent());
    }
    
    private ReplyOutcome processReplyNoException(XNetPlusReply reply, Runnable callback) throws Exception {
        return noException(controller.processReply2(reply, callback));
    }

    /**
     * Checks that data in an unsolicited message will be filtered during preprocess.
     */
    @Test
    public void testPreprocessConcurrentMessage() throws Exception {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        
        XNetPlusReply okReply = XNetPlusAccess.createReply("01 04 05");
        okReply.setResponseTo(jmriMsg);
        
        // process the message and leave it in the transmitted
        CommandState s = controller.send(null, jmriMsg, null);
        controller.pollMessage();
        controller.message(jmriMsg);
        preprocess(okReply);
        ReplyOutcome o = processReplyNoException(okReply, () -> {});
        controller.replyFinished(o);
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        ReplyOutcome o2 = preprocess(reply);
        assertNotNull(o2);
        assertTrue(reply.isUnsolicited());
        // check the filtering: 
        assertFalse(reply.selectTurnoutFeedback(5).isPresent());
    }

    /**
     * Checks that a solicited message rejected by the handler becomes
     * unsolicited.
     */
    @Test
    public void testPreprocessRejectedByHandler() throws Exception {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(7, true, false, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        reply.setResponseTo(jmriMsg);
        
        assertFalse(reply.isUnsolicited());
        
        // process the message and leave it in the transmitted
        CommandState s = controller.send(null, jmriMsg, null);
        controller.pollMessage();
        controller.message(jmriMsg);
        ReplyOutcome o = preprocess(reply);
        assertNotNull(o);
        assertTrue(o.isComplete());
        assertTrue(o.isMessageFinished());
        assertTrue(reply.isUnsolicited());
    }

    /**
     * Checks that a message rejected by a retransmittable error
     * will produce a completed, but unfinished outcome.
     */
    @Test
    public void testPreprocessRetransmittableError() throws Exception {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(7, true, false, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("61 81 e0");
        reply.setResponseTo(jmriMsg);
        
        assertFalse(reply.isUnsolicited());

        // process the message and leave it in the transmitted
        CommandState s = controller.send(null, jmriMsg, null);
        controller.pollMessage();
        controller.message(jmriMsg);
        ReplyOutcome o = preprocess(reply);
        assertNotNull(o);
        assertTrue(o.isComplete());
        assertFalse(o.isMessageFinished());
        assertSame(Phase.REJECTED, s.getPhase());
    }

    /**
     * Checks that an unsupported error will complete the command immediately.
     */
    @Test
    public void testPreprocessUnsupportedError() throws Exception {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(7, true, false, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("61 82 e3");
        reply.setResponseTo(jmriMsg);
        
        assertFalse(reply.isUnsolicited());

        // process the message and leave it in the transmitted
        CommandState s = controller.send(null, jmriMsg, null);
        controller.pollMessage();
        controller.message(jmriMsg);
        ReplyOutcome o = preprocess(reply);
        assertNotNull(o);
        assertTrue(o.isComplete());
        assertTrue(o.isMessageFinished());
        assertSame(Phase.FAILED, s.getPhase());
    }

    /**
     * Checks a basic request - reply through the Default (generic) CommandHandler.
     */
    @Test
    public void testBasicStationStartup() throws Exception {
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());

        controller.send(msg, null);
        
        // pretend we really send something:
        XNetPlusMessage polled = controller.pollMessage();
        assertSame(msg, polled);
        
        // and as a traffic controller, send:
        controller.message(msg);
        
        // and pretend we've received something:
        XNetPlusReply reply = XNetPlusAccess.createReply("63 21 36 00 74");
        reply.setResponseTo(msg);
        
        ReplyOutcome oc = processReplyNoException(reply, () -> {});
        controller.replyFinished(oc);
        
        CommandState st = controller.state(msg);
        assertSame(Phase.FINISHED, st.getPhase());
        
        msg = XNetPlusMessage.create(new XNetMessage("21 24 05"));
        
        // pretend we really send something:
        controller.send(msg, null);
        polled = controller.pollMessage();
        controller.message(polled);

        reply = XNetPlusAccess.createReply("62 22 06 46");
        reply.setResponseTo(msg);

        oc = processReplyNoException(reply, () -> {});
        controller.replyFinished(oc);

        st = controller.state(msg);
        assertSame(Phase.FINISHED, st.getPhase());
    }

    /**
     * Checks that "transmitted" messages is filled when a message is reported
     * to be sent, and that messages remain there even they are finished.
     */
    @Test
    public void testGetTransmittedMessages() throws Exception {
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());
        
        // not transmitted yet:
        assertTrue(controller.getTransmittedMessages().isEmpty());

        controller.send(msg, null);
        
        CommandState st = controller.state(msg);
        
        // pretend we really send something:
        XNetPlusMessage polled = controller.pollMessage();
        assertSame(msg, polled);
        assertTrue(controller.getTransmittedMessages().isEmpty());
        
        // and as a traffic controller, send:
        controller.message(msg);
        assertFalse(controller.getTransmittedMessages().isEmpty());
        
        // and pretend we've received something:
        XNetPlusReply reply = XNetPlusAccess.createReply("63 21 36 00 74");
        reply.setResponseTo(msg);
        
        ReplyOutcome oc = processReplyNoException(reply, () -> {});
        controller.replyFinished(oc);
        
        // still remains in the transmit queue
        assertFalse(controller.getTransmittedMessages().isEmpty());
     
        Thread.sleep(controller.getConcurrentTimeAfter());
        
        controller.disableExpiration = false;
        // receive some message to expunge obsolete messages from the queue
        reply = XNetPlusAccess.createReply("42 01 01 44");
        processReplyNoException(reply, () -> {});
        
        assertTrue(controller.getTransmittedMessages().isEmpty());
        // but is not expired:
        assertSame(Phase.FINISHED, st.getPhase());
    }
    
    XNetReply targetReply;

    @Test
    public void testSendTuroutMessageWithOff() throws Exception {
        XNetPlusMessage start = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(10, true, false, true)
        );
        
        controller.send(start, new XNetPlusResponseListener() {
            @Override
            public void completed(CompletionStatus s) {
                targetReply = s.getReply();
            }
        });
        
        CommandState st = controller.state(start);
        CommandHandler h = st.getHandler();
        
        assertSame(start,  controller.pollMessage());
        controller.message(start);
        
        XNetPlusReply reply = XNetPlusAccess.createReply("01 04 05");
        reply.setResponseTo(start);
        ReplyOutcome o = processReplyNoException(reply, () -> {});
        
        assertNull(targetReply);
        
        assertFalse(o.isComplete());
        assertFalse(o.isAdditionalReplyRequired());
        
        controller.replyFinished(o);
        
        // still nothing, since the delayed command is sent.
        assertNotSame(start, h.getCommand());
        Future<?> f = controller.getFutureCommand(h.getCommand());
        f.get(200, TimeUnit.MILLISECONDS);
        
        XNetPlusMessage off = controller.pollMessage();
        assertNotNull(off);
        controller.message(off);
        
        reply = XNetPlusAccess.createReply("01 04 05");
        reply.setResponseTo(off);
        
        o = processReplyNoException(reply, () -> {});
        
        assertTrue(o.isComplete());
        assertFalse(o.isAdditionalReplyRequired());
        
        controller.replyFinished(o);
        
        assertEquals(reply, targetReply);
    }
    
    /**
     * Send a simple query-response command, and check the target listener
     * was notified after the command finished.
     */
    @Test
    public void testSendSimpleCommand() throws Exception {
        XNetPlusMessage query = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());
        
        controller.send(query, new XNetPlusResponseListener() {
            @Override
            public void completed(CompletionStatus s) {
                targetReply = s.getReply();
            }
        });
        controller.pollMessage();
        controller.message(query);

        XNetPlusReply reply = XNetPlusAccess.createReply("63 21 36 00 74");
        reply.setResponseTo(query);
        ReplyOutcome o = processReplyNoException(reply, () -> {});
        assertTrue(o.isComplete());
        assertNull(targetReply);
        controller.replyFinished(o);
        
        assertEquals(reply, targetReply);
    }

    /**
     * Pretends the message was rejected with busy or data fail error
     * by the command station. Observe how the message was processed.
     */
    @Test
    public void testReplay() throws Exception {
        XNetPlusMessage start = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(10, true, false, true)
        );
        
        controller.send(start, new XNetPlusResponseListener() {
            @Override
            public void completed(CompletionStatus s) {
                targetReply = s.getReply();
            }
        });
        
        CommandState st = controller.state(start);
        CommandHandler h = st.getHandler();
        
        assertSame(start,  controller.pollMessage());
        controller.message(start);
        
        // pretent that a reject came:
        XNetPlusReply reject = XNetPlusAccess.createReply("61 81 e0");
        reject.setResponseTo(start);
        ReplyOutcome o = processReplyNoException(reject, () -> {});

        // the processing should finish, but the message is not even confirmed:
        assertTrue(o.isComplete());
        assertFalse(o.isMessageFinished());
        assertFalse(st.getPhase().isConfirmed());
        
        controller.replyFinished(o);
        // no accessory should be changed (yet)
        assertEquals(Turnout.UNKNOWN, controller.getAccessoryState(10));
        assertFalse(st.getPhase().isConfirmed());
        
        // resend:
        controller.replay(start);
        assertSame(start,  controller.pollMessage());
        controller.message(start);
        
        assertFalse(st.getPhase().isConfirmed());
        assertTrue(st.getPhase().isActive());
        
        XNetPlusReply reply = XNetPlusAccess.createReply("01 04 05");
        reply.setResponseTo(start);
        o = processReplyNoException(reply, () -> {});
        
        assertNull(targetReply);
        
        assertFalse(o.isComplete());
        assertFalse(o.isAdditionalReplyRequired());
        
        controller.replyFinished(o);
        
        Future<?> f = controller.getFutureCommand(h.getCommand());
        f.get(200, TimeUnit.MILLISECONDS);
        XNetPlusMessage off = controller.pollMessage();

        // send the OFF message
        controller.message(off);
        
        // but reject it:
        
        
        reply = XNetPlusAccess.createReply("01 04 05");
        reply.setResponseTo(off);
        
        o = processReplyNoException(reply, () -> {});
        
        assertTrue(o.isComplete());
        assertFalse(o.isAdditionalReplyRequired());
        
        controller.replyFinished(o);
        
        assertEquals(reply, targetReply);
    }

    private CommandHandler callForMessage(XNetMessage msg) {
        XNetPlusMessage m = XNetPlusMessage.create(msg);
        CommandState s = new CommandState(m);
        return controller.forMessage(s, null);
    }

    @Test
    public void testForMessage() {
        XNetPlusReply okReply = XNetPlusAccess.createReply("01 04 05");
        XNetPlusReply fbReply = XNetPlusAccess.createReply("42 01 01 44");
        XNetPlusReply fbReply2 = XNetPlusAccess.createReply("42 02 01 44");
        XNetPlusReply progModeReply = XNetPlusAccess.createReply("61 02 63");
        CommandHandler h;
        
        h = callForMessage(XNetMessage.getTurnoutCommandMsg(5, true, false, true));
        assertNotNull(h);
        assertTrue(h.acceptsReply(h.getMessage(), okReply));
        assertTrue(h.acceptsReply(h.getMessage(), fbReply));
        assertFalse(h.acceptsReply(h.getMessage(), fbReply2));
        assertFalse(h.acceptsReply(h.getMessage(), progModeReply));
        
        h = callForMessage(XNetMessage.getWriteDirectCVMsg(300, 10));
        assertNotNull(h);
        assertFalse(h.acceptsReply(h.getMessage(), fbReply));
        assertFalse(h.acceptsReply(h.getMessage(), fbReply2));
        assertTrue(h.acceptsReply(h.getMessage(), okReply));
        assertTrue(h.acceptsReply(h.getMessage(), progModeReply));
        
        h = callForMessage(XNetMessage.getLocomotiveInfoRequestMsg(11));
        assertNotNull(h);
        assertFalse(h.acceptsReply(h.getMessage(), fbReply));
        assertFalse(h.acceptsReply(h.getMessage(), fbReply2));
        assertFalse(h.acceptsReply(h.getMessage(), okReply));
        assertFalse(h.acceptsReply(h.getMessage(), progModeReply));
    }

    /**
     * ProcessUnsolicited must make the message unsolicited
     */
    @Test
    public void testProcessUnsolicited() {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(7, true, false, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        reply.setResponseTo(jmriMsg);
        
        assertFalse(reply.isUnsolicited());
        
        controller.processUnsolicited(Collections.emptyList(), null, reply);
        assertTrue(reply.isUnsolicited());
    }

    @Test
    public void testFilterThroughQueued() {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        reply.setResponseTo(jmriMsg);
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        
        CommandState s = controller.send(null, jmriMsg, null);
        
        controller.filterThroughQueued(reply, null);
        assertFalse(reply.selectTurnoutFeedback(5).isPresent());
    }

    @Test
    public void testFilterQueuedConcurrent() {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, false, true, true)
        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");

        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        assertFalse(reply.feedbackMatchesAccesoryCommand(jmriMsg));
        
        CommandState s = controller.send(null, jmriMsg, null);
        
        controller.filterThroughQueued(reply, null);
        
        assertSame(reply, s.getConcurrentReply());
    }
    
    class L1 implements XNetListener {
        XNetReply   okMessage;
        XNetMessage timeoutMessage;
        
        @Override
        public void message(XNetReply msg) {
            okMessage = msg;
        }

        @Override
        public void message(XNetMessage msg) {
        }

        @Override
        public void notifyTimeout(XNetMessage msg) {
            timeoutMessage = msg;
        }
    }

    class L2 implements XNetPlusResponseListener {
        XNetReply   okMessage;
        XNetReply   failMessage;
        XNetMessage timeoutMessage;
        XNetMessage op;
        List<XNetPlusReply> allReplies;
        XNetReply   concurrentReply;
        
        @Override
        public void completed(CompletionStatus s) {
            okMessage = s.getReply();
            op = s.getCommand();
            allReplies = s.getAllReplies();
            concurrentReply = s.getConcurrentReply();
        }

        @Override
        public void failed(CompletionStatus s) {
            failMessage = s.getReply();
            op = s.getCommand();
        }

        @Override
        public void notifyTimeout(XNetMessage msg) {
            timeoutMessage = msg;
        }
    }
    
    private ReplyOutcome simulateMessage(XNetListener l, XNetPlusMessage jmriMsg) throws Exception {
        return simulateMessage(l, false, jmriMsg);
    }
    
    private ReplyOutcome simulateMessage(XNetListener l, boolean insertExtra, XNetPlusMessage jmriMsg) throws Exception {
//        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
//            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
//        );
        
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        reply.setResponseTo(jmriMsg);
        
        controller.send(jmriMsg, l);
        controller.pollMessage();
        controller.message(jmriMsg);
        ReplyOutcome out = processReplyNoException(reply, () -> {});
        controller.replyFinished(out);
        
        CommandState st = controller.state(jmriMsg);
        CommandHandler h = st.getHandler();
        if (!h.getPhase().isFinal()) {
            reply = XNetPlusAccess.createReply("01 04 05");
            reply.setResponseTo(st.getHandler().getCommand().getMessage());

            XNetPlusMessage m = controller.pollMessage();
            if (m == null) {
                if (insertExtra) {
                    XNetPlusReply extraReply = XNetPlusAccess.createReply("42 01 01 44");
                    ReplyOutcome extraOut = processReplyNoException(extraReply, () -> {});
                    controller.replyFinished(extraOut);
                }
                Thread.sleep(100);
                m = controller.pollMessage();
            }
            if (m == null) {
                return out;
            }
            controller.message(m);
            out = processReplyNoException(reply, () -> {});
            controller.replyFinished(out);
        }
        return out;
    }

    @Test
    public void testNotifyTargetHandlerListener() throws Exception {
        L1 l = new L1();
        
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        
        ReplyOutcome o = simulateMessage(l, jmriMsg);
        assertTrue(o.isMessageFinished());
        assertSame(Phase.FINISHED, o.getPhase());

        assertNotNull(l.okMessage);
        assertNull(l.timeoutMessage);
    }

    @Test
    public void testNotifyTargetHandlerExListener() throws Exception {
        L2 l = new L2();
            
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        ReplyOutcome o = simulateMessage(l, jmriMsg);
        assertTrue(o.isMessageFinished());
        assertSame(Phase.FINISHED, o.getPhase());
        assertNotNull(l.okMessage);
        assertNotNull(l.op);
        assertNotNull(l.allReplies);
        assertNull(l.timeoutMessage);
    }
    
    @Test
    public void testNotifyExListenerConcurrent() throws Exception {
        L2 l = new L2();
        
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
        ReplyOutcome o = simulateMessage(l, true, jmriMsg);
        assertNotNull(l.concurrentReply);
    }

    @Test
    public void testReplyFinished() {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );
    }

    @Test
    public void testTerminate() {
    }

    @Test
    public void testExpectAccessoryState() {
        assertEquals(Turnout.UNKNOWN, controller.getAccessoryState(1));
        controller.expectAccessoryState(1, Turnout.CLOSED);
        assertEquals(Turnout.CLOSED, controller.getAccessoryState(1));
        controller.expectAccessoryState(1, Turnout.THROWN);
        assertEquals(Turnout.THROWN, controller.getAccessoryState(1));
    }

    @Test
    public void testGetAccessoryState() {
    }

    @Test
    public void testRequestAccessoryStatus() {
    }

    /**
     * Checks that unsolicited feedback messages are monitored. The
     * controller should serve the proper accessory state.
     */
    @Test
    public void testUnsolicitedFeedbacksMonitored() throws Exception {
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        ReplyOutcome outcome = processReplyNoException(reply, () -> {});
        assertTrue(outcome.isMessageFinished());
        
        assertNotEquals(Turnout.UNKNOWN, controller.getAccessoryState(5));
        assertEquals(Turnout.UNKNOWN, controller.getAccessoryState(6));
    }
    
    /**
     * Checks that unsolicited feedback messages are monitored. The
     * controller should serve the proper accessory state.
     */
    @Test
    public void testSolicitedFeedbacksMonitored() throws Exception {
        XNetPlusMessage jmriMsg = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5, true, false, true)
        );

        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 09 44");
        reply.setResponseTo(jmriMsg);
        
        controller.send(jmriMsg, null);
        controller.pollMessage();
        controller.message(jmriMsg);
        
        ReplyOutcome outcome = processReplyNoException(reply, () -> {});
        assertTrue(outcome.isMessageFinished());
        
        assertNotEquals(Turnout.UNKNOWN, controller.getAccessoryState(5));
        assertNotEquals(Turnout.UNKNOWN, controller.getAccessoryState(6));
    }
    
}
