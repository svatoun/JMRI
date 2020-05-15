/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.impl.AccessoryHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.XNetPlusCommAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import jmri.jmrix.lenzplus.comm.CommandService;

/**
 *
 * @author sdedic
 */
public class AccessoryHandlerOffTest extends AccessoryHandlerTest implements CommandService {
    private Map<Integer, Integer> accessoryMap = new HashMap<>();
    
    XNetPlusMessage m2;
    CommandState s2;
    
    public AccessoryHandlerOffTest() {
        disableSelectively = true;
    }
    
    @Before
    public void setUp() {
        super.setUp();
        // simulate adding the OFF command
        m2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, false));
        s2 = new CommandState(m2);
        h.addMessage(s2);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.QUEUED);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.SENT);
        XNetPlusCommAccess.toPhase(s, CommandState.Phase.CONFIRMED);
        XNetPlusCommAccess.advance(h);
    }
    
    @After
    public void tearDown() {
    }

    // --------------------- Accept tests ---------------------------
    
    /**
     * Ensures the OFF command rejects a feedback message,
     */
    public void testAcceptsFeedback() throws Exception {
        // closed message
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        assertTrue(reply.selectTurnoutFeedback(m.getCommandedAccessoryNumber()).isPresent());
        assertFalse(h.acceptsReply(m, reply));
    }

    // --------------------- Filter tests ---------------------------

    // --------------------- Misc tests ---------------------------

    @Test
    public void testAddMessage() throws Exception {
        CommandState s3 = new CommandState(m2);
        // second add will fail:
        assertFalse(h.addMessage(s3));
        assertSame(s2, h.getCommand());
        
        // try OFF command
        XNetPlusMessage m3 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, false));
        assertFalse(h.addMessage(new CommandState(m3)));
        
        testDontAcceptOffCommands();
    }
    
    @Test
    public void testGetCommand() throws Exception {
        List<CommandState> cmds2 = h.getAllCommands();
        assertEquals(2, cmds2.size());
        assertSame(s, cmds2.get(0));
        assertSame(s2, cmds2.get(1));
    }
    
    @Test
    public void testSentOffDoesNothing() throws Exception {
         XNetPlusMessage m = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5,
                true, false, true));
        CommandState s1 = new CommandState(m);
        AccessoryHandler h = new AccessoryHandler(s1, null);
        XNetPlusCommAccess.attachQueue(h, this);

        XNetPlusMessage m2 = XNetPlusMessage.create(
            XNetMessage.getTurnoutCommandMsg(5,
            true, false, false));
        CommandState st = new CommandState(m2);
        h.addMessage(st);
        XNetPlusCommAccess.advance(h);

        assertEquals(Turnout.UNKNOWN, getAccessoryState(5));
        h.sent(s1);
        assertEquals(Turnout.UNKNOWN, getAccessoryState(5));
    }

    @Override
    @Test
    public void processedFeedbackDirected() throws Exception {
        assertFalse(h.acceptsReply(m, fb));
        ReplyOutcome out = h.processed(s2, fb);
        assertTrue(out.isComplete());
    }

    @Override
    @Test
    public void processedFeedbackBroadcast() throws Exception {
        XNetReply r = new XNetReply("42 01 01 44");
        r.setUnsolicited();
        fb = new XNetPlusReply(r);
        assertTrue(fb.isBroadcast());
        
        assertFalse(h.acceptsReply(m, fb));
        ReplyOutcome out = h.processed(s2, fb);
        assertTrue(out.isComplete());
    }

    @Override
    @Test
    public void processedOK() throws Exception {
        ReplyOutcome out = h.processed(s2, ok);
        assertTrue(out.isComplete());
        assertTrue(out.isSolicited());
    }
    
    @Override
    public void checkAccessoryRequestState() {
        if (h.isRecheckNeeded()) {
            assertEquals(5, accessoryRequested);
        }
    }
    
    public void checkSentCommand() {
        assertNull(sentCommand);
    }

    
}
