/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.JUnitTestBase;
import jmri.jmrix.lenzplus.XNetAdapter;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author sdedic
 */
public class QueueControllerTest extends JUnitTestBase implements TrafficController {
    TestQueueController controller = new TestQueueController(this);
    CommandState lastSentState;
    
    List<XNetPlusMessage> reallyTransmitted = new ArrayList<>();
    Map<Integer, Integer> accessoryState = new HashMap<>();
    
    public QueueControllerTest() {
    }
    
    class TestQueueController extends QueueController {
        boolean disableExpiration = true;
        
        @Override
        protected void assureLayoutThread() {
        }
        
        public TestQueueController(TrafficController controller) {
            super(controller);
        }

        @Override
        public CommandState send(CommandHandler h, XNetPlusMessage msg, XNetListener callback) {
            CommandState s = super.send(h, msg, callback);
            lastSentState = s;
            return s;
        }

        @Override
        public void requestAccessoryStatus(int id) {
            //return accessoryState.getOrDefault(id, Turnout.UNKNOWN);
        }

        @Override
        void expireTransmittedMessages() {
            if (!disableExpiration) {
                super.expireTransmittedMessages();
            }
        }
    }

    @Override
    public <T> T lookup(Class<T> service) {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public void sendMessageToDevice(XNetPlusMessage msg, XNetListener l) {
        reallyTransmitted.add(msg);
        // record accessory state:
        int id = msg.getCommandedAccessoryNumber();
        if (id != -1 && msg.getCommandedOutputState()) {
            accessoryState.put(id, msg.getCommandedTurnoutStatus());
        }
    }
    
    /**
     * Checks a basic request - reply through the Default (generic) CommandHandler.
     */
    @Test
    public void testBasicStationStartup() {
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
        
        ReplyOutcome oc = controller.processReply2(reply, () -> {});
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

        oc = controller.processReply2(reply, () -> {});
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
        
        ReplyOutcome oc = controller.processReply2(reply, () -> {});
        controller.replyFinished(oc);
        
        // still remains in the transmit queue
        assertFalse(controller.getTransmittedMessages().isEmpty());
     
        Thread.sleep(controller.getConcurrentUnsolicitedTime());
        
        controller.disableExpiration = false;
        // receive some message to expunge obsolete messages from the queue
        reply = XNetPlusAccess.createReply("42 01 01 44");
        controller.processReply2(reply, () -> {});
        
        assertTrue(controller.getTransmittedMessages().isEmpty());
        // but is not expired:
        assertSame(Phase.FINISHED, st.getPhase());
    }
    
    @Test
    public void testExpireUnconfirmedMessage() throws Exception {
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
        
        Thread.sleep(controller.getStateTimeout());
        
        assertFalse(st.getPhase().passed(Phase.CONFIRMED));
        
        controller.disableExpiration = false;
        // receive some message to expunge obsolete messages from the queue
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        controller.processReply2(reply, () -> {});
        
        assertSame(Phase.EXPIRED, st.getPhase());
        assertTrue(controller.getTransmittedMessages().isEmpty());
    }
    
    XNetReply targetReply;

    @Test
    public void testSendTuroutMessageWithOff() throws Exception {
        XNetPlusMessage start = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(10, true, false, true)
        );
        
        controller.send(start, new XNetAdapter() {
            @Override
            public void message(XNetReply msg) {
                super.message(msg);
                targetReply = msg;
            }
        });
        
        CommandState st = controller.state(start);
        CommandHandler h = st.getHandler();
        
        assertSame(start,  controller.pollMessage());
        controller.message(start);
        
        XNetPlusReply reply = XNetPlusAccess.createReply("01 04 05");
        reply.setResponseTo(start);
        ReplyOutcome o = controller.processReply2(reply, () -> {});
        
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
        
        o = controller.processReply2(reply, () -> {});
        
        assertTrue(o.isComplete());
        assertFalse(o.isAdditionalReplyRequired());
        
        controller.replyFinished(o);
        
        assertSame(reply, targetReply);
    }
    
    /**
     * Send a simple query-response command, and check the target listener
     * was notified after the command finished.
     */
    @Test
    public void testSendSimpleCommand() throws Exception {
        XNetPlusMessage query = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());
        
        controller.send(query, new XNetAdapter() {
            @Override
            public void message(XNetReply msg) {
                super.message(msg);
                targetReply = msg;
            }
        });
        CommandState st = controller.state(query);
        CommandHandler h = st.getHandler();
        
        controller.pollMessage();
        controller.message(query);

        XNetPlusReply reply = XNetPlusAccess.createReply("63 21 36 00 74");
        reply.setResponseTo(query);
        ReplyOutcome o = controller.processReply2(reply, () -> {});
        assertTrue(o.isComplete());
        assertNull(targetReply);
        controller.replyFinished(o);
        
        assertSame(reply, targetReply);
    }

    @Test
    public void testReplay() {
    }

    @Test
    public void testSend_3args() {
    }

    @Test
    public void testForMessage() {
    }

    @Test
    public void testMessage() {
    }

    @Test
    public void testRejected() {
    }

    @Test
    public void testPreprocess() {
    }

    @Test
    public void testProcessUnsolicited() {
    }

    @Test
    public void testFilterThroughQueued() {
    }

    @Test
    public void testProcessAttachedMessage() {
    }

    @Test
    public void testProcessReply() {
    }

    @Test
    public void testNotifyTarget() {
    }

    @Test
    public void testReplyFinished() {
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
    
}
