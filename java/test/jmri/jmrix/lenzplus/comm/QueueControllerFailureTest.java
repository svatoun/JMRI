package jmri.jmrix.lenzplus.comm;

import java.util.concurrent.Semaphore;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.JUnitTestBase;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.openide.util.Lookup;

/**
 * This test specializes on various failures.
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class QueueControllerFailureTest extends JUnitTestBase implements TrafficController {
    TestQueueController controller = new TestQueueController(this);

    @Override
    public Lookup getLookup() {
        return Lookup.EMPTY;
    }
    
    /**
     * Checks that messages will not expire before their set timeout.
     * @throws Exception 
     */
    @Test
    public void testExpirationRespectsTimeout() throws Exception {
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());
        // lower the timeoutsomewhat
        msg.setTimeout(800);
        // not transmitted yet:
        assertTrue(controller.getTransmittedMessages().isEmpty());
        controller.send(msg, null);
        CommandState st = controller.state(msg);
        // pretend we really send something:
        XNetPlusMessage polled = controller.pollMessage();
        // and as a traffic controller, send:
        controller.message(msg);
        
        assertFalse(controller.getTransmittedMessages().isEmpty());
        assertFalse(st.getPhase().isFinal());
        
        controller.disableExpiration = false;
        long t;
        while (!st.getPhase().isFinal() && 
                (((t = System.currentTimeMillis()) - st.getTimeSent()) < 1500)) {
            controller.expireTransmittedMessages();
            Thread.sleep(50);
        }
        assertTrue(st.getPhase().isFinal());

        t = System.currentTimeMillis();
        assertTrue(st.getTimeFinished() > st.getTimeSent());
        assertTrue(st.getTimeFinished() - st.getTimeSent() > msg.getTimeout());
    }
    
    /**
     * Check that confirmed, but (erroneously ?) unfinished message will eventually
     * transition to finished.
     * @throws Exception 
     */
    @Test
    public void testFinishConfirmedMessage() throws Exception {
        XNetPlusMessage msg = XNetPlusMessage.create(XNetMessage.getCSVersionRequestMessage());
        // lower the timeoutsomewhat
        msg.setTimeout(800);
        // not transmitted yet:
        assertTrue(controller.getTransmittedMessages().isEmpty());
        controller.send(msg, null);
        CommandState st = controller.state(msg);
        // pretend we really send something:
        XNetPlusMessage polled = controller.pollMessage();
        // and as a traffic controller, send:
        controller.message(msg);
        
        assertFalse(controller.getTransmittedMessages().isEmpty());
        assertFalse(st.getPhase().isFinal());
        
        st.toPhase(Phase.CONFIRMED);

        controller.disableExpiration = false;
        long t;
        while (!st.getPhase().isFinal() && 
                (((t = System.currentTimeMillis()) - st.getTimeSent()) < 2000)) {
            controller.expireTransmittedMessages();
            Thread.sleep(50);
        }
        assertTrue(st.getPhase().isFinal());

        t = System.currentTimeMillis();
        assertTrue(st.getTimeFinished() > st.getTimeSent());
        assertTrue(st.getTimeFinished() - st.getTimeSent() > msg.getTimeout());
    }
    
    
    /**
     * Checks that unconfirmed messages transition to expired after some time.
     * @throws Exception 
     */
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
        
        assertFalse(st.getPhase().passed(CommandState.Phase.CONFIRMED));
        
        controller.disableExpiration = false;
        // receive some message to expunge obsolete messages from the queue
        XNetPlusReply reply = XNetPlusAccess.createReply("42 01 01 44");
        // and fake the timeout:
        st.getMessage().setTimeout(50);
        Thread.sleep(100);
        controller.processReply2(reply, () -> {});
        
        assertSame(CommandState.Phase.EXPIRED, st.getPhase());
        assertTrue(controller.getTransmittedMessages().isEmpty());
    }
    
    
    /**
     * Checks that commands that may have been blocked by a command
     * will be released upon its expiration.
     * @throws Exception 
     */
    @Test
    public void testCommandsBlockedByExpired() throws Exception {
        XNetPlusMessage acc1 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5, true, false, true));
        
        XNetPlusMessage acc2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5, false, true, true));

        CommandState s1 = controller.send(null, acc1, null);
        CommandState s2 = controller.send(null, acc2, null);
        
        // poll the command from s1
        XNetPlusMessage m = controller.pollMessage();
        controller.message(m);
        
        XNetPlusReply r1 = XNetPlusAccess.createReply("42 01 01 44");
        r1.setResponseTo(m);
        ReplyOutcome o = controller.processReply2(r1, () -> {});
        
        CommandState offState;
        Semaphore offSem;
        synchronized (controller.testQueue) {
            controller.replyFinished(o);
            offState = s1.getHandler().getCommand();
            offSem = new Semaphore(0);
            // block the OFF, to be sure the test timing is not an issue
            controller.testQueue.delayMap.put(offState, offSem);
        }
        /*
        // now there should be "OFF" scheduled, and s2 blocked:
        assertEquals(Phase.FINISHED, s1.getPhase());
        assertNotEquals(Phase.FINISHED, s1.getHandler().getPhase());
        assertNull(controller.pollMessage());
        
        // wait for a while:
        Thread.sleep(300);
        synchronized (controller.testQueue) {
            assertTrue(controller.testQueue.reached.containsKey(offState));
        }
        // 
        assertEquals(Phase.FINISHED, s1.getPhase());
        assertNotEquals(Phase.FINISHED, s1.getHandler().getPhase());
        assertNull(controller.pollMessage());
        */
        // terminate
        controller.terminate(s1.getHandler(), false);
        
        // the held message should become unblocked.
        assertSame(s2.getMessage(), controller.pollMessage());
    }

    /**
     * Checks that commands that may have been blocked by a command
     * will be released upon its finish.
     * @throws Exception 
     */
    @Test
    public void testCommandsBlockedByFinished() throws Exception {
        XNetPlusMessage acc1 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5, true, false, true));
        
        XNetPlusMessage acc2 = XNetPlusMessage.create(
                XNetMessage.getTurnoutCommandMsg(5, false, true, true));

        CommandState s1 = controller.send(null, acc1, null);
        CommandState s2 = controller.send(null, acc2, null);

        // poll the command from s1
        XNetPlusMessage m = controller.pollMessage();
        controller.message(m);

        XNetPlusReply r1 = XNetPlusAccess.createReply("42 01 01 44");
        r1.setResponseTo(m);
        ReplyOutcome o = controller.processReply2(r1, () -> {});
        
        CommandState offState;
        Semaphore offSem;
        synchronized (controller.testQueue) {
            controller.replyFinished(o);
            offState = s1.getHandler().getCommand();
            offSem = new Semaphore(0);
            // block the OFF, to be sure the test timing is not an issue
            controller.testQueue.delayMap.put(offState, offSem);
        }
        controller.terminate(s1.getHandler(), true);
        
        // the held message should become unblocked.
        assertSame(s2.getMessage(), controller.pollMessage());
    }
}
