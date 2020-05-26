package jmri.jmrix.lenzplus.comm;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.JUnitTestBase;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class SchedulingQueueTest extends JUnitTestBase {
    
    SchedulingQueue q = new SchedulingQueue();
    
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
    CommandState a2On = createTurnout(1, false, true);
    CommandState aOff = createTurnout(1, true, false);
    CommandState bOn = createTurnout(5, false, true);

    public SchedulingQueueTest() {
    }
    
    @Before
    public void setUp() {
        super.setUp();
    }
    
    @After
    public void tearDown() {
        super.tearDown();
    }
    
    public void testAddSimple() {
        q.add(aOn, false);
        assertSame(aOn, q.poll());
        assertNull(q.poll());
    }
    
    @Test
    public void testAddDelayedFails() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            q.add(aOff, true);
        });
    }

    @Test
    public void testAddDelayed() throws Exception {
        Future<?> f;
        synchronized (q) {
            q.add(aOff, false);
            f = q.getFutureCommand(aOff);
            assertNotNull(f);
            assertFalse(f.isDone());
            assertFalse(f.isCancelled());
            
            assertNull(q.poll());
        }
        // must not throw timeout exception
        f.get(200, TimeUnit.MILLISECONDS);
        
        assertSame(aOff, q.poll());
    }

    @Test
    public void testDelayedWillBlockSlot() throws Exception {
        Future<?> f;
        q.add(aOn, false);
        synchronized (q) {
            q.add(aOff, false);
            f = q.getFutureCommand(aOff);
            assertNotNull(f);
            assertFalse(f.isDone());
            assertFalse(f.isCancelled());
            
            assertNull(q.poll());
        }
        // must not throw timeout exception
        f.get(200, TimeUnit.MILLISECONDS);
        
        assertSame(aOff, q.poll());
        assertSame(aOn, q.poll());
    }

    @Test
    public void testGetFutureCommand() {
        Future<?> f;
        synchronized (q) {
            q.add(bOn, false);
            f = q.getFutureCommand(bOn);
            assertNotNull(f);
            assertTrue(f.isDone());
        }
        synchronized (q) {
            q.add(aOff, false);
            f = q.getFutureCommand(aOff);
            assertNotNull(f);
        }
    }
    
    @Test
    public void testGetInconsistentCommand() throws Exception {
        Future<?> f = q.getFutureCommand(aOn);
        assertNotNull(f);
        assertThrows(ExecutionException.class, () -> f.get());
    }

    @Test
    public void testRemove() throws Exception {
        Future<?> f;
        synchronized (q) {
            q.add(aOff, false);
            q.add(a2On, false);
            f = q.getFutureCommand(aOff);
            assertNotNull(f);
            assertFalse(f.isDone());
            assertFalse(f.isCancelled());
            
            assertNull(q.poll());
            
            q.remove(aOff);
        }
        assertTrue(f.isDone());
        assertTrue(f.isCancelled());
        // check that a suspended message was unblocked.
        assertSame(a2On, q.poll());
    }

    @Test
    public void testCancel() {
        Future<?> f;
        synchronized (q) {
            q.add(aOff, false);
            q.add(a2On, false);
            f = q.getFutureCommand(aOff);
            assertNotNull(f);
            assertFalse(f.isDone());
            assertFalse(f.isCancelled());
            
            assertNull(q.poll());
        }
        
        q.cancel(aOff);
        assertFalse(aOff.getPhase().isActive());
        assertSame(a2On, q.poll());
    }
    
    @Test
    public void testCancelFinished() {
        Future<?> f;
        q.add(aOff, false);
        aOff.toPhase(Phase.QUEUED);
        aOff.toPhase(Phase.SENT);
        aOff.toPhase(Phase.CONFIRMED);
        aOff.toPhase(Phase.FINISHED);
        
        q.cancel(aOff);
        assertSame(Phase.FINISHED, aOff.getPhase());
    }
    
}
