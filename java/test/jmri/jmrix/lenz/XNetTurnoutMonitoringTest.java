/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jmri.InstanceManager;
import jmri.Turnout;
import jmri.TurnoutManager;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.lenz.XNetTestSimulator.DR5000;
import jmri.jmrix.lenz.XNetTestSimulator.NanoXGenLi;
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
    
    private void initializeLayout(XNetTestSimulator adapter) {
        testAdapter = adapter;
        lnis = new XNetPacketizer(new LenzCommandStation()) {
            protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
                super.handleTimeout(msg, l);
                if (timeoutCallback != null) {
                    timeoutCallback.accept(msg);
                }
            }
        };
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
//    @Test
    public void testGenLiSwitchTimeout() throws Exception {
        initializeLayout(new NanoXGenLi());
        
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
//    @Test
    public void testGenLiIncorrectTurnoutState() throws Exception {
        initializeLayout(new NanoXGenLi());
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
        }
        
    }
    
    /**
     * Checks that turnouts correctly reflect the state from the layout, especially 'shortly'
     * after their creation during startup sequence. After some initialization sequence
     * (i. e. startup script), the turnout's known state should match the commanded state.
     * @throws Exception 
     */
//    @Test
    public void testFeedbackEvenTurnoutShortAfterBoot() throws Exception {
        initializeLayout(new NanoXGenLi());
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

    /**
     * Checks that sequence of commands to turnouts will leave turnouts
     * in the correct commanded state.
     * @throws Exception 
     */
    @Test
    public void testDR5000TurnoutMessage() throws Exception {
        initializeLayout(new DR5000());
        
        TurnoutManager mgr = InstanceManager.getDefault().getInstance(TurnoutManager.class);
        XNetTurnout t2 = (XNetTurnout)xnetManager.provideTurnout("XT22");
        t.setCommandedState(Turnout.CLOSED);
        t2.setCommandedState(Turnout.THROWN);
        
        Thread.currentThread().sleep(3000);
        
//        assertEquals(Turnout.CLOSED, t2.getCommandedState());
        
        log.debug("----------------------------------------------------");
        ThreadingUtil.runOnLayout(() -> {
            t.setCommandedState(Turnout.THROWN);
            t2.setCommandedState(Turnout.CLOSED);
        });
        log.debug("----------------------------------------------------");
        Thread.currentThread().sleep(1000000);
        
        System.err.println("*** Commanded state for " + t2.getSystemName() + ": " + t2.getCommandedState());
        assertEquals(Turnout.THROWN, t2.getCommandedState());
    }
    
    /**
     * Checks that JMRI will not produce spurious timeouts 
     * when multiple active messages are in the transmit queue.
     * @throws Exception 
     */
//    @Test
    public void testMultipleActiveMessages() throws Exception {
        initializeLayout(new NanoXGenLi());
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
    

}
