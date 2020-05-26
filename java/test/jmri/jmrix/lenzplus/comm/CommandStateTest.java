package jmri.jmrix.lenzplus.comm;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Mainly tests the state machine transitions.
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class CommandStateTest {
    
    private static final Map<Phase, EnumSet<Phase>> PERMITTED_CHANGES = new HashMap<>();
    
    private static void addTransition(Phase from, Phase... to) {
        EnumSet<Phase> cur = PERMITTED_CHANGES.computeIfAbsent(from, 
                f -> EnumSet.noneOf(Phase.class));
        cur.addAll(Arrays.asList(to));
    }
    
    private static void addTarget(EnumSet<Phase> from, Phase to) {
        for (Phase p : from) {
            addTransition(p, to);
        }
    }
    
    static {
        addTransition(Phase.CREATED, Phase.QUEUED, Phase.SCHEDULED, Phase.BLOCKED);
        // queue operations:
        addTransition(Phase.SCHEDULED, Phase.QUEUED, Phase.BLOCKED);
        addTransition(Phase.QUEUED, Phase.BLOCKED, Phase.SENT);
        addTransition(Phase.BLOCKED, Phase.QUEUED);
        // transmission:
        addTransition(Phase.SENT, Phase.CONFIRMED, Phase.REJECTED);
        addTransition(Phase.REJECTED, Phase.REPLAYING);
        addTransition(Phase.REPLAYING, Phase.SENT);
        // confirmation:
        addTransition(Phase.CONFIRMED, Phase.CONFIRMED_AGAIN, Phase.FINISHED);
        // special, remains at confirmed_again, but does not throw
        addTransition(Phase.CONFIRMED_AGAIN, Phase.CONFIRMED, Phase.FINISHED);
        
        EnumSet<Phase> almostAll = EnumSet.allOf(Phase.class);
        almostAll.remove(Phase.FINISHED);
        almostAll.remove(Phase.EXPIRED);
        
        addTarget(almostAll, Phase.EXPIRED);
        addTarget(almostAll, Phase.FINISHED);
        
        addTransition(Phase.SENT, Phase.FAILED);
    }
    
    public CommandStateTest() {
    }
    
    @Before
    public void setUp() {
        JUnitUtil.setUp();
    }
    
    @After
    public void tearDown() {
        JUnitUtil.tearDown();
    }

    @Test
    public void testGetTimeQueued() throws Exception {
        CommandState inst = new CommandState(new XNetPlusMessage());
        
        assertEquals(0, inst.getTimeQueued());
        inst.toPhase(Phase.QUEUED);
        long tms = inst.getTimeQueued();
        assertNotEquals(0, tms);
        
        // simulate block-and-unblock:
        inst.toPhase(Phase.BLOCKED);
        Thread.sleep(10);
        inst.toPhase(Phase.QUEUED);
        long tms2 = inst.getTimeQueued();
        assertNotEquals(0, tms2);
        assertNotEquals(tms, tms2);
        
        // simulate sent-and-replay
        inst.toPhase(Phase.SENT);
        Thread.sleep(10);
        inst.toPhase(Phase.REJECTED);
        inst.toPhase(Phase.REPLAYING);
        long tms3 = inst.getTimeQueued();
        assertNotEquals(0, tms3);
        assertNotEquals(tms2, tms3);
    }

    @Test
    public void testGetTimeSent() throws Exception {
        CommandState inst = new CommandState(new XNetPlusMessage());
        inst.toPhase(Phase.QUEUED);

        assertEquals(0, inst.getTimeSent());
        
        // simulate sent-and-replay
        inst.toPhase(Phase.SENT);
        long tms = inst.getTimeSent();
        assertNotEquals(0, tms);

        inst.toPhase(Phase.REJECTED);
        inst.toPhase(Phase.REPLAYING);
        Thread.sleep(10);
        inst.toPhase(Phase.SENT);
        long tms2 = inst.getTimeSent();
        assertNotEquals(0, tms2);
        assertNotEquals(tms, tms2);
    }

    @Test
    public void testGetTimeConfirmed() throws Exception {
        CommandState inst = new CommandState(new XNetPlusMessage());
        inst.toPhase(Phase.QUEUED);
        inst.toPhase(Phase.SENT);

        assertEquals(0, inst.getTimeConfirmed());
        
        // simulate sent-and-replay
        inst.toPhase(Phase.CONFIRMED);
        long tms = inst.getTimeConfirmed();
        assertNotEquals(0, tms);

        Thread.sleep(10);
        inst.toPhase(Phase.CONFIRMED_AGAIN);
        long tms2 = inst.getTimeConfirmed();
        assertEquals(tms, tms2);
    }
    
    private <T> Iterable<T> sorted(Collection<T> c) {
        Stream<T> s = c.stream().sorted();
        return s::iterator;
    }

    @Test
    public void testToPhase() {
        for (Phase from : sorted(PERMITTED_CHANGES.keySet())) {
            for (Phase to : PERMITTED_CHANGES.get(from)) {
                CommandState inst = new CommandState(new XNetPlusMessage());
                inst.toPhaseInternal(from);
                inst.toPhase(to);
            }
        }
        
        for (Phase from : sorted(PERMITTED_CHANGES.keySet())) {
            EnumSet<Phase> notPermitted = EnumSet.allOf(Phase.class);
            notPermitted.removeAll(PERMITTED_CHANGES.getOrDefault(from, EnumSet.noneOf(Phase.class)));
            notPermitted.remove(from);
            for (Phase to : sorted(notPermitted)) {
                CommandState inst = new CommandState(new XNetPlusMessage());
                inst.toPhaseInternal(from);
                if (from.isFinal()) {
                    assertFalse(inst.toPhase(to));
                } else {
                    assertThrows("Must not permit " + from + " -> " + to, 
                        IllegalArgumentException.class, () -> 
                            inst.toPhase(to));
                }
            }
        }
        
        CommandState inst = new CommandState(new XNetPlusMessage());
        inst.toPhase(Phase.QUEUED);
        inst.toPhase(Phase.SENT);
        
        inst.toPhase(Phase.CONFIRMED);
        assertEquals(Phase.CONFIRMED, inst.getPhase());
        inst.toPhase(Phase.CONFIRMED);
        assertEquals(Phase.CONFIRMED_AGAIN, inst.getPhase());
        inst.toPhase(Phase.CONFIRMED);
        assertEquals(Phase.CONFIRMED_AGAIN, inst.getPhase());
    }

    @Test
    public void testSetStampOnce() {
        CommandState inst = new CommandState(new XNetPlusMessage());
        assertEquals(0, inst.getStamp());
        
        inst.setStampOnce(1);
        assertEquals(1, inst.getStamp());
        inst.setStampOnce(2);
        assertEquals(1, inst.getStamp());
    }
}
