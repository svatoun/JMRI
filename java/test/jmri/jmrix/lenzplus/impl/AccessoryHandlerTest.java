/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandQueue;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.FeedbackPlusItem;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.jmrix.lenzplus.comm.XNetPlusCommAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/**
 *
 * @author sdedic
 */
public class AccessoryHandlerTest implements CommandQueue {
    Map<Integer, Integer> accessoryMap = new HashMap<>();
    
    private static XNetPlusReply newXNetPlusReply(String s) {
        return XNetPlusAccess.createReply(s);
    }
    
    boolean disableSelectively;
    
    XNetPlusMessage m;
    AccessoryHandler h;
    CommandState s;
    
    XNetPlusReply fb = newXNetPlusReply("42 01 01 44");
    XNetPlusReply ok = newXNetPlusReply("01 04 05");
    
    public AccessoryHandlerTest() {
    }
    
    @Before
    public void setUp() {
        m = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, true));
        h = new AccessoryHandler(s = new CommandState(m), null);
        XNetPlusCommAccess.attachQueue(h, this);
        XNetPlusCommAccess.toPhase(h, Phase.SENT);
    }
    
    @After
    public void tearDown() {
    }

    @Override
    public void requestAccessoryStatus(int id) {
        accessoryRequested = id;
    }
    
    int accessoryRequested = -1;

    @Override
    public void expectAccessoryState(int accId, int state) {
        accessoryMap.put(accId, state);
    }

    @Override
    public int getAccessoryState(int id) {
        return accessoryMap.getOrDefault(id, Turnout.UNKNOWN);
    }

    @Override
    public boolean send(XNetPlusMessage msg, XNetListener callback) {
        return false;
    }

    @Override
    public CommandState send(CommandHandler handler, XNetPlusMessage command, XNetListener callback) {
        CommandState s = new CommandState(command);
        if (handler != null) {
            XNetPlusCommAccess.attachHandler(s, handler);
        }
        sentCommand = s;
        handler.addMessage(s);
        return s;
    }
    
    CommandState sentCommand;

    // --------------------- Accept tests ---------------------------
    
    // --------------------- Accept tests ---------------------------
    
    /**
     * Checks that OK is accepted by accessory command.
     */
    @Test
    public void testAcceptsOK() throws Exception {
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        assertTrue(h.acceptsReply(m, reply));
    }

    /**
     * Verifies that just one OK is accepted, further OKs should be rejected
     */
    @Test
    public void testAcceptsJustSingleOK() throws Exception {
        h.getCommand().addOkMessage();
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        assertFalse(h.acceptsReply(m, reply));
    }
    
    /**
     * Ensures the ON command accepts a feedback message,
     */
    @Test
    public void testAcceptsFeedback() throws Exception {
        // closed message
        XNetPlusReply reply = fb;
        assertTrue(reply.selectTurnoutFeedback(m.getCommandedAccessoryNumber()).isPresent());
        assertTrue(h.acceptsReply(m, reply));
    }

    /**
     * Checks that ON command rejects feedback with the other state.
     */
    @Test
    public void testRejectsOtherState() throws Exception {
        // thrown message
        XNetPlusReply reply = newXNetPlusReply("42 01 02 44");
        assertTrue(reply.selectTurnoutFeedback(m.getCommandedAccessoryNumber()).isPresent());
        assertFalse(h.acceptsReply(m, reply));
    }
    
    // --------------------- Filter tests ---------------------------

    /**
     * Verifies that feedbacks for different turnouts are not altered by the filter.
     */
    @Test
    public void checkFilterUnrelatedMessage() throws Exception {
        // thrown message
        XNetPlusReply reply = newXNetPlusReply("42 00 02 44");
        assertFalse(reply.selectTurnoutFeedback(m.getCommandedAccessoryNumber()).isPresent());
        assertFalse(h.filterMessage(reply));
        assertFalse(reply.feedbacks().anyMatch(FeedbackPlusItem::isConsumed));
        
        reply = newXNetPlusReply("42 02 02 44");
        assertFalse(h.filterMessage(reply));
        assertFalse(reply.feedbacks().anyMatch(FeedbackPlusItem::isConsumed));
    }

    /**
     * Filter should not alter OK message.
     * @throws Exception 
     */
    @Test
    public void checkFilterWithOKMessage() throws Exception {
        // thrown message
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        assertFalse(h.filterMessage(reply));
        assertFalse(reply.isConsumed());
    }
    
    /**
     * Checks that only the turnout's feedback is consumed/filtered from the message.
     */
    @Test
    public void checkFilterExpectedFeedback() throws Exception {
        expectAccessoryState(5, Turnout.CLOSED);

        // closed message
        XNetPlusReply reply = fb;
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        h.filterMessage(reply);
        
        assertTrue("Feedabck 5 action must be marked", reply.isFeedbackActionConsumed(5));
        assertFalse("Feedback 5 must be gone", reply.selectTurnoutFeedback(5).isPresent());
        
        assertTrue("Feedback 6 must be still present", reply.selectTurnoutFeedback(6).isPresent());
        assertEquals(1, reply.feedbacks().count());
    }

    /**
     * Checks that only the turnout's feedback is consumed/filtered from the message.
     */
    @Test
    public void checkNoFilterUnkownFeedback() throws Exception {
        expectAccessoryState(5, Turnout.CLOSED);

        // closed message
        XNetPlusReply reply = newXNetPlusReply("42 01 00 44");
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        h.filterMessage(reply);
        
        assertTrue("Feedback 5 must be still present", reply.selectTurnoutFeedback(5).isPresent());
        assertTrue("Feedback 6 must be still present", reply.selectTurnoutFeedback(6).isPresent());
        assertEquals(2, reply.feedbacks().count());
    }

    /**
     * Checks that turnout's feedback will be filtered out. Since it is divergent,
     * there should be "recheck" flag set.
     */
    @Test
    public void checkFilterDivergentFeedback() throws Exception {
        expectAccessoryState(5, Turnout.THROWN);

        // closed message
        XNetPlusReply reply = newXNetPlusReply("42 01 01 44");
        assertTrue(reply.selectTurnoutFeedback(5).isPresent());
        h.filterMessage(reply);
        
        assertTrue(reply.isFeedbackActionConsumed(5));

        assertTrue("Feedback 6 must be still present", reply.selectTurnoutFeedback(6).isPresent());
        assertEquals(1, reply.feedbacks().count());
        
        assertTrue(h.isRecheckNeeded());
    }

    @Test
    public void testAddMessage() throws Exception {
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.CONFIRMED);
        
        XNetPlusMessage m2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, false));
        CommandState st = new CommandState(m2);
        assertTrue(h.addMessage(st));
        // second add will fail:
        assertFalse(h.addMessage(st));
        
        assertNotSame(st, h.getCommand());
        // let's accept the first command:
        XNetPlusCommAccess.advance(h);
        assertSame(st, h.getCommand());
        
        h = new AccessoryHandler(new CommandState(m), null);
        testDontAcceptOffCommands();
    }
    
    protected void testDontAcceptOffCommands() throws Exception {
        // try ON command
        XNetPlusMessage m2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, true));
        assertFalse(h.addMessage(new CommandState(m2)));
        
        // try OFF command for a different output
        m2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                false, true, false));
        assertFalse(h.addMessage(new CommandState(m2)));
        
        // try OFF command for a different accessory
        m2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(6,
                true, false, false));
        assertFalse(h.addMessage(new CommandState(m2)));
    }
    
    @Test
    public void testGetCommand() throws Exception {
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.CONFIRMED);

        XNetPlusMessage m = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, true));
        CommandState s1 = new CommandState(m);
        AccessoryHandler h = new AccessoryHandler(s1, null);
        assertSame(s1, h.getCommand());
        
        List<CommandState> cmds = h.getAllCommands();
        assertEquals(1, cmds.size());
        assertSame(s1, cmds.get(0));

        XNetPlusMessage m2 = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5,
            true, false, false));
        CommandState st = new CommandState(m2);
        assertTrue(h.addMessage(st));
        assertSame(s1, h.getCommand());
        cmds = h.getAllCommands();
        assertEquals(2, cmds.size());
        assertSame(s1, cmds.get(0));
        assertSame(st, cmds.get(1));
        
        XNetPlusCommAccess.advance(h);
        assertSame(st, h.getCommand());
        List<CommandState> cmds2 = h.getAllCommands();
        assertEquals(cmds, cmds2);
    }
    
    // --------------------- Sent tests ---------------------------

    @Test
    public void testSentChangesAccessory() throws Exception {
         XNetPlusMessage m = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, true));
        CommandState s1 = new CommandState(m);
        AccessoryHandler h = new AccessoryHandler(s1, null);
        XNetPlusCommAccess.attachQueue(h, this);

        assertEquals(Turnout.UNKNOWN, getAccessoryState(5));
        h.sent(s1);
        assertEquals(Turnout.CLOSED, getAccessoryState(5));
    }

    // --------------------- Processed tests ---------------------------

    @Test
    public void processedFeedbackDirected() throws Exception {
        ReplyOutcome out = h.processed(s, fb);
        assertTrue(out.isComplete());
        assertTrue(out.isSolicited());
        assertFalse(out.isAdditionalReplyRequired());
    }

    @Test
    public void processedFeedbackBroadcast() throws Exception {
        XNetReply r = new XNetReply("42 01 01 44");
        r.setUnsolicited();
        fb = new XNetPlusReply(r);
        assertTrue(fb.isBroadcast());
        
        ReplyOutcome out = h.processed(s, fb);
        assertFalse(out.isComplete());
        assertTrue(out.isSolicited());
        // got just feedback broadcast, needs also OK.
        assertTrue(out.isAdditionalReplyRequired());
    }

    @Test
    public void processedOK() throws Exception {
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        
        ReplyOutcome out = h.processed(s, ok);
        assertFalse(out.isComplete());
        assertTrue(out.isSolicited());
        // wait for a possible feedback
        assertFalse(out.isAdditionalReplyRequired());
    }

    @Test
    public void processedOKAndFeedback() throws Exception {
        assumeFalse(disableSelectively);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        
        ReplyOutcome out = h.processed(s, ok);
        assertFalse(out.isComplete());
        assertFalse(out.isAdditionalReplyRequired());
        
        // process a fedback:
        out = h.processed(s, fb);
        assertTrue(out.isComplete());
        assertTrue(out.isSolicited());
    }

    @Test
    public void processedOKAndNonBroadcastFeedback() throws Exception {
        assumeFalse(disableSelectively);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        
        ReplyOutcome out = h.processed(s, ok);
        assertFalse(out.isComplete());
        assertFalse(out.isAdditionalReplyRequired());
        
        XNetReply r = new XNetReply("42 01 01 44");
        r.setUnsolicited();
        fb = new XNetPlusReply(r);
        // process a fedback:
        out = h.processed(s, fb);
        assertTrue(out.isComplete());
        assertTrue(out.isSolicited());
    }

    @Test
    public void processedFeedbackAndOK() throws Exception {
        assumeFalse(disableSelectively);
        
        XNetReply r = new XNetReply("42 01 01 44");
        r.setUnsolicited();
        fb = new XNetPlusReply(r);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        
        ReplyOutcome out = h.processed(s, fb);
        assertFalse(out.isComplete());
        assertTrue(out.isAdditionalReplyRequired());
        
        out = h.processed(s, ok);
        assertTrue(out.isComplete());
        assertTrue(out.isSolicited());
    }

    // --------------------- Finished tests ---------------------------

    @Test
    public void testFinishedWithRecheck() {
        CommandState c = h.getCommand();
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.SENT);
        XNetPlusReply fb = newXNetPlusReply("42 01 02 44");
        h.filterMessage(fb);
        assertTrue(h.isRecheckNeeded());
        
        ReplyOutcome out = ReplyOutcome.finished(c, ok);
        h.finished(out, c);
        XNetPlusCommAccess.advance(h);
        checkSentCommand();
        checkAccessoryRequestState();
    }
    
    protected void checkSentCommand() {
        CommandState st = h.getCommand();
        assertNotSame(s, st);
        assertSame(sentCommand, st);
    }
    
    protected void checkAccessoryRequestState() {
        assertEquals(-1, accessoryRequested);
    }

    @Test
    public void testFinishedNoRecheck() {
        CommandState c = h.getCommand();
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.SENT);

        ReplyOutcome out = ReplyOutcome.finished(c, ok);
        h.finished(out, c);
        XNetPlusCommAccess.advance(h);
        checkSentCommand();
        checkAccessoryRequestState();
    }
    
    @Test
    public void testConcurrentAction() throws Exception {
        CommandState c = h.getCommand();
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(c, CommandState.Phase.SENT);

        XNetPlusReply fb = newXNetPlusReply("42 01 02 44");
        
        assertTrue(h.checkConcurrentAction(s, fb));
        assertEquals(5, accessoryRequested);
    }
}
