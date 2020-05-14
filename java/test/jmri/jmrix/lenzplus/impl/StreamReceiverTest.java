package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenzplus.impl.StreamReceiver;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import jmri.jmrix.lenz.XNetReply;
import jmri.jmrix.lenzplus.XNetPlusAccess;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;
import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.jupiter.api.Assertions;
import org.junit.rules.TestName;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class StreamReceiverTest {
    @Rule
    public TestName    name = new TestName();
    
    private final Semaphore replySem = new Semaphore(0);
    private final Semaphore incomingSem = new Semaphore(0);
    private final LinkedBlockingQueue<XNetPlusReply>  replies = new LinkedBlockingQueue<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService exec2 = Executors.newSingleThreadExecutor();

    private StreamReceiver  receiver;
    private volatile IOException throwIO;
    private volatile RuntimeException throwRun;
    private volatile XNetPlusReply incomingPacket;
    
    public StreamReceiverTest() {
    }
    
    @Before
    public void setUp() {
        JUnitUtil.setUp();
    }
    
    @After
    public void tearDown() {
        if (receiver != null) {
            receiver.stop();
            receiver.getReceivingThread().interrupt();
        }
        exec.shutdownNow();
        exec2.shutdownNow();
        
        JUnitUtil.tearDown();
    }
    
    private static XNetPlusReply newXNetPlusReply(String s) {
        return XNetPlusAccess.createReply(s);
    }
    
    private void startReceiver() {
        receiver = new StreamReceiver(this::getReply);
        exec.submit(() -> {
            // is thread is stuck during testing, its name will show method culprit
            Thread.currentThread().setName("Receiver: " + name.getMethodName());
            // avoid name set by receiver
            receiver.doRun();
        });
    }

    private XNetPlusReply getReply() throws IOException {
        XNetPlusReply r = null;
        while (true) {
            try {
                XNetPlusReply incoming;
                replySem.acquire();
                incoming = incomingPacket;
                if (incoming != null) {
                    receiver.incomingPacket(incoming);
                    incomingSem.release();
                }
                r = replies.take();
                incomingSem.release();
                break;
            } catch (InterruptedException ex) {
                if (!receiver.isActive() || throwRun != null || throwIO != null) {
                    break;
                }
            }
        }
        if (throwRun != null) {
            RuntimeException e = throwRun;
            throwRun = null;
            throw e;
        } else if (throwIO != null) {
            throw throwIO;
        }
        return r;
    }
    
    private void markIncomingPacket(XNetPlusReply r) {
        incomingPacket = r;
        replySem.release();
        
        // HACK HACK: in production code, the incomingPacket() will be called
        // synchronously from within the receive thread. This wait here is to synchronize
        // the main test thread with the receiver for stable test results.
        incomingSem.acquireUninterruptibly();
    }
    
    private XNetPlusReply addReply(XNetPlusReply r) {
        markIncomingPacket(r);
        addReply2(r);
        return r;
    }
    
    private void addReply2(XNetPlusReply r) {
        if (incomingPacket != r) {
            replySem.release();
        }
        replies.offer(r);
    }
    
    @Test
    public void testIncomingPacket() {
    }
    
    private volatile IOException recvIOException;
    private volatile RuntimeException recvRuntimeException;
    private volatile XNetPlusReply replyInstance;
    private final Semaphore signal = new Semaphore(0);
    private final Semaphore reached = new Semaphore(0);
    
    private void receiverTakeFunc() {
        receiverTakeTimeout(0);
    }
    
    private void receiverTakeTimeout(int timeout) {
        try {
            if (timeout == 0) {
                replyInstance = receiver.take();
            } else {
                reached.release();
                replyInstance = receiver.takeWithTimeout(timeout);
            }
        } catch (RuntimeException  ex) {
            recvRuntimeException = ex;
        } catch (IOException ex) {
            recvIOException = ex;
        } finally {
            signal.release();
        }
    }

    /**
     * Checks that a message can be normally received.
     * @throws Exception 
     */
    @Test
    public void testNormalReceiveMessage() throws Exception  {
        startReceiver();
        signal.drainPermits();
        exec2.submit(this::receiverTakeFunc);
        // must block:
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        addReply(m);
        
        assertTrue("Take must terminate on message", signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        assertSame(m, replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
        assertTrue(receiver.isActive());
    }

    /**
     * Checks that when stopped, take function throws no exception, just
     * returns {@code null}.
     */
    @Test
    public void testStop() throws Exception {
        startReceiver();
        assertTrue(receiver.isActive());
        signal.drainPermits();
        exec2.submit(this::receiverTakeFunc);
        // must block:
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        // advise an incoming packet
        markIncomingPacket(m);
        // issue stop before the packet can be read:
        receiver.stop();
        addReply2(m);
        
        assertTrue(signal.tryAcquire(100, TimeUnit.MILLISECONDS));
        
        assertNull(replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
        assertFalse(receiver.isActive());
    }
    
    /**
     * Checks that when stopped, take function throws no exception, just
     * returns {@code null}.
     */
    @Test
    public void testInterrupt() throws Exception {
        startReceiver();
        assertTrue(receiver.isActive());
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        CountDownLatch ok = new CountDownLatch(1);
        // allocate explicit since in the threadpool, thread never dies.
        Thread takeThread = new Thread() {
            public void run() {
                ok.countDown();
                receiverTakeFunc();
            }
        };
        takeThread.start();
        assertTrue(ok.await(500, TimeUnit.MILLISECONDS));
        
        // interrupt in the take()
        takeThread.interrupt();
        markIncomingPacket(new XNetPlusReply());
        Thread.sleep(50);
        // interrupt in the takeIncomingReply()
        takeThread.interrupt();
        takeThread.join(100);
        // still did not stop:
        assertTrue(takeThread.isAlive());
        // stop the receiver
        receiver.stop();
        takeThread.join(100);
        // and now has stopped:
        assertFalse(takeThread.isAlive());
        
        assertNull(replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
        assertFalse(receiver.isActive());
    }
    
    /**
     * Checks that when stopped, even if IOException is thrown
     * from the message fetcher, the take function just returns 
     * {@code null}.
     */
    @Test
    public void testStopWithException() throws Exception {
        startReceiver();
        assertTrue(receiver.isActive());
        signal.drainPermits();
        exec2.submit(this::receiverTakeFunc);
        // must block:
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        // advise an incoming packet
        markIncomingPacket(m);
        // issue stop before the packet can be read:
        receiver.stop();
        
        // simulate IOException as if InputStream was closed forcibly:
        throwIO = new IOException();
        addReply2(m);
        assertTrue(signal.tryAcquire(100, TimeUnit.MILLISECONDS));
        
        assertNull(replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
        assertFalse(receiver.isActive());
    }
    
    /**
     * Take throws runtime exception, but the loop continues.
     * @throws Exception 
     */
    @Test
    public void testTakeThrowsRuntimeException() throws Exception {
        startReceiver();
        assertTrue(receiver.isActive());
        signal.drainPermits();
        exec2.submit(this::receiverTakeFunc);
        // must block:
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        // simulate runtime error:
        RuntimeException e = new IllegalStateException();
        throwRun = e;
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        
        addReply(m);
        
        assertTrue("Error must exit from take", signal.tryAcquire(100, TimeUnit.MILLISECONDS));
        // the loop did not end
        assertTrue("Runtime does not terminate receiver", receiver.isActive());
        assertSame(e, recvRuntimeException);
        assertNull(recvIOException);
    }

    /**
     * Checks that IOException will terminate receiver and report the exception.
     */
    @Test
    public void testTakeTerminatesOnIOException() throws Exception {
        startReceiver();
        assertTrue(receiver.isActive());
        signal.drainPermits();
        exec2.submit(this::receiverTakeFunc);
        // must block:
        assertFalse(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        
        // simulate runtime error:
        IOException e = new IOException();
        throwIO = e;
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        
        addReply(m);
        
        assertTrue("Error must exit from take", signal.tryAcquire(100, TimeUnit.MILLISECONDS));
        // the loop did not end
        assertFalse("IOException terminates receiver", receiver.isActive());
        assertSame(e, recvIOException);
        assertNull(recvRuntimeException);
    }
    
    /**
     * Checks that message that comes without markTransmission is
     * not reported as solicited.
     */
    @Test
    public void testIsUnexpected() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = addReply(newXNetPlusReply("01 04 05"));
        
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertTrue(replyInstance.isUnsolicited());
    }

    @Test
    public void testIsExpected() throws Exception{
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        XNetPlusReply m = addReply(newXNetPlusReply("01 04 05"));
        
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());
    }

    /**
     * Checks that code that forgets to call incomingPacket will not block
     * the receiver thread.
     */
    @Test
    public void testReceptionWorksWithoutIncomingPacket() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        addReply2(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());
    }

    /**
     * Checks that it's sufficient if just a single byte arrives before
     * markTransmission.
     */
    @Test
    public void testIsUnexpectedWhenJustHeaderArrives() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");

        markIncomingPacket(m);
        
        // NOTE: this is NOT entirely real. The test coordinates with the receiver
        // thread so it continues even after incomingPacket() is delivered to the
        // StreamReceiver.
        // The production code however may receiver.markTransmission() at any time, even 
        // during receiver.incomingPacket() execution. There's a small time overlap that
        // cannot be fixed/closed reliably, where an unsolicited reply may have been marked
        // as expected.
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        addReply2(m);
        
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertTrue(m.isUnsolicited());
    }

    @Test
    public void testResetExpectedReply() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        // add an expected reply
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));

        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());

        // reset the reply state, schedule next message
        receiver.resetExpectedReply(replyInstance);
        exec2.submit(this::receiverTakeFunc);
        m = newXNetPlusReply("42 05 80 01");
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        // the previous reply was reset
        assertTrue(m.isUnsolicited());
    }

    /**
     * Checks that all messages, which come before the expected-reset
     * instruction will be marked as expected.
     * @throws Exception 
     */
    @Test
    public void testMultiRepliesExepectedBeforeReset() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        // add an expected reply
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));

        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());

        m = newXNetPlusReply("42 07 80 01");
        // there should be still 'message being transmitted'
        addReply(m);
        // simulate the receiver's loop
        exec2.submit(this::receiverTakeFunc);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));

        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());

        // reset the reply state, schedule next message
        receiver.resetExpectedReply(replyInstance);
        exec2.submit(this::receiverTakeFunc);
        m = newXNetPlusReply("42 05 80 01");
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        // the previous reply was reset
        assertTrue(m.isUnsolicited());
    }

    @Test
    public void testTakeWithTimeoutExpires() throws Exception {
        startReceiver();

        exec2.submit(() -> receiverTakeTimeout(30));
        Thread.sleep(40);
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertNull(replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
    }
    
    @Test
    public void testTakeWithTimeoutAlreadyWaiting() throws Exception {
        startReceiver();

        exec2.submit(() -> receiverTakeTimeout(30));
        reached.acquire();
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertNull(recvIOException);
        assertNull(recvRuntimeException);
    }
    
    /**
     * Checks that marking transmission before the receive thread
     * clears the reception status fails. This may indicate bad coordination
     * between the transmit and receive threads.
     */
    @Test
    public void testMarkTransmissionFails() throws Exception {
        startReceiver();
        exec2.submit(this::receiverTakeFunc);
        
        XNetPlusReply m = newXNetPlusReply("01 04 05");
        // add an expected reply
        receiver.markTransmission(new XNetPlusMessage("01 00 01"));
        addReply(m);
        assertTrue(signal.tryAcquire(50, TimeUnit.MILLISECONDS));
        assertSame(m, replyInstance);
        assertFalse(m.isUnsolicited());
        
        // do NOT clear the status, and make another transmission
        Assertions.assertThrows(IllegalStateException.class, () -> 
            receiver.markTransmission(new XNetPlusMessage("01 01 00"))
        );
    }

}
