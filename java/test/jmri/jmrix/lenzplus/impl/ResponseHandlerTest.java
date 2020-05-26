package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jmri.jmrix.AbstractMRTrafficController;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.StateMemento;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class ResponseHandlerTest implements ReplySource, ReplyDispatcher {
    private TestMemento initialMemento = new TestMemento();
    private ResponseHandler instance = new ResponseHandler(this, this);
    private ExecutorService exec = Executors.newSingleThreadExecutor();
    
    private volatile boolean active = true;
    
    private LinkedBlockingQueue<XNetPlusReply> lbq = new LinkedBlockingQueue<>();
    private TestMemento finalMemento;
    private volatile boolean wakeUp;
    
    private static final XNetPlusReply NULL = new XNetPlusReply();
    
    private final Semaphore distributeSemaphore = new Semaphore(0);
    private final Semaphore takeSemaphore = new Semaphore(1000);
    
    public ResponseHandlerTest() {
    }
    
    private static XNetPlusReply newXNetPlusReply(String s) {
        return XNetPlusAccess.createReply(s);
    }
    
    class ReplySourceAdapter implements ReplySource {
        @Override
        public XNetPlusReply takeWithTimeout(int timeout) throws IOException {
            return ResponseHandlerTest.this.takeWithTimeout(timeout);
        }

        @Override
        public boolean isActive() {
            return ResponseHandlerTest.this.isActive();
        }

        @Override
        public void resetExpectedReply(XNetPlusReply reply) {
            ResponseHandlerTest.this.resetExpectedReply(reply);
        }
    }
    
    @Before
    public void setUp() {
    }
    
    @After
    public void tearDown() {
        active = false;
        lbq.offer(NULL);
    }
    
    private volatile int takeCalled;
    
    @Override
    public XNetPlusReply takeWithTimeout(int timeout) throws IOException {
        XNetPlusReply r;
        takeCalled++;
        try {
            takeSemaphore.acquire();
        }
        catch (InterruptedException ex) {
            throw new InterruptedIOException();
        }
        try {
            if (timeout == 0) {
                r = lbq.take();
            } else {
                r = lbq.poll(timeout, TimeUnit.MILLISECONDS);
            }
        }
        catch (InterruptedException ex) {
            Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
        return r == NULL ? null : r;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void resetExpectedReply(XNetPlusReply reply) {
    }
    
    private volatile int snapshotCalled;

    @Override
    public synchronized void snapshot(StateMemento m) {
        snapshotCalled++;
        copyState(m, initialMemento);
    }
    
    static class TestMemento extends StateMemento {

        public int getRetransmitCount() {
            return retransmitCount;
        }

        public int getmCurrentState() {
            return mCurrentState;
        }

        public int getmCurrentMode() {
            return mCurrentMode;
        }

        public void setRetransmitCount(int retransmitCount) {
            this.retransmitCount = retransmitCount;
        }

        public void setmCurrentState(int mCurrentState) {
            this.mCurrentState = mCurrentState;
        }

        public void setmCurrentMode(int mCurrentMode) {
            this.mCurrentMode = mCurrentMode;
        }
        
        public void copyState(StateMemento to, StateMemento from) {
            super.copyState(to, from);
        }

        public void copyState(StateMemento from) {
            super.copyState(this, from);
        }
    }
    
    private void copyState(StateMemento to, StateMemento from) {
        new StateMemento() {
            {
                copyState(to, from);
            }
        };
    }

    @Override
    public void update(StateMemento m) {
    }
    
    @Override
    public void commit(StateMemento m, boolean wakeUp) {
        finalMemento = new TestMemento();
        finalMemento.copyState(m);
        this.wakeUp = wakeUp;
    }
    
    private volatile int distributeCalled;
    
    private ReplyOutcome nextOutcome;
    private ReplyOutcome finishedOutcome;

    @Override
    public ReplyOutcome distributeReply(StateMemento m, XNetPlusReply msg, XNetListener target) {
        distributeCalled++;
        distributeSemaphore.release();
        
        ReplyOutcome o;
        synchronized (this) {
            if (nextOutcome != null) {
                o = nextOutcome;
                nextOutcome = null;
                return o;
            }
        }
        return ReplyOutcome.finished(msg);
    }

    @Override
    public void commandFinished(StateMemento m, ReplyOutcome outcome) {
        finishedOutcome = outcome;
    }
    
    @Test
    public void testGetFollowupTimeout() {
        instance = new ResponseHandler(this, this);
        assertFalse(instance.getFollowupTimeout() == 0);
    }

    @Test
    public void testSetFollowupTimeout() {
    }
    
    private ReplyOutcome outcome;

    /**
     * Checks that the loop will eat up all unsolicited messages, and will
     * stop on a first message that is solicited.
     */
    @Test
    public void testLoopUnsolicitedMessages() throws Exception {
        Future<ReplyOutcome> handle = exec.submit(() -> {
            try {
                return outcome = instance.loopUnsolicitedMessages();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new ReplyOutcome(NULL);
        });
        Thread.sleep(Math.max(300, instance.getFollowupTimeout() * 2));
        assertNull(outcome);
        assertFalse(handle.isDone());

        XNetPlusReply first = newXNetPlusReply("01 04 05");
        assertTrue(first.isUnsolicited());
        // the ReplyOutcome served by default is unsolicited, finished.
        lbq.offer(first);
        assertTrue(distributeSemaphore.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> handle.get(50, TimeUnit.MILLISECONDS));
        // the reply was consumed, but since the reply was not solicited,
        // the loop continues.
        assertFalse(handle.isDone());
        
        // try again
        lbq.offer(first);
        assertTrue(distributeSemaphore.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> handle.get(50, TimeUnit.MILLISECONDS));
        assertFalse(handle.isDone());
        
        // make the message solicited
        XNetPlusReply last = newXNetPlusReply("01 04 05");
        XNetPlusMessage cmd = new XNetPlusMessage("52 01 00 01");
        last.setResponseTo(cmd);
        
        CommandState st = new CommandState(cmd);
        nextOutcome = ReplyOutcome.finished(st, last);
        
        lbq.offer(last);
        assertTrue(distributeSemaphore.tryAcquire(50, TimeUnit.MILLISECONDS));
        outcome = handle.get(50, TimeUnit.MILLISECONDS);
        assertTrue(outcome.isComplete());
    }

    /**
     * Checks that a solicited, completed reply will not read a subsequent
     * reply even though one is available.
     */
    @Test
    public void testSolicitedReadsOneReply() throws Exception {
        takeSemaphore.drainPermits();
        
        Future<ReplyOutcome> handle = exec.submit(() -> {
            try {
                return outcome = instance.loopUnsolicitedMessages();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new ReplyOutcome(NULL);
        });
        
        // the loop will block before first take from the queue.
        XNetPlusReply second = newXNetPlusReply("01 04 05");
        second.setUnsolicited();
        
        XNetPlusReply completed = newXNetPlusReply("01 04 05");
        XNetPlusMessage cmd = new XNetPlusMessage("52 01 00 01");
        completed.setResponseTo(cmd);
        CommandState st = new CommandState(cmd);
        
        // prepare two messages into the queue.
        lbq.offer(completed);
        lbq.offer(second);
        // outcome for the first message, reply must be attached to a command.
        nextOutcome = ReplyOutcome.finished(st, completed);
        
        // release the loop thread
        takeSemaphore.release(1000);
        
        // should complete very soon:
        outcome = handle.get(50, TimeUnit.MILLISECONDS);
        // wait some more to allow "bad" take(s) to occur:
        Thread.sleep(300);
        assertEquals("No more messages should be read", 1, takeCalled);
        
    }

    /**
     * Checks that an incomplete message will continue to read without a timeout.
     * @throws Exception 
     */
    @Test
    public void testIncompleteRequiredMessageWillBlock() throws Exception {
        takeSemaphore.drainPermits();
        Future<Void> handle = exec.submit(() -> {
            try {
                instance.handleOneIncomingReply();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return null;
        });
        
        // the loop will block before first take from the queue.
        XNetPlusReply second = newXNetPlusReply("01 04 05");
        second.setUnsolicited();
        
        XNetPlusReply incomplete = newXNetPlusReply("01 04 05");
        XNetPlusMessage cmd = new XNetPlusMessage("52 01 00 01");
        incomplete.setResponseTo(cmd);
        second.setResponseTo(cmd);
        CommandState st = new CommandState(cmd);
        
        // prepare two messages into the queue.
        lbq.offer(incomplete);
        lbq.offer(second);

        // outcome for the first message, reply must be attached to a command.
        nextOutcome = new ReplyOutcome(st, incomplete);
        nextOutcome.setAdditionalReplyRequired(true);
        takeSemaphore.release();
        
        // the handle must not complete yet, allow for some expiration to take place
        assertThrows(TimeoutException.class, () -> handle.get(150, TimeUnit.MILLISECONDS));
        assertFalse(handle.isDone());
        
        nextOutcome = new ReplyOutcome(st, incomplete);
        nextOutcome.setAdditionalReplyRequired(true);
        takeSemaphore.release();
        assertThrows(TimeoutException.class, () -> handle.get(150, TimeUnit.MILLISECONDS));
        assertFalse(handle.isDone());
        
        XNetPlusReply last = newXNetPlusReply("01 04 05");
        last.setResponseTo(cmd);
        nextOutcome = ReplyOutcome.finished(st, last);
        takeSemaphore.release();
        lbq.offer(last);
        
        handle.get(50, TimeUnit.MILLISECONDS);
        // 3 messages should be asked for:
        assertEquals(3, takeCalled);
        // 3 messages should be distributed:
        assertEquals(3, distributeCalled);
    }

    /**
     * Checks that an incomplete message with just optionally expected reply
     * will finish if no reply comes.
     */
    @Test
    public void testIncompleteOptionalReplyTimesOut() throws Exception {
        takeSemaphore.drainPermits();
        Future<Void> handle = exec.submit(() -> {
            try {
                instance.handleOneIncomingReply();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return null;
        });
        
        XNetPlusReply incomplete = newXNetPlusReply("01 04 05");
        XNetPlusMessage cmd = new XNetPlusMessage("52 01 00 01");
        incomplete.setResponseTo(cmd);
        CommandState st = new CommandState(cmd);
        
        // prepare two messages into the queue.
        lbq.offer(incomplete);

        // outcome for the first message, reply must be attached to a command.
        nextOutcome = new ReplyOutcome(st, incomplete);
        nextOutcome.setAdditionalReplyRequired(false);
        takeSemaphore.release(20);
        
        // the handle must not complete yet, allow for some expiration to take place
        handle.get(150, TimeUnit.MILLISECONDS);
        assertTrue(handle.isDone());
        
        // one message distributed,
        assertEquals(1, distributeCalled);
        // but two messages asked for:
        assertEquals(2, takeCalled);
    }

    @Test
    public void testNoTransitionOnUnsolicited() {
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITMSGREPLYSTATE);
        XNetPlusReply reply = newXNetPlusReply("42 05 2B 0f");
        snapshot(instance);
        
        assertFalse(reply.isBroadcast());
        assertTrue(reply.isUnsolicited());
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(initialMemento.getmCurrentState(), finalMemento.getmCurrentState());
        assertFalse(wakeUp);

        instance = new ResponseHandler(this, this);
        snapshot(instance);
        XNetReply base = new XNetReply("42 05 2B 0f");
        base.setUnsolicited();
        // hack: the constructor deciphers the unsolicited to broadcast
        reply = XNetPlusReply.create(base);
        reply.setUnsolicited();
        assertTrue(reply.isBroadcast());
        assertTrue(reply.isUnsolicited());
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(initialMemento.getmCurrentState(), finalMemento.getmCurrentState());
        assertFalse(wakeUp);
        
        instance = new ResponseHandler(this, this);
        snapshot(instance);
        reply = XNetPlusReply.create(base);
        reply.markSolicited(true);
        assertTrue(reply.isBroadcast());
        assertFalse(reply.isUnsolicited());
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(initialMemento.getmCurrentState(), finalMemento.getmCurrentState());
        assertFalse(wakeUp);
    }
    
    
    @Test
    public void testTransitionFromWaitMsg() {
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITMSGREPLYSTATE);
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        reply.markSolicited(true);
        snapshot(instance);
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(AbstractMRTrafficController.NOTIFIEDSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
    }

    @Test
    public void testTransitionFromWaitMsgBusyError() throws Exception {
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITMSGREPLYSTATE);
        XNetPlusReply reply = newXNetPlusReply("61 81 E0");
        reply.markSolicited(true);
        snapshot(instance);
        instance.makeTransition(reply);
        instance.commit();
        
        Semaphore transitionDone = new Semaphore(0);
        
        exec.submit(() -> {
            XNetPlusReply r;
            try {
                while ((r = takeWithTimeout(0)) != null) {
                    snapshot(instance);
                    instance.makeTransition(r);
                    instance.commit();
                    transitionDone.release();
                }
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
        });
        
        lbq.add(reply);
        assertTrue("Retry immediately", transitionDone.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertEquals(AbstractMRTrafficController.AUTORETRYSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
        assertEquals(1, finalMemento.getRetransmitCount());
        
        initialMemento = finalMemento;
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITMSGREPLYSTATE);
        // next retransmission:
        lbq.add(reply);
        assertFalse("Must delay transition", transitionDone.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertTrue("Must not delay too long", transitionDone.tryAcquire(150, TimeUnit.MILLISECONDS));
        assertEquals(AbstractMRTrafficController.AUTORETRYSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
        assertEquals(2, finalMemento.getRetransmitCount());
        
        // now deliver OK:
        reply = newXNetPlusReply("01 04 05");
        reply.markSolicited(true);
        lbq.add(reply);
        assertTrue("Transition immediately", transitionDone.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertEquals(AbstractMRTrafficController.NOTIFIEDSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
        assertEquals(0, finalMemento.getRetransmitCount());
    }

    @Test
    public void testTransitionFromWaitReplyInProg() {
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITREPLYINPROGMODESTATE);
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        reply.markSolicited(true);
        snapshot(instance);
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(AbstractMRTrafficController.OKSENDMSGSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
    }

    @Test
    public void testTransitionFromWaitReplyInNorm() {
        initialMemento.setmCurrentState(AbstractMRTrafficController.WAITREPLYINNORMMODESTATE);
        XNetPlusReply reply = newXNetPlusReply("01 04 05");
        reply.markSolicited(true);
        snapshot(instance);
        instance.makeTransition(reply);
        instance.commit();
        assertEquals(AbstractMRTrafficController.OKSENDMSGSTATE, finalMemento.getmCurrentState());
        assertTrue(wakeUp);
    }
    
    /**
     * Checks that loopUnsolicited terminates gracefuly on receiver shutdown.
     */
    @Test
    public void testTerminateWhileLoopUnsolicited() throws Exception{
        Future<ReplyOutcome> handle = exec.submit(() -> {
            try {
                return outcome = instance.loopUnsolicitedMessages();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new ReplyOutcome(NULL);
        });
        lbq.add(NULL);
        assertNull("Return immediately", handle.get(50, TimeUnit.MILLISECONDS));
    }

    /**
     * Checks that loopUnsolicited terminates gracefully on receiver shutdown.
     */
    @Test
    public void testTerminateWhileLoopUnsolicited2() throws Exception{
        Future<ReplyOutcome> handle = exec.submit(() -> {
            try {
                return outcome = instance.loopUnsolicitedMessages();
            }
            catch (IOException ex) {
                Logger.getLogger(ResponseHandlerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return new ReplyOutcome(NULL);
        });
        active = false;
        XNetPlusReply base = newXNetPlusReply("42 05 2B 0f");
        base.setUnsolicited();
        lbq.add(base);
        assertNull("Return immediately", handle.get(50, TimeUnit.MILLISECONDS));
    }

}
