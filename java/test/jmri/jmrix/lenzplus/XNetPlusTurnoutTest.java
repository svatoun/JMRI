package jmri.jmrix.lenzplus;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jmri.Turnout;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetFeedbackMessageCache;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTrafficController;
import jmri.jmrix.lenz.XNetTurnout;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.XNetPlusCommAccess;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutTest extends JUnitTestBase {
    static class MockMessageCache extends XNetFeedbackMessageCache {
        public MockMessageCache(XNetTrafficController controller) {
            super(controller);
        }

        @Override
        public void requestCachedStateFromLayout(XNetTurnout turnout) {
        }
    }

    static class MockTrafficController extends XNetPlusTrafficController {
        MockMessageCache cache = new MockMessageCache(this);
        List<XNetPlusMessage> msgs = new ArrayList<>();
        
        public MockTrafficController() {
            super(new LenzCommandStation());
        }
        
        @Override
        public void sendXNetMessage(XNetMessage m, XNetListener reply) {
            msgs.add(XNetPlusMessage.create(m));
            super.sendXNetMessage(m, reply);
        }

        @Override
        public void sendHighPriorityXNetMessage(XNetMessage m, XNetListener reply) {
            msgs.add(XNetPlusMessage.create(m));
        }

        @Override
        public XNetFeedbackMessageCache getFeedbackMessageCache() {
            return super.getFeedbackMessageCache(); //To change body of generated methods, choose Tools | Templates.
        }
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp(); 
        tc.msgs.clear();
    }
    
    MockTrafficController tc = new MockTrafficController();
    XNetPlusTurnout t = new XNetPlusTurnout("X", 5, tc);
    SimpleTL simpleTL = new SimpleTL();
    
    class SimpleTL implements PropertyChangeListener {
        private Map<String, Object> propValues = new HashMap<>();
        boolean permitMultiple = false;
        
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            Object old = propValues.put(evt.getPropertyName(), evt.getNewValue());
            if (permitMultiple) {
                return;
            }
            assertNull("Property " + evt.getPropertyName() + " fired multiple times", old);
        }
        
        void assertPropertyValue(String n, Object val) {
            assertEquals("Property " + n + " expected: " + val, val, propValues.get(n));
        }

        void assertNoChange(String n) {
            assertNull("Property " + n + " must not change", propValues.get(n));
        }
    }
    
    /**
     * Checks that setCommanded will fire up a command. Its KnownState
     * should change, and Commanded should reflect the command.
     */
    @Test
    public void testSetCommandedStateStart() throws Exception {
        t.addPropertyChangeListener(simpleTL);
        t.setCommandedState(Turnout.CLOSED);
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        
        assertEquals(1, tc.msgs.size());
        simpleTL.assertPropertyValue("CommandedState", Turnout.CLOSED);
        simpleTL.assertPropertyValue("KnownState", Turnout.INCONSISTENT);
    }
    
    /**
     * Twice the same command: sends a command to layout, but will not fire
     * or change the turnout.
     */
    @Test
    public void testSetCommandedTwiceSame() throws Exception {
        t.addPropertyChangeListener(simpleTL);
        t.setCommandedState(Turnout.CLOSED);
        t.setCommandedState(Turnout.CLOSED);
        
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        
        assertEquals(2, tc.msgs.size());
        simpleTL.assertPropertyValue("CommandedState", Turnout.CLOSED);
        simpleTL.assertPropertyValue("KnownState", Turnout.INCONSISTENT);
    }

    /**
     * Checks that nothing changes from an unexpected feedback delivered through
     * Turnout manager.
     */
    @Test
    public void testMonitoringChangeStateFromExpectedFeedback() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        r.selectTurnoutFeedback(5).get().consume();
        t.message(r);
        
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
    }
    
    /**
     * Tests that if feedback is unexpected, both Known AND Commanded
     * states change.
     */
    @Test
    public void testMonitoringChangeStateFromFeedback() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        t.message(r);
        
        assertEquals(Turnout.THROWN, t.getKnownState());
        assertEquals(Turnout.THROWN, t.getCommandedState());
    }
    
    /**
     * When monitoring turnout command completes, it should change the 
     * known state of the turnout.
     */
    @Test
    public void testMonitoringCompleteChangesState() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        
        t.completed(s);
        
        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
    }
    
    /**
     * When monitoring turnout command completes, it should change the 
     * known state of the turnout. If the state is reported DIFFERENT
     * than expected, the command state should change as well, and 
     * a low-priority resync command should be fired.
     */
    @Test
    public void testMonitoringCompleteChangesDifferentState() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        
        assertEquals(1, tc.msgs.size());
        t.completed(s);
        
        assertEquals(Turnout.THROWN, t.getKnownState());
        assertEquals(Turnout.THROWN, t.getCommandedState());
        
        assertEquals(2, tc.msgs.size());

        assertResyncMessage();
    }
    
    private void eatUpMessages() {
        XNetPlusMessage m;
        while ((m = tc.getQueueController().pollMessage()) != null) {
            ;
        }
    }
    
    /**
     * Checks that if monitoring turnout completes, but the feedback received
     * is inconsistent (neither closed or thrown), it issues a new command to
     * get layout to the shape.
     */
    @Test
    public void testMonitoringCompleteInconsistentState() throws Exception {
        eatUpMessages();
        
        t.setCommandedState(Turnout.CLOSED);
        // next command to check command order
        
        t.setCommandedState(Turnout.THROWN);
        
        // poll the first command
        XNetPlusMessage cmd = tc.getQueueController().pollMessage();
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 00 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        
        assertEquals(2, tc.msgs.size());
        t.completed(s);
        
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        // the new state is retained
        assertEquals(Turnout.THROWN, t.getCommandedState());
        
        // we have one more command
        assertEquals(3, tc.msgs.size());

        // what will be the next ?
        XNetPlusMessage testCmd = tc.getQueueController().pollMessage();
        assertEquals(cmd.getCommandedTurnoutStatus(), testCmd.getCommandedTurnoutStatus());
    }

    private void assertResyncMessage() {
        assertTrue(tc.msgs.size() > 0);
        XNetPlusMessage m = tc.msgs.get(tc.msgs.size() -1);
        assertTrue("Resync message should have been issued.", m.getPriority() > XNetPlusMessage.DEFAULT_PRIORITY);
    }
    
    /**
     * Checks that a monitoring feedback will attempt to resync, if it receives a
     * 'concurrent' reply in its completion.
     */
    @Test
    public void testMonitoringWillResyncIfConcurrent() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        s.setConcurrentReply(r2);
        
        assertEquals(1, tc.msgs.size());
        t.completed(s);

        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        
        assertEquals(2, tc.msgs.size());
        assertResyncMessage();
        
        assertNull(t.getMotionCompleter());
        assertNotNull(t.getPostCommandResync());
        
        XNetPlusMessage resyncMsg = tc.msgs.get(1);
        XNetPlusResponseListener l = (XNetPlusResponseListener)XNetPlusCommAccess.state(tc.getQueueController(), resyncMsg).
                getHandler().getTarget();
        // simulate the inquiry complete:
        CompletionStatus cs = new CompletionStatus(resyncMsg);
        // the 2nd state is the correct one:
        cs.addReply(r2);
        cs.success();
        
        l.completed(cs);
        assertEquals(Turnout.THROWN, t.getKnownState());
        assertEquals(Turnout.THROWN, t.getCommandedState());
    }
    
    /**
     * If the turnout decided to resync and another resync instruction will arrive,
     * just that one resync will happen.
     */
    @Test
    public void testMonitoringResyncsJustOnce() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        s.setConcurrentReply(r2);
        
        assertEquals(1, tc.msgs.size());
        
        // first command completes
        t.completed(s);
        
        assertEquals(2, tc.msgs.size());
        assertResyncMessage();
        
        // informed about a concurrent message after completion:
        t.concurrentLayoutOperation(s, r2);
        
        // still just 2 messages sent.
        assertEquals(2, tc.msgs.size());
    }
    
    /**
     * Checks that resync will happen, if a command completes, then 
     * concurrent msg is detected later.
     */
    @Test
    public void testResyncIfConcurrentDetectedLater() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        
        assertEquals(1, tc.msgs.size());
        t.completed(s);

        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        
        assertEquals(1, tc.msgs.size());
        
        r2.markConcurrent(s.getCommand());
        t.message(r2);
        
        assertEquals(2, tc.msgs.size());
        assertResyncMessage();
        
        assertNull(t.getMotionCompleter());
        assertNotNull(t.getPostCommandResync());
    }
    
    /**
     * Checks that no error occurs if the resync or motion attempts fail
     */
    @Test
    public void testResyncOrMotionAttemptFails() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        // simulate completion w/ concurrent reply
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        s.setConcurrentReply(r2);
        t.completed(s);

        assertResyncMessage();
        assertNull(t.getMotionCompleter());
        assertNotNull(t.getPostCommandResync());
        
        XNetPlusMessage resyncMsg = tc.msgs.get(1);
        XNetPlusResponseListener l = (XNetPlusResponseListener)XNetPlusCommAccess.state(tc.getQueueController(), resyncMsg).
                getHandler().getTarget();

        // simulate a timed out reply
        CompletionStatus cs = new CompletionStatus(resyncMsg);
        l.failed(cs);
        
        // turnout unchanged
        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        
        // queue unchanged
        assertEquals(2, tc.msgs.size());
        
        // tasks cleared
        assertNull(t.getMotionCompleter());
        assertNull(t.getPostCommandResync());
    }
    
    /**
     * After a command times out, it is *safe* to send a rescue OFF message, 
     * just in case the command reached the command station, but the reply
     * was lost for some reason.
     * @throws Exception 
     */
    @Test
    public void testTimeoutWillSendRescueOff() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        XNetPlusMessage m = tc.msgs.get(0);
        CompletionStatus timeout = new CompletionStatus(m);
        assertTrue(timeout.isTimeout());
        assertFalse(timeout.isSuccess());
        
        t.failed(timeout);
        assertEquals(2, tc.msgs.size());
        
        XNetPlusMessage off = tc.msgs.get(1);
        assertEquals(XNetConstants.ACC_OPER_REQ, off.getElement(0));
        assertEquals(false, off.getCommandedOutputState());
        assertTrue(off.isSameAccessoryOutput(m));

        CompletionStatus timeout2 = new CompletionStatus(off);
        assertTrue(timeout2.isTimeout());
        assertFalse(timeout2.isSuccess());
        
        // simulate other timeout:
        t.failed(timeout2);
        assertEquals(3, tc.msgs.size());
        
        off = tc.msgs.get(2);
        assertEquals(XNetConstants.ACC_OPER_REQ, off.getElement(0));
        assertEquals(false, off.getCommandedOutputState());
        assertTrue(off.isSameAccessoryOutput(m));
        
        tc.msgs.clear();
        // 2,3
        t.failed(timeout2);
        t.failed(timeout2);
        // 4: this one should NOT produce any further off
        t.failed(timeout2);
        
        assertEquals(2, tc.msgs.size());
    }
    
    /**
     * Just for sure: rescue OFF must be send always after ON command times
     * out.
     */
    @Test
    public void timeoutAlwaysSendsRescueOffAfterOn() throws Exception {
        t.setCommandedState(Turnout.CLOSED);
        XNetPlusMessage m = tc.msgs.get(0);
        CompletionStatus timeout = new CompletionStatus(m);
        assertTrue(timeout.isTimeout());
        assertFalse(timeout.isSuccess());

        for (int i = 2; i < 10; i++) {
            t.failed(timeout);
            assertEquals(i, tc.msgs.size());
        }
    }
    
    /**
     * After inconsistent feedback is received, the turnout will try to set itself
     * back to its current commanded state.
     * 
     * @throws Exception 
     */
    @Test
    public void testInconsistentFeedbackSetsToComanded() throws Exception {
        
        t.setCommandedState(Turnout.CLOSED);
        eatUpMessages();

        // next command to check command order
        t.setCommandedState(Turnout.THROWN);
        
        XNetPlusMessage cmd = tc.getQueueController().pollMessage();
        eatUpMessages();
        tc.msgs.clear();
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 00 63");
        t.message(r);
        
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        // the new state is retained
        assertEquals(Turnout.THROWN, t.getCommandedState());
        
        // we have one more command
        assertEquals(1, tc.msgs.size());

        // what will be the next ?
        XNetPlusMessage testCmd = tc.getQueueController().pollMessage();
        assertEquals(cmd.getCommandedTurnoutStatus(), testCmd.getCommandedTurnoutStatus());
    }
    
    /**
     * Direct-type feedbacks should ignore concurrent infos.
     */
    @Test
    public void testDirectIgnoresConcurrent() throws Exception {
        t.setFeedbackMode(XNetPlusTurnout.DIRECT);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        CompletionStatus s = new CompletionStatus(tc.msgs.get(0)).success();
        s.addReply(r);
        s.setConcurrentReply(r2);
        
        assertEquals(1, tc.msgs.size());
        t.completed(s);

        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        
        assertEquals(1, tc.msgs.size());
    }
    
    @Test
    public void testDirectDoNotChangeStateUnexpected() throws Exception {
        t.setFeedbackMode(XNetPlusTurnout.DIRECT);

        t.setCommandedState(Turnout.CLOSED);
        CompletionStatus cs = new CompletionStatus(tc.msgs.get(0));
        t.completed(cs);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        t.message(r);
        
        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
    }
    
    /**
     * Checks basic reception of unsolicited feedback for exact turnout.
     */
    @Test
    public void testExactModeUnsolicitedFeedback() throws Exception {
        t.setFeedbackMode(XNetPlusTurnout.EXACT);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        t.message(r);
        
        assertEquals(Turnout.THROWN, t.getKnownState());
        assertEquals(Turnout.THROWN, t.getCommandedState());
    }
    
    /**
     * Checks basic reception of unsolicited feedback for exact turnout.
     */
    @Test
    public void testExactModeConsumedFeedback() throws Exception {
        t.setFeedbackMode(XNetPlusTurnout.EXACT);
        t.setCommandedState(Turnout.CLOSED);
                
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 02 63");
        r.onTurnoutFeedback(5, (FeedbackPlusItem f) -> {
            f.consume(); 
            return true;
        });
        
        t.message(r);
        
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
    }
    
    /**
     * When received 'motion in progress' feedback, the state must not change.
     * Instead of that, an inquiry command should be scheduled.
     */
    @Test
    public void testExactModeFeedbacMotionInProgress() throws Exception {
        t.setFeedbackMode(XNetPlusTurnout.EXACT);
        t.setCommandedState(Turnout.CLOSED);
        
        eatUpMessages();
        
        tc.msgs.clear();
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 A2 63");
        XNetPlusMessage inq;
        
        synchronized (tc.getQueueController()) {
            t.message(r);

            assertEquals(1, tc.msgs.size());

            inq = tc.msgs.remove(0);
            
            // first message is fired immediately
            XNetPlusMessage m = tc.getQueueController().pollMessage();
            assertSame(inq, m);
        }
        
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        
        // get a state for the message:
        CommandState s = XNetPlusCommAccess.state(tc.getQueueController(), inq);
        tc.msgs.clear();
        
        synchronized (tc.getQueueController()) {
            t.message(r);

            assertEquals(0, tc.msgs.size());
            
            // inquiry is under way, should not post antoher
            assertNull(tc.getQueueController().pollMessage());
        }
        
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());

        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 82 63");
        XNetPlusReply finalR = XNetPlusAccess.createReply("42 01 02 63");
        
        // send the reply to the exact motion handler:
        XNetPlusResponseListener l = (XNetPlusResponseListener)s.getHandler().getTarget();
        
        
        CompletionStatus st = new CompletionStatus(inq);
        st.addReply(r2);
        st.success();
        l.completed(st);

        // still nothing, next query sent:
        assertEquals(1, tc.msgs.size());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        assertNotNull(t.getMotionCompleter());
        assertNull(t.getPostCommandResync());
        
        CompletionStatus st2 = new CompletionStatus(inq);
        st2.addReply(finalR);
        st2.success();
        l.completed(st2);

        assertEquals(Turnout.THROWN, t.getCommandedState());
        assertEquals(Turnout.THROWN, t.getKnownState());
        
        assertNull(t.getMotionCompleter());
        assertNull(t.getPostCommandResync());
    }

    /**
     * Simple behaviour in exact mode: the motion already completed at the time
     * of the feedback.
     */
    @Test
    public void testExactModeMotionAlreadyDone() throws Exception {
        eatUpMessages();

        t.setFeedbackMode(XNetPlusTurnout.EXACT);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusMessage cmd = tc.getQueueController().pollMessage();
        
        tc.msgs.clear();
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 21 63");
        r.setResponseTo(cmd);
        CompletionStatus s = new CompletionStatus(cmd);
        s.addReply(r);
        s.success();
        
        synchronized (tc.getQueueController()) {
            t.completed(s);
            assertEquals(0, tc.msgs.size());
        }

        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.CLOSED, t.getKnownState());
    }
    
    /**
     * The command reply is motion in progress, known state must change 
     * only after the motion completes
     */
    @Test
    public void testExactModeCommandInProgress() throws Exception {

        eatUpMessages();
        
        t.setFeedbackMode(XNetPlusTurnout.EXACT);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusMessage cmd = tc.getQueueController().pollMessage();
        
        tc.msgs.clear();
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 A2 63");
        r.setResponseTo(cmd);
        XNetPlusMessage inq;
        
        CompletionStatus s2 = new CompletionStatus(cmd);
        s2.addReply(r);
        s2.success();

        synchronized (tc.getQueueController()) {
            t.completed(s2);

            assertEquals(1, tc.msgs.size());

            inq = tc.msgs.remove(0);
            
            // first message is fired immediately
            XNetPlusMessage m = tc.getQueueController().pollMessage();
            assertSame(inq, m);
        }
        
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
        
        // get a state for the message:
        CommandState s = XNetPlusCommAccess.state(tc.getQueueController(), inq);
        tc.msgs.clear();
        
        synchronized (tc.getQueueController()) {
            t.message(r);

            assertEquals(0, tc.msgs.size());
            
            // inquiry is under way, should not post antoher
            assertNull(tc.getQueueController().pollMessage());
        }
        
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());

        XNetPlusReply r2 = XNetPlusAccess.createReply("42 01 02 63");
        
        // send the reply to the exact motion handler:
        XNetPlusResponseListener l = (XNetPlusResponseListener)s.getHandler().getTarget();
        CompletionStatus st = new CompletionStatus(inq);
        st.addReply(r2);
        st.success();
        
        l.completed(st);

        assertEquals(Turnout.THROWN, t.getCommandedState());
        assertEquals(Turnout.THROWN, t.getKnownState());
        
        assertNull(t.getMotionCompleter());
        
        // the feedback was different from the commanded state, so inquiry once again
        assertNotNull(t.getPostCommandResync());
    }
    
    /**
     * Checks that inverted command does the right things
     * @throws Exception 
     */
    @Test
    public void checkInvertedCommand() throws Exception {
        t.setInverted(true);
        t.setCommandedState(Turnout.CLOSED);
        assertEquals(1, tc.msgs.size());
        assertEquals(Turnout.THROWN, tc.msgs.get(0).getCommandedTurnoutStatus());
    }

    /**
     * Checks that inverted feedback received does the right things
     * @throws Exception 
     */
    @Test
    public void checkInvertedFeedback() throws Exception {
        t.setInverted(true);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        t.message(r);
        
        assertEquals(Turnout.THROWN, t.getCommandedState());
        assertEquals(Turnout.THROWN, t.getKnownState());
    }

    /**
     * Checks that inverted feedback received does the right things
     * @throws Exception 
     */
    @Test
    public void checkInvertedCompletionMonitoring() throws Exception {
        t.setInverted(true);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusMessage cmd = tc.msgs.get(0);

        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        CompletionStatus s = new CompletionStatus(cmd);
        s.addReply(r);
        s.success();
        
        t.completed(s);
        
        assertEquals(Turnout.THROWN, t.getCommandedState());
        assertEquals(Turnout.THROWN, t.getKnownState());
    }

    /**
     * Checks that inverted feedback received does the right things
     * @throws Exception 
     */
    @Test
    public void checkInvertedCompletionDirect() throws Exception {
        t.setInverted(true);
        t.setCommandedState(Turnout.CLOSED);
        
        XNetPlusMessage cmd = tc.msgs.get(0);

        XNetPlusReply r = XNetPlusAccess.createReply("42 01 01 63");
        CompletionStatus s = new CompletionStatus(cmd);
        s.addReply(r);
        s.success();
        
        t.completed(s);
        
        assertEquals(Turnout.THROWN, t.getCommandedState());
        assertEquals(Turnout.THROWN, t.getKnownState());
    }
}
