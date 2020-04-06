/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import jmri.InstanceManager;
import jmri.Turnout;
import jmri.TurnoutManager;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.lenz.liusb.LIUSBXNetPacketizer;
import jmri.jmrix.lenz.xnetsimulator.XNetSimulatorAdapter;
import jmri.util.JUnitUtil;
import jmri.util.Log4JUtil;
import jmri.util.ThreadingUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class XNetTurnoutMonitoringTest {
    private final static Logger log = LoggerFactory.getLogger(XNetTurnoutMonitoringTest.class);

    protected XNetPacketizer lnis;

    protected Turnout t = null;	// holds object under test; set by setUp()
    
    protected XNetTestSimulator testAdapter;
    
    private Consumer<AbstractMRMessage> timeoutCallback;
    
    private XNetTurnoutManager xnetManager;
    
    @BeforeClass
    public static void setupLogging() {
        Log4JUtil.initLogging();
    }

    // The minimal setup for log4J
    @Before
    public void setUp() {
        jmri.util.JUnitUtil.setUp();
        // prepare an interface
        jmri.util.JUnitUtil.resetInstanceManager();
        jmri.util.JUnitUtil.initInternalSensorManager();
        jmri.util.JUnitUtil.initInternalTurnoutManager();
        jmri.InstanceManager.store(new jmri.NamedBeanHandleManager(), jmri.NamedBeanHandleManager.class);
    }
    
    class SerialPacketizer extends XNetPacketizer {
        SerialPacketizer() {
            super(new LenzCommandStation());
        }
        
        protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
            super.handleTimeout(msg, l);
            if (timeoutCallback != null) {
                timeoutCallback.accept(msg);
            }
        }
    }
    
    class USBPacketizer extends LIUSBXNetPacketizer {
        USBPacketizer() {
            super(new LenzCommandStation());
        }
        
        protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
            super.handleTimeout(msg, l);
            if (timeoutCallback != null) {
                timeoutCallback.accept(msg);
            }
        }
    }
    private void initializeLayout(XNetTestSimulator adapter) {
        initializeLayout(adapter, new SerialPacketizer());
    }
    
    private void initializeLayout(XNetTestSimulator adapter, XNetPacketizer packetizer) {
        testAdapter = adapter;
        lnis = packetizer;
        testAdapter.configure(lnis);
        
        xnetManager = (XNetTurnoutManager)InstanceManager.getDefault().getInstance(XNetSystemConnectionMemo.class).getTurnoutManager();
        t = xnetManager.provideTurnout("XT21");
    }

    @After
    public void tearDown() {
        t = null;
        lnis.disconnectPort(testAdapter);
        testAdapter.dispose();
        JUnitUtil.clearShutDownManager(); // put in place because AbstractMRTrafficController implementing subclass was not terminated properly
        JUnitUtil.tearDown();
    }
    
    private boolean timeoutReached;
    private volatile CountDownLatch timeoutLatch;
    
    /**
     * Tests that communication with GenLi will not result in a timeout.
     * Responses to XT21 and XT22 are likely to be incorrectly paired and the
     * system believes that it got a response to the second XT21 earlier than
     * it actually arrives. This will result in a timeout and a spurious
     * 5sec safety wait according to XpressNet spec.
     * 
     * @throws Exception 
     */
    @Test
    public void testGenLiSwitchTimeout() throws Exception {
        initializeLayout(new XNetTestSimulator.NanoXGenLi());
        
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");

        // wait for the t2 to initialize with feedback request to the layout.
        Thread.currentThread().sleep(1000);
        
        timeoutLatch = new CountDownLatch(1);
        timeoutCallback = (m) -> {
            timeoutReached = true;
            timeoutLatch.countDown();
        };

        log.info("Sending commands to the turnout");
        testAdapter.limitReplies = true;
        Thread.sleep(3000);
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.CLOSED);
        });
        ThreadingUtil.runOnLayout(() -> {        
            t2.setCommandedState(Turnout.THROWN);
        });
        ThreadingUtil.runOnLayout(() -> {        
            t2.setCommandedState(Turnout.THROWN);
        });
        ThreadingUtil.runOnLayout(() -> {        
            t.setCommandedState(Turnout.THROWN);
        });
        log.info("Unblocking responses");
        testAdapter.repliesAllowed.release(100);
        
        timeoutLatch.await(6000, TimeUnit.MILLISECONDS);
        if (timeoutReached) {
            Thread.currentThread().sleep(2000);
        }
        assertFalse("Timeout must not occur", timeoutReached);
    }
    
    /**
     * When two consecutive turnouts are operated, the 1st turnout's state
     * may change prematurely.
     * 
     * Two statuses are reported in a feedback broadcast message that confirms 
     * the operation actually contains two turnout statuses. The companion turnout
     * may react first. This testcase checks that a Turnout does not flip back/forth
     * as a result of incorrectly applied feedback change, while the turnout's
     * command is still not sent (but commanded state is already changed).
     * Spurious KnownState changes may result in extra property change processings.
     * 
     * @throws Exception 
     */
    @Test
    public void testGenLiIncorrectTurnoutState() throws Exception {
        initializeLayout(new XNetTestSimulator.NanoXGenLi());
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        Thread.currentThread().sleep(1000);
        
        // setup non-default state of t, let the process to settle.
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.THROWN);
        });
        Thread.currentThread().sleep(1000);
        
        assertEquals(Turnout.THROWN, t.getKnownState());
        
        testAdapter.limitReplies = true;
        
        // command t2 first; it's second in the pair
        ThreadingUtil.runOnLayout(() -> {
            t2.setCommandedState(Turnout.THROWN);
            t.setCommandedState(Turnout.CLOSED);
        });
        // esnure that both commands at processed (and maybe queued)
        // before first reply arrives
        testAdapter.repliesAllowed.release(1);
        
        // wait for the t2 command reply to be delivered; the t2 command still
        // waits as its reply to OFF is blocked.
        Thread.currentThread().sleep(5000);
        
        assertEquals(Turnout.INCONSISTENT, t.getKnownState());
    }
    
    static class PrimaryXNetReply extends XNetReply {

        public PrimaryXNetReply(XNetReply reply) {
            super(reply);
            reply.resetUnsolicited();
            if (reply.isUnsolicited()) {
                setUnsolicited();
            }
        }
        
    }
    
    /**
     * Checks that turnouts correctly reflect the state from the layout, especially 'shortly'
     * after their creation during startup sequence. After some initialization sequence
     * (i. e. startup script), the turnout's known state should match the commanded state.
     * @throws Exception 
     */
    @Test
    public void testFeedbackEvenTurnoutShortAfterBoot() throws Exception {
        initializeLayout(new XNetTestSimulator.NanoXGenLi());
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.CLOSED);
        });
        Thread.currentThread().sleep(1000);
        ThreadingUtil.runOnLayout(() -> {
            t2.setCommandedState(Turnout.CLOSED);
        });
        Thread.currentThread().sleep(2000);
        
        // setup non-default state of t, let the process to settle.
        ThreadingUtil.runOnLayout(() -> {
            t2.setCommandedState(Turnout.THROWN);
        });
        Thread.currentThread().sleep(1000);
        
        assertEquals(Turnout.CLOSED, t.getKnownState());
        assertEquals(Turnout.CLOSED, t.getCommandedState());
        assertEquals(Turnout.THROWN, t2.getKnownState());
        assertEquals(Turnout.THROWN, t2.getCommandedState());
    }
    
/*
    not finished yet
    public void testFeedbackEvenTurnout() throws Exception {
        initializeLayout(new GenLiTestSimulator());
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.CLOSED);
        });
        Thread.currentThread().sleep(3000);
        ThreadingUtil.runOnLayout(() -> {
            t2.setCommandedState(Turnout.THROWN);
        });
        Thread.currentThread().sleep(4000);
    }
    */
    
    public int getCommandedTurnout(XNetMessage msg) {
        if (msg.getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return -1;
        }
        int h = msg.getElement(1) * 4;
        int l = (msg.getElement(2) >> 1) & 0x03;
        return h + l + 1;
    }

    public int getCommandedOutput(XNetMessage msg) {
        if (msg.getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return -1;
        }
        return msg.getElement(2) & 0x01;
    }
    
    public boolean getCommandedState(XNetMessage msg) {
        if (msg.getElement(0) != XNetConstants.ACC_OPER_REQ) {
            return false;
        }
        return (msg.getElement(2) & 0x08) > 0;
    }
    
    @Test
    public void testDR5000Routetest() throws Exception {
        initializeLayout(new XNetTestSimulator.DR5000(), new USBPacketizer());
        
        XNetTurnout p1 = (XNetTurnout)xnetManager.provideTurnout("XT11");
        XNetTurnout p2 = (XNetTurnout)xnetManager.provideTurnout("XT12");
        XNetTurnout p3 = (XNetTurnout)xnetManager.provideTurnout("XT15");
        XNetTurnout p4 = (XNetTurnout)xnetManager.provideTurnout("XT16");
        
        Map<Integer, Integer> outMap = new HashMap<>();
        outMap.put(11, 0);
        outMap.put(12, 1);
        outMap.put(15, 0);
        outMap.put(16, 1);
        
        List<AssertionError> err = new ArrayList<>();
        
        class L implements XNetListener {
            volatile int turnoutCommands;
            
            @Override
            public void message(XNetReply msg) {
            }

            @Override
            public void message(XNetMessage msg) {
                try {
                    int tnt = getCommandedTurnout(msg);
                    if (tnt == -1) {
                        return;
                    }
                    assertTrue("Unexpected turnout: " + tnt, outMap.containsKey(tnt));
                    boolean s = getCommandedState(msg);
                    if (s) {
                        turnoutCommands++;
                    }
                    int o = getCommandedOutput(msg);
                    int eo = outMap.get(tnt);
                    assertEquals("Unexpected output " + o + " " + (s ? "ON" : "OFF") + " for turnout " + tnt, eo, o);
                } catch (AssertionError e) {
                    err.add(e);
                }
            }

            @Override
            public void notifyTimeout(XNetMessage msg) {
            }
        }
        L l = new L();
        lnis.addXNetListener(XNetTrafficController.ALL, l);
        
        Thread.sleep(1000);
        ThreadingUtil.runOnLayout(() -> {
            p1.setCommandedState(XNetTurnout.CLOSED);
        });
        ThreadingUtil.runOnLayout(() -> {
            p2.setCommandedState(XNetTurnout.THROWN);
        });
        ThreadingUtil.runOnLayout(() -> {
            p3.setCommandedState(XNetTurnout.CLOSED);
        });
        ThreadingUtil.runOnLayout(() -> {
            p4.setCommandedState(XNetTurnout.THROWN);
        });
        
        Thread.sleep(6000);
        
        assertEquals(4, l.turnoutCommands);
        assertEquals(Collections.emptyList(), err);
    }

    /**
     * Checks that sequence of commands to turnouts will leave turnouts
     * in the correct commanded state.
     * @throws Exception 
     */
    @Test
    public void testDR5000TurnoutMessage() throws Exception {
        initializeLayout(new XNetTestSimulator.LZV100(), new USBPacketizer());
        
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        t2.setCommandedState(Turnout.CLOSED);
        
        Thread.currentThread().sleep(3000);
        
        assertEquals(Turnout.CLOSED, t2.getCommandedState());
        
        log.debug("----------------------------------------------------");
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.THROWN);
            t2.setCommandedState(Turnout.THROWN);
        });
        log.debug("----------------------------------------------------");
        Thread.currentThread().sleep(10000);
        
        System.err.println("*** Commanded state for " + t2.getSystemName() + ": " + t2.getCommandedState());
        assertEquals(Turnout.THROWN, t2.getCommandedState());
    }
    
    /**
     * Checks that JMRI will not produce spurious timeouts 
     * when multiple active messages are in the transmit queue.
     * @throws Exception 
     */
    @Test
    public void testMultipleActiveMessages() throws Exception {
        initializeLayout(new XNetTestSimulator.NanoXGenLi());
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        t.setCommandedState(Turnout.CLOSED);
        Thread.currentThread().sleep(3000);
        
        timeoutLatch = new CountDownLatch(1);
        timeoutCallback = (m) -> {
            timeoutReached = true;
            timeoutLatch.countDown();
        };
        log.debug("----------------------------------------------------");
        
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        t2.setCommandedState(Turnout.THROWN);
        log.debug("----------------------------------------------------");
        Thread.currentThread().sleep(300);

        timeoutLatch.await(6000, TimeUnit.MILLISECONDS);
        if (timeoutReached) {
            Thread.currentThread().sleep(2000);
        }
        assertFalse("Timeout must not occur", timeoutReached);
    }
    
    static class GenLiTestSimulator extends TurnoutTestSimulator {
        protected XNetReply generateAccRequestReply(int address, int output, boolean state) {
            if (state) {
                return accInfoReply(address);
            } else {
                return okReply();
            }
        }
    }
    
    static class DR5000TestSimulator extends TurnoutTestSimulator {
        @Override
        protected XNetReply generateAccRequestReply(int address, int output, boolean state) {
            if (state) {
                addReply(accInfoReply(address));
                return okReply();
            } else {
                return okReply();
            }
        }

        @Override
        protected int getTurnoutFeedbackType() {
            return 0;
        }
    }
    
    static abstract class TurnoutTestSimulator extends XNetSimulatorAdapter {
        private List<XNetReply>   replyBuffer = new ArrayList<>();
        private List<XNetReply>   additionalReplies = new ArrayList<>();
        
        private BitSet  accessoryState = new BitSet(1024);
        
        volatile boolean    limitReplies;
        private Semaphore   repliesAllowed = new Semaphore(0);

        private void insertAdditionalReplies() {
            replyBuffer.addAll(additionalReplies);
            additionalReplies.clear();
        }
        
        protected XNetReply addReply(XNetReply r) {
            additionalReplies.add(r);
            return r;
        }
        
        public void configure(XNetTrafficController ctrls) {
            super.configure(ctrls);
        }
        
        private void maybeWaitBeforeReply(XNetReply reply) {
            if (!limitReplies) {
                return;
            }
            if (reply instanceof PrimaryXNetReply) {
                try {
                    repliesAllowed.acquire();
                } catch (InterruptedException ex) {
                    java.util.logging.Logger.getLogger(XNetTurnoutMonitoringTest.class.getName()).log(Level.SEVERE, null, ex);
                }

            }
        }
        
        /**
         * Serves the bateched items through FIFO. The test class may generate
         * additional replies, which are ordered after the primary one.
         * 
         * @param m XNet message instance
         * @return the current reply
         */
        @Override
        protected XNetReply generateReply(XNetMessage m) {
            insertAdditionalReplies();
            if (m == null) {
                if (replyBuffer.isEmpty()) {
                    return null;
                }
                XNetReply r = replyBuffer.remove(0);
                maybeWaitBeforeReply(r);
                System.err.println("Returning reply: " + r + " ... " + r.toMonitorString());
                return r;
            }
            XNetReply reply  = super.generateReply(m);
            if (isPrimaryReply(m)) {
                reply = new PrimaryXNetReply(reply);
            }
            if (replyBuffer.isEmpty()) {
                insertAdditionalReplies();
                maybeWaitBeforeReply(reply);
                System.err.println("Returning reply: " + reply + " ... " + reply.toMonitorString());
                return reply;
            }
            replyBuffer.add(reply);
            insertAdditionalReplies();
            XNetReply r = replyBuffer.remove(0);
            maybeWaitBeforeReply(reply);
            return r;
        }
        
        protected boolean isPrimaryReply(XNetMessage msg) {
            return msg.getElement(0)== XNetConstants.ACC_OPER_REQ;
        }

        @Override
        protected XNetReply accReqReply(XNetMessage m) {
            int baseaddress = m.getElement(1);
            int subaddress = ((m.getElement(2) & 0x06) >> 1);
            int address = (baseaddress * 4) + subaddress + 1;
            int output = (m.getElement(2) & 0x01);
            boolean on = ((m.getElement(2) & 0x08)) == 0x08;
            if (on) {
                accessoryState.set(address, output != 0);
            }
            System.err.println(m + " ..." + m.toMonitorString());
            return generateAccRequestReply(address, output, on);
        }
        
        protected abstract XNetReply generateAccRequestReply(int address, int output, boolean state);

        protected XNetReply accInfoReply(int dccTurnoutAddress) {
            dccTurnoutAddress--;
            int baseAddress = dccTurnoutAddress / 4;
            boolean upperNibble = (dccTurnoutAddress % 4 >= 2);
            return accInfoReply(true, baseAddress, upperNibble);
        }

        @Override
        protected XNetReply accInfoReply(XNetMessage m) {
            boolean nibble = (m.getElement(2) & 0x01) == 0x01;
            int ba = m.getElement(1);
            return accInfoReply(false, ba, nibble);
        }
        
        /**
         * Return the turnout feedback type.
         * <ul>
         * <li>0x00 - turnout without feedback, ie DR5000
         * <li>0x01 - turnout with feedback, ie NanoX
         * <li>0x10 - feedback module
         * </ul>
         * @return 
         */
        protected int getTurnoutFeedbackType() {
            return 0x01;
        }
        
        XNetReply accInfoReply(boolean broadcast, int baseAddress, boolean nibble) {
            XNetReply r = new XNetReply();
            r.setOpCode(broadcast ? XNetConstants.ACC_INFO_RESPONSE : XNetConstants.ACC_INFO_RESPONSE);
            r.setElement(1, baseAddress);
            
            int nibbleVal = 0;
            
            int a = baseAddress * 4 + 1;
            if (nibble) {
                a += 2;
            }
            boolean state = accessoryState.get(a++);
            int zbits = state ? 0b10 : 0b01;
            
            nibbleVal |= zbits;
            
            state = accessoryState.get(a++);
            zbits = state ? 0b10 : 0b01;
            
            nibbleVal |= (zbits << 2);
            
            r.setElement(2, 
                    0       << 7 |  // turnout movement completed
                    getTurnoutFeedbackType()    << 5 |  // two bits: accessory without feedback
                    (nibble ? 1 : 0) << 4 | // upper / lower nibble
                    nibbleVal & 0x0f
            );
            r.setElement(3, 0);
            r.setParity();
            return r;
        }
        
    }

}
