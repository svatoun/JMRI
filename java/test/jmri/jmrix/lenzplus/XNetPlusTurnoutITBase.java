package jmri.jmrix.lenzplus;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jmri.Turnout;
import jmri.jmrix.lenz.XNetInterface;
import jmri.jmrix.lenz.XNetTurnout;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenz.XNetTestSimulator;
import jmri.jmrix.lenz.XNetTrafficController;
import jmri.jmrix.lenzplus.port.DefaultPacketizerSupport;
import jmri.jmrix.lenzplus.port.USBPacketizerSupport;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutITBase extends IntegrationTestBase {
    private XNetPlusTurnout t11;
    private XNetPlusTurnout t12;
    
    static class PropL implements PropertyChangeListener {
        Map<String, List> changeList = new HashMap<>();

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            String s = evt.getPropertyName();
            if (s == null) {
                return;
            }
            synchronized (this) {
                changeList.computeIfAbsent(s, x -> new ArrayList()).add(evt.getNewValue());
            }
        }
        
        synchronized List getPropertyChanges(String prop) {
            return new ArrayList<>(changeList.getOrDefault(prop, Collections.emptyList()));
        }
    }
    
    public void setupTurnouts() throws Exception {
        t11 = (XNetPlusTurnout)xnetManager.provideTurnout("11");
        t12 = (XNetPlusTurnout)xnetManager.provideTurnout("12");
        t11.setFeedbackMode(Turnout.MONITORING);
        t12.setFeedbackMode(Turnout.MONITORING);
        
        testAdapter.drainPackets(true);
    }
    
    @Before
    public void initSimulator() throws Exception {
//        initializeLayout(new XNetTestSimulator.LZV100_USB(true), new USBPacketizerSupport());
        initializeLayout(new XNetTestSimulator.NanoXGenLi(true), new DefaultPacketizerSupport());
    }

    @Test
    public void testConsecutiveTurnouts() throws Exception {
        setupTurnouts();
        
        testAdapter.drainPackets(true);
        
        testAdapter.setCaptureMessages(true);
        
        PropL t11L = new PropL();
        PropL t12L = new PropL();
        
        t11.addPropertyChangeListener(t11L);
        t12.addPropertyChangeListener(t12L);
        
        int initCommanded = t11.getCommandedState();

        t11.setCommandedState(Turnout.CLOSED);
        t12.setCommandedState(Turnout.THROWN);
        
        t11.setCommandedState(Turnout.THROWN);
        t12.setCommandedState(Turnout.CLOSED);
        
        Thread.sleep(100);
        
        testAdapter.drainPackets(false);
        
        List<XNetMessage> commands = testAdapter.getOutgoingMessages();
        List<XNetReply> replies = testAdapter.getIncomingReplies();
        
        // check final state:
        assertEquals(Turnout.CLOSED, t12.getKnownState());
        assertEquals(Turnout.CLOSED, t12.getCommandedState());
        
        assertEquals(Turnout.THROWN, t11.getKnownState());
        assertEquals(Turnout.THROWN, t11.getCommandedState());
        
        // check property change event sequences
        if (initCommanded == Turnout.UNKNOWN) {
            assertEquals(Arrays.asList(2, 4), t11L.getPropertyChanges("CommandedState")); 
        } else {
            // XT21 was initially CLOSED; so (closed) -> (no change) -> THROWN
            assertEquals(Arrays.asList(4), t11L.getPropertyChanges("CommandedState"));
        }
        // XT22 was initially CLOSED; so (closed) -> THROWN -> CLOSED
        assertEquals(Arrays.asList(4, 2), t12L.getPropertyChanges("CommandedState"));
        
        // XT21 was commanded -> 8, then 2, 4
        assertEquals(Arrays.asList(8, 2, 4), t11L.getPropertyChanges("KnownState"));
        assertEquals(Arrays.asList(8, 4, 2), t12L.getPropertyChanges("KnownState"));
    }


    @Test
    public void testRoutetest() throws Exception {
        setupTurnouts();
        
        XNetTurnout p1 = t11;
        XNetTurnout p2 = t12;
        XNetTurnout p3 = (XNetTurnout)xnetManager.provideTurnout("15");
        XNetTurnout p4 = (XNetTurnout)xnetManager.provideTurnout("16");
        
        p3.setFeedbackMode(Turnout.MONITORING);
        p4.setFeedbackMode(Turnout.MONITORING);
        
        p1.setCommandedState(2);
        p2.setCommandedState(4);
        p3.setCommandedState(2);
        p4.setCommandedState(2);
        
        Thread.sleep(5000);
//        log.debug("---------------------------- start ------------------");
        
        Map<Integer, Integer> outMap = new HashMap<>();
        outMap.put(11, 1);
        outMap.put(12, 0);
        outMap.put(15, 1);
        outMap.put(16, 1);
        
        List<AssertionError> err = new ArrayList<>();
        
        class L implements XNetPlusListener {
            volatile int turnoutCommands;
            
            @Override
            public void message(XNetPlusReply msg) {
            }

            @Override
            public void message(XNetPlusMessage msg) {
                try {
                    int tnt = msg.getCommandedAccessoryNumber();
                    if (tnt == -1) {
                        return;
                    }
                    assertTrue("Unexpected turnout: " + tnt, outMap.containsKey(tnt));
                    boolean s = msg.getCommandedOutputState();
                    if (s) {
                        turnoutCommands++;
                    }
                    int o = msg.getCommandedTurnoutStatus() == Turnout.CLOSED ? 0 : 1;
                    int eo = outMap.get(tnt);
                    assertEquals("Unexpected output " + o + " " + (s ? "ON" : "OFF") + " for turnout " + tnt, eo, o);
                } catch (AssertionError e) {
                    err.add(e);
                }
            }

            @Override
            public void notifyTimeout(XNetPlusMessage msg) {
            }
        }

        L l = new L();
        lnis.addXNetListener(XNetTrafficController.ALL, l);
        
        Thread.sleep(1000);
        p1.setCommandedState(XNetTurnout.THROWN);
        p2.setCommandedState(XNetTurnout.CLOSED);
        p3.setCommandedState(XNetTurnout.THROWN);
        p4.setCommandedState(XNetTurnout.THROWN);
        
        Thread.sleep(6000);
        
        assertFalse(lnis.timeoutOccured);
        assertEquals(4, l.turnoutCommands);
        assertEquals(Collections.emptyList(), err);
    }

    /**
     * Checks that a timeout makes the turnout to send an OFF message.
     */
    @Test
    public void testTimeoutSendsOff() throws Exception {
        setupTurnouts();
        class XL implements XNetPlusListener {
            final CountDownLatch l = new CountDownLatch(2);
            volatile XNetMessage msg;
            volatile XNetReply reply;
            
            @Override
            public void message(XNetPlusReply reply) {
                this.reply = reply;
                l.countDown();
            }

            @Override
            public void message(XNetPlusMessage msg) {
            }

            @Override
            public void notifyTimeout(XNetPlusMessage msg) {
                this.msg = msg;
                l.countDown();
            }
        }
        
        XL xl = new XL();
        lnis.addXNetListener(XNetInterface.ALL, xl);

        testAdapter.setCaptureMessages(true);
        // discard the reply
        testAdapter.setNextReply(XNetTestSimulator.SKIP);
        
        t11.setCommandedState(Turnout.THROWN);
        assertTrue(xl.l.await(7, TimeUnit.SECONDS));

        // timeout must be reported
        assertNotNull(xl.msg);
        // reply to OFF should be there
        assertNotNull(xl.reply);
        
        List<XNetMessage> msgs = testAdapter.getOutgoingMessages();
        List<XNetReply> replies = testAdapter.getIncomingReplies();
        
        System.err.println("hello");
        
        // command (timed out) and an OFF should be sent
        assertEquals(2, msgs.size());
        // just OK is ACKed
        assertEquals(1, replies.size());
        XNetPlusReply r = XNetPlusReply.create(replies.get(0));
        assertTrue(r.isOkMessage());
        assertNotNull(r.getResponseTo());
        // check it is the correct output/off state
        assertEquals(0x85, r.getResponseTo().getElement(2));
    }
}
