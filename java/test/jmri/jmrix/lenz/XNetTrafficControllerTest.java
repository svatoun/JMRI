package jmri.jmrix.lenz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import jmri.InstanceManager;
import jmri.Turnout;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.lenz.liusb.LIUSBXNetPacketizer;
import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for XNetTrafficController.
 *
 * @author Bob Jacobsen Copyright (C) 2002
 * @author Paul Bender Copyright (C) 2016
 */
public class XNetTrafficControllerTest extends jmri.jmrix.AbstractMRTrafficControllerTest {
    XNetTestSimulator testAdapter;
    XNetPacketizer lnis;
    
    volatile boolean blockMessageQueue;
    final Semaphore messageQueuePermits = new Semaphore(0);
    volatile boolean timeoutOccured;
    
    XNetTurnoutManager xnetManager;
    
    MessageOutput output;
        
    @Before
    @Override
    public void setUp() {
        JUnitUtil.setUp();

        // prepare an interface
        jmri.util.JUnitUtil.resetInstanceManager();
        jmri.util.JUnitUtil.initInternalSensorManager();
        jmri.util.JUnitUtil.initInternalTurnoutManager();
        jmri.InstanceManager.store(new jmri.NamedBeanHandleManager(), jmri.NamedBeanHandleManager.class);

        tc = new XNetTrafficController(new LenzCommandStation()){
            @Override
            public void sendXNetMessage(XNetMessage m, XNetListener reply){
                System.err.println("ahoj");
            }
        };
    }
    
    public interface MessageOutput {
        public void sendMessage(AbstractMRMessage m, AbstractMRListener reply);
    }
    
    class TestUSBPacketizer extends LIUSBXNetPacketizer implements MessageOutput {

        public TestUSBPacketizer(LenzCommandStation pCommandStation) {
            super(pCommandStation);
            output = this;
        }
        
        @Override
        protected AbstractMRMessage takeMessageToTransmit(AbstractMRListener[] ll) {
            if (blockMessageQueue) {
                try {
                    messageQueuePermits.acquire();
                } catch (InterruptedException ex) {

                }
            }
            return super.takeMessageToTransmit(ll);
        }

        @Override
        protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
            super.handleTimeout(msg, l);
            timeoutOccured = true;
        }

        // Just a trampoline
        @Override
        public synchronized void sendMessage(AbstractMRMessage m, AbstractMRListener reply) {
            super.sendMessage(m, reply);
        }
    }
    
    class TestPacketizer extends XNetPacketizer implements MessageOutput {

        public TestPacketizer(LenzCommandStation pCommandStation) {
            super(pCommandStation);
            output = this;
        }
        
        
        @Override
        protected AbstractMRMessage takeMessageToTransmit(AbstractMRListener[] ll) {
            if (blockMessageQueue) {
                try {
                    messageQueuePermits.acquire();
                } catch (InterruptedException ex) {

                }
            }
            return super.takeMessageToTransmit(ll);
        }

        @Override
        protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
            super.handleTimeout(msg, l);
            timeoutOccured = true;
        }

        // Just a trampoline
        @Override
        public synchronized void sendMessage(AbstractMRMessage m, AbstractMRListener reply) {
            super.sendMessage(m, reply);
        }
    }
    
    private void initializeLayout(XNetTestSimulator adapter) {
        initializeLayout(adapter, new TestPacketizer(new LenzCommandStation()));
    }
    
    private void initializeLayout(XNetTestSimulator adapter, XNetPacketizer packetizer) {
        testAdapter = adapter;
        lnis = packetizer;
        testAdapter.configure(lnis);
        xnetManager = (XNetTurnoutManager)InstanceManager.getDefault().getInstance(XNetSystemConnectionMemo.class).getTurnoutManager();
    }

    @After
    @Override
    public void tearDown(){
        tc = null;
        JUnitUtil.clearShutDownManager(); // put in place because AbstractMRTrafficController implementing subclass was not terminated properly
        JUnitUtil.tearDown();
    }

    /**
     * Check that normal messages will arrive in
     * the same order as they were posted.
     */
//    @Test
    public void testNormalMessages() throws Exception {
        XNetTestSimulator simul = new XNetTestSimulator.DR5000();
        initializeLayout(simul);
        
        simul.clearMesages();
        simul.setCaptureMessages(true);
        
        simul.limitReplies = true;
        XNetMessage m = XNetMessage.getCSVersionRequestMessage();        
        XNetMessage m2 = XNetMessage.getCSStatusRequestMessage();
        XNetMessage m3 = XNetMessage.getLocomotiveInfoRequestMsg(1);
        
        CountDownLatch l = new CountDownLatch(3);
        XNetListener callback = new XNetListenerScaffold() {
            @Override
            public void message(XNetReply m) {
                l.countDown();
            }
        };
        output.sendMessage(m, callback);
        output.sendMessage(m2, callback);
        output.sendMessage(m3, callback);
        
        simul.repliesAllowed.release(100);
        
        l.await(300, TimeUnit.MILLISECONDS);
        
        List<XNetMessage> msgs = simul.getOutgoingMessages();
        assertEquals(Arrays.asList(m, m2, m3), msgs);
    }
    
    /**
     * Checks that a priority message will preempt existing messages
     * in the queue, and also new messages that should be yet sent.
     */
//    @Test
    public void testSendPriorityMessages() throws Exception {
        XNetTestSimulator simul = new XNetTestSimulator.DR5000();
        initializeLayout(simul);
        
        simul.clearMesages();
        simul.setCaptureMessages(true);
        
        blockMessageQueue = true;
        messageQueuePermits.release(1);
        XNetMessage m = XNetMessage.getCSVersionRequestMessage();       
        XNetMessage m2 = XNetMessage.getCSStatusRequestMessage();
        XNetMessage m3 = XNetMessage.getLocomotiveInfoRequestMsg(1);
        XNetMessage m4 = XNetMessage.getCSVersionRequestMessage();
        XNetMessage m5 = XNetMessage.getLocomotiveInfoRequestMsg(1);
        
        CountDownLatch l = new CountDownLatch(5);
        XNetListener callback = new XNetListenerScaffold() {
            @Override
            public void message(XNetReply m) {
                l.countDown();
            }
        };
        output.sendMessage(m, callback);
        output.sendMessage(m2, callback);
        lnis.sendHighPriorityXNetMessage(m3, callback);
        lnis.sendHighPriorityXNetMessage(m4, callback);
        output.sendMessage(m5, callback);

        messageQueuePermits.release(100);
        
        l.await(300, TimeUnit.MILLISECONDS);
        
        List<XNetMessage> msgs = simul.getOutgoingMessages();
        assertEquals(Arrays.asList(m3, m4, m, m2, m5), msgs);
    }
    
    /**
     * Checks that a sole feedback response to Turnout command
     * is sufficient to acknowledge the command.
     */
//    @Test
    public void testFeedbackOnlyAccepted() throws Exception {
        XNetTestSimulator simul = new XNetTestSimulator.NanoXGenLi();
        initializeLayout(simul);
        
        
        Turnout t = xnetManager.provideTurnout("XT5");

        Thread.sleep(100);
        
        simul.clearMesages();
        simul.setCaptureMessages(true);
        CountDownLatch l = new CountDownLatch(1);
        t.setCommandedState(XNetTurnout.THROWN);

        // wait > 5sec to capture a timeout
        Thread.sleep(6000);
        List<XNetReply> replies = simul.getIncomingReplies();
        
        assertFalse("Must not time out", timeoutOccured);
        // FIXME: there is a LOT of OK messages
        assertTrue(replies.size() > 1);
        assertEquals("Feedback reply expected", 0x42, replies.get(0).getElement(0));
    }
    
    /**
     * Checks that a sole OK response to a Turnout command is sufficient
     * to acknowledge the command message.
     */
//    @Test
    public void testInterfaceOKOnlyAccepted() throws Exception {
        XNetTestSimulator simul = new XNetTestSimulator.LZV100();
        initializeLayout(simul);
        
        Turnout t = xnetManager.provideTurnout("XT5");
        // excess OFF messages are sent
        Thread.sleep(500);
        t.setCommandedState(XNetTurnout.CLOSED);

        Thread.sleep(500);
        
        simul.clearMesages();
        simul.setCaptureMessages(true);
        CountDownLatch l = new CountDownLatch(1);
        t.setCommandedState(XNetTurnout.CLOSED);

        // wait > 5sec to capture a timeout
        Thread.sleep(6000);
        List<XNetReply> replies = simul.getIncomingReplies();
        
        assertFalse("Must not time out", timeoutOccured);
        // FIXME: there is a LOT of OK messages
        assertTrue(replies.size() > 1);
        for (XNetReply r : replies) {
            assertTrue("Only OKs are expected", r.isOkMessage());
        }
    }
    
    /**
     * Check that feedback+ok is processed before the command is acknowledged.
     */
    @Test
    public void testFeedbackAndOKProcessedBeforeNextCommand() throws Exception {
        
        XNetTestSimulator simul = new XNetTestSimulator.LZV100_USB();
        initializeLayout(simul, new TestUSBPacketizer(new LenzCommandStation()));
        
        Turnout t = xnetManager.provideTurnout("XT21");
        // excess OFF messages are sent
        Thread.sleep(500);
        t.setCommandedState(XNetTurnout.THROWN);

        Thread.sleep(500);
        /*
        simul.clearMesages();
        simul.setCaptureMessages(true);
        CountDownLatch l = new CountDownLatch(1);
        t.setCommandedState(XNetTurnout.THROWN);
        */
        Thread.sleep(6000);
        List<XNetReply> replies = simul.getIncomingReplies();
        System.err.println(replies);
    }

    /**
     * Checks that ok + feedback is processed before the command is acknowledged.
     * If feedback itself releases transmit thread, the OK may be paired to the next
     * message transmitted, and the transmit/receive message streams go out of sync.
     */
    @Test
    public void testOKAndFeedbackProcessedBeforeNextCommand() throws Exception {
        
    }
    
    /**
     * Checks that if a unsolicited layout feedback broadcast arrives concurrently
     * with a command sent to the layout, it won't be delivered as a reply
     * to the commanding Listener. The response thread should behave as if there
     * was no command sender at all.
     * @throws Exception 
     */
    public void testUnsolicitedFeedbackNotTargetedToCommande() throws Exception {
        
    }
}
