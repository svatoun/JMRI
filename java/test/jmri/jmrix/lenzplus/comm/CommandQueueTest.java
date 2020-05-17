/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.jmrix.lenzplus.impl.DefaultHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author sdedic
 */
public class CommandQueueTest {
    CommandQueue q = new CommandQueue();
    
    public CommandQueueTest() {
    }
    
    @Before
    public void setUp() {
    }
    
    @After
    public void tearDown() {
    }
    
    private CommandState newCommandState(int p, int n) {
        String mString = Integer.toHexString(p) + " " + Integer.toHexString(n);
        XNetPlusMessage msg = new XNetPlusMessage(mString).asPriority(p);
        CommandState s = new CommandState(msg);
        s.attachHandler(new DefaultHandler(s, null));
        return s;
    }

    @Test
    public void testPriorityNoBlocking() {
        CommandState s1 = newCommandState(100, 1);
        CommandState s2 = newCommandState(100, 2);
        CommandState s3 = newCommandState(50, 3);
        CommandState s4 = newCommandState(150, 4);
        CommandState s5 = newCommandState(100, 5);
        CommandState s6 = newCommandState(200, 6);
        
        q.add(s1, false);
        q.add(s2, false);
        q.add(s3, false);
        q.add(s4, false);
        q.add(s5, false);
        q.add(s6, false);
        
        assertSame(s3, q.poll());
        assertSame(s1, q.poll());
        assertSame(s2, q.poll());
        assertSame(s5, q.poll());
        assertSame(s4, q.poll());
        assertSame(s6, q.poll());
    }
    
    private CommandState newCommandState(int p, int lid, int n) {
        String mString = Integer.toHexString(p) + " " + Integer.toHexString(n);
        XNetPlusMessage msg = new XNetPlusMessage(mString).asPriority(p);
        CommandState s = new CommandState(msg);
        s.setCommandGroupKey(lid);
        return s;
    }
    
    @Test
    public void testCheckBlockedSimple() throws Exception {
        CommandState s1 = newCommandState(100, 1, 1);
        CommandState s2 = newCommandState(100, 1, 2);
        CommandState s3 = newCommandState(100, 2, 3);
        CommandState s4 = newCommandState(100, 1, 4);
        CommandState s5 = newCommandState(150, 2, 5);
        CommandState s6 = newCommandState(150, 2, 6);
        
        q.add(s1, false);
        q.add(s2, true);
        q.add(s3, false);
        q.add(s4, false);
        q.add(s5, true);
        q.add(s6, false);
        
        assertSame(s1, q.poll());
        assertSame(s3, q.poll());
        assertSame(null, q.poll());
    }
    
    @Test
    public void testCheckBlocked2() throws Exception {
        CommandState s1 = newCommandState(100, 1, 1);
        CommandState s2 = newCommandState(100, 1, 2);
        CommandState s3 = newCommandState(100, 2, 3);
        CommandState s4 = newCommandState(100, 1, 4);
        CommandState s5 = newCommandState(100, 2, 5);
        CommandState s6 = newCommandState(50, 2, 6);
        CommandState s7 = newCommandState(150, 2, 7);
        
        q.add(s1, false);
        q.add(s2, true);
        
        assertSame(s1, q.poll());
        assertSame(null, q.poll());
        
        q.add(s3, false);
        
        assertSame(s3, q.poll());
        
        q.add(s4, false);
        
        assertSame(null, q.poll());
        
        q.add(s5, true);
        
        assertSame(null, q.poll());
        
        q.add(s6, false);
        
        assertSame(s6, q.poll());
        
        assertSame(null, q.poll());
        
        // release s5, that should release s5, then s7
        q.unblock(s5);
        
    }
    
    private CommandState createTurnout(int a, boolean closed, boolean on) {
        XNetPlusMessage m = XNetPlusMessage.create(XNetMessage.getTurnoutCommandMsg(a, 
                closed, 
                !closed, on));
        if (!on) {
            m = m.delayed(100).asPriority(true);
        }
        CommandState s = new CommandState(m);
        if (a > 0) {
            s.setCommandGroupKey(a);
        }
        return s;
    }

    CommandState aOn = createTurnout(1, true, true);
    CommandState aOff = createTurnout(1, true, false);

    CommandState bOn = createTurnout(5, false, true);
    CommandState bOff = createTurnout(5, false, false);

    CommandState cOn = createTurnout(9, true, true);
    CommandState cOff = createTurnout(9, true, false);

    CommandState dOn = createTurnout(13, true, true);
    CommandState dOff = createTurnout(13, true, false);

    CommandState b2On = createTurnout(5, true, true);
    CommandState b2Off = createTurnout(5, true, false);

    CommandState a2On = createTurnout(1, false, true);
    CommandState a2Off = createTurnout(1, false, false);
    
    /**
     * Checks a following scenario.
     * - turnout A is commanded, sends out command
     * - turnout B is commanded, sends out command
     * - reply A causes delayed OFF to be sent, this one is blocked.
     * - turnout C is commanded, sends out command
     * - reply B causes delayed OFF to be sent, will be blocked
     * - OFF "A" timer expires, enters queue (unblock)
     * - reply B is commanded again, sends out command - which will be blocked
     * - reply "C" causes delayed OFF to be sent, this one is blocked.
     * - turnout D is commanded
     * - OFF "B" timer expires, enters queue (unblock)
     * !! at this phase, fetch the item from the queue !!!
     * - 2nd command for B is sent, causing another delayed B OFF
     * - OFF "C" timer expires, enters queue (unblock)
     * - OFF "2B" timer expires, enters queue (unblock)
     * 
     * The observed sequence of messages will be:
     * - A-ON, B-ON, C-ON, A-OFF, B-OFF, 2B-ON, C-OFF, 2B-OFF, D-ON, D-OFF
     * 
     * What the testcase shows:
     * - commands for turnouts are ordered after their predecessor's OFFs (priority)
     * - ON commands maintain order
     * - 2B-ON and D-ON maintain their order, if 2B is released before D reaches queue head.
     * 
     * @throws Exception 
     */
    @Test
    public void testTurnoutScenario1() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        
        assertSame(aOn, q.poll());
        q.add(aOff, true);
        
        q.add(cOn, false);
        
        assertSame(bOn, q.poll());
        q.add(bOff, true);

        q.unblock(aOff);

        q.add(b2On, false); // not blocked !
        assertSame(aOff, q.poll());
        
        assertSame(cOn, q.poll());
        q.add(cOff, true);

        q.add(dOn, false);
        
        q.unblock(bOff);
        assertSame(bOff, q.poll());
        
        assertSame(b2On, q.poll());
        q.add(b2Off, true);
        q.unblock(cOff);
        
        assertSame(cOff, q.poll());
        q.unblock(b2Off);
        
        assertSame(b2Off, q.poll());
        assertSame(dOn, q.poll());
        
        // all set
        assertEquals(0, q.size());
    }
    
    @Test
    public void testReinsertDefaultPriority() throws Exception {
        q.add(bOn, false);
        q.add(b2On, false);
        q.add(aOn, false);
        q.add(cOn, false);
        q.add(dOn, false);
        
        assertSame(bOn, q.poll());
        q.add(bOff, true);
        
        assertSame(aOn, q.poll());
        
        // this will unblock bOff, b2On. bOff will go to the head of the queue,
        // but b2On should go before cOn, dOn as it was enqueued before them.
        q.unblock(bOff);
        
        assertSame(bOff, q.poll());
        assertSame(b2On, q.poll());
    }
    
    @Test
    public void testReinsertSeveralPriorityMessages() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        q.add(a2On, false);
        q.add(b2On, false);
        
        assertSame(aOn, q.poll());
        q.add(aOff, true);
        assertSame(bOn, q.poll());
        q.add(bOff, true);
        assertNull(q.poll());
        
        q.unblock(aOff);
        q.unblock(bOff);
        
        assertSame(aOff, q.poll());
        assertSame(bOff, q.poll());
    }
    
    /**
     * Blocks a normal message, then a priority one. Now the normal message
     * is blocked even by the priority one. Release the normal block: 
     * the normal message must be still suspended by the priority message.
     * 
     * @throws Exception 
     */
    @Test
    public void testUnblockLowerPriorityStillBlocks() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        q.add(a2On, true);
        q.add(cOn, false);
        q.add(dOn, false);
        
        assertSame(aOn, q.poll());
        assertSame(bOn, q.poll());
        assertSame(cOn, q.poll());

        q.add(aOff, true);
        
        assertSame(dOn, q.poll());
        
        assertEquals(0, q.unblock(a2On));
        
        assertEquals(0, q.unblock(aOff));
        
        assertSame(aOff, q.poll());
        assertSame(a2On, q.poll());
    }
    
    /**
     * Block a normal message, and a priority one. Release the priority block,
     * but the normal messages must remain blocked (it was blocked explicitly).
     * @throws Exception 
     */
    @Test
    public void testUnblockHigherPriorityStillBlockes() throws Exception {
        q.add(aOn, false);
        q.add(a2On, true);
        q.add(bOn, false);
        q.add(cOn, false);
        q.add(dOn, false);
        
        assertSame(aOn, q.poll());
        assertSame(bOn, q.poll());

        q.add(aOff, true);
        assertSame(cOn, q.poll());
        
        q.unblock(aOff);

        assertSame(aOff, q.poll());
        assertSame(dOn, q.poll());
        assertNull(q.poll());
        
        q.unblock(a2On);
        assertSame(a2On, q.poll());
    }
    
    /**
     * Block a high priority message. Then block a normal one - that should
     * be just added to the high-prio wait set.
     * @throws Exception 
     */
    @Test
    public void testAddBlockToHigherPriority() throws Exception {
        q.add(aOn, false);
        assertSame(aOn, q.poll());
        q.add(aOff, true);
        q.add(a2On, true);
        q.add(bOn, false);
        q.add(cOn, false);
        
        assertSame(bOn, q.poll());
        assertSame(cOn, q.poll());
        
        assertNull(q.poll());
    }
    
    @Test
    public void testPeekMessage() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        
        assertSame(aOn, q.peek());
        assertSame(aOn, q.poll());
        q.add(aOff, true);
        
        q.add(cOn, false);
        
        assertSame(bOn, q.peek());
        assertSame(bOn, q.poll());
        q.add(bOff, true);

        q.unblock(aOff);

        q.add(b2On, false); // not blocked !
        assertSame(aOff, q.peek());
        assertSame(aOff, q.poll());
        
        assertSame(cOn, q.peek());
        assertSame(cOn, q.poll());
        q.add(cOff, true);

        q.add(dOn, false);
        
        q.unblock(bOff);
        assertSame(bOff, q.peek());
        assertSame(bOff, q.poll());
        
        assertSame(b2On, q.peek());
        assertSame(b2On, q.poll());
        q.add(b2Off, true);
        q.unblock(cOff);
        
        assertSame(cOff, q.peek());
        assertSame(cOff, q.poll());
        q.unblock(b2Off);
        
        assertSame(b2Off, q.peek());
        assertSame(b2Off, q.poll());
        assertSame(dOn, q.peek());
        assertSame(dOn, q.poll());
        
        // all set
        assertEquals(0, q.size());
    }
    
    @Test
    public void testRemoveNotBlocked() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        q.add(cOn, false);
        
        assertSame(aOn, q.poll());
        bOn.toPhase(Phase.EXPIRED);
        q.remove(bOn);
        assertSame(cOn, q.poll());
        assertNull(q.poll());
    }
    
    @Test
    public void tesRemoveReleasesBlocks() throws Exception {
        q.add(aOn, false);
        assertSame(aOn, q.poll());

        q.add(aOff, true);
        q.add(a2On, false);
        q.add(bOn, false);
        q.add(cOn, false);
        
        assertSame(bOn, q.poll());
        aOff.toPhase(Phase.EXPIRED);
        assertTrue(q.remove(aOff));
        
        assertSame(a2On, q.poll());
        assertSame(cOn, q.poll());
    }
    
    /**
     * Checks that removing an unfinished command fails with IllegalState.
     */
    @Test
    public void testRemoveUnfinishedFails() throws Exception {
        q.add(aOn, false);
        q.add(aOff, true);
        
        Assert.assertThrows(IllegalStateException.class, () -> q.remove(aOff));
    }
    
    @Test
    public void testRemoveNonExistent() throws Exception {
        q.add(aOn, false);
        q.add(aOff, true);

        aOn.toPhase(Phase.EXPIRED);
        aOff.toPhase(Phase.EXPIRED);
        bOn.toPhase(Phase.EXPIRED);
        assertTrue(q.remove(aOn));
        assertTrue(q.remove(aOff));
        assertFalse(q.remove(bOn));
    }
    
    @Test
    public void testBlockWithoutGroup() throws Exception {
        XNetPlusMessage m = XNetPlusMessage.create(XNetMessage.getTurnoutCommandMsg(1, 
                true, 
                false, true));
        CommandState s = new CommandState(m);
        
        q.add(s, true);
        q.add(aOn, false);
        
        assertSame(aOn, q.poll());
        assertNull(q.poll());
        
        q.unblock(s);
        assertSame(s, q.poll());
    }
    
    @Test
    public void testStreamWillNotReportExpired() throws Exception {
        q.add(aOn, false);
        q.add(bOn, false);
        
        bOn.toPhase(Phase.EXPIRED);
        
        assertFalse(q.getQueued().anyMatch(s -> s.getPhase().passed(Phase.FINISHED)));
    }
    
    /**
     * Checks that removing a blocking message will release messages
     * suspended by it.
     */
    @Test
    public void testRemoveBlocked() {
        q.add(aOff, true);
        q.add(a2On, false);

        assertNull(q.poll());

        aOff.toPhase(Phase.EXPIRED);
        q.remove(aOff);
        
        // check that a2On was unblocked:
        assertSame(a2On, q.poll());
    }
}
