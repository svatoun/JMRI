package jmri.jmrix.lenzplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetMessage;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
class TestXNetPlusTrafficController extends XNetPlusTrafficController {

    final Semaphore messageQueuePermits = new Semaphore(0);
    volatile boolean blockMessageQueue;
    volatile XNetMessage originalMessage;
    final BlockingQueue<Runnable> injectMessageQueue = new LinkedBlockingQueue<>();
    volatile boolean timeoutOccured;
    Map<XNetMessage, AtomicInteger> retryCounters = Collections.synchronizedMap(new IdentityHashMap<>());

    public TestXNetPlusTrafficController(LenzCommandStation pCommandStation) {
        super(pCommandStation);
    }

    private void injectMessages() {
        List<Runnable> r = new ArrayList<>();
        injectMessageQueue.drainTo(r);
        r.stream().forEach(Runnable::run);
    }

    /**
     * Counts the number of retries after errors, does NOT count retries
     * after timeout.
    s         */
    @Override
    protected void forwardToPort(AbstractMRMessage m, AbstractMRListener reply) {
        XNetMessage msg = (XNetMessage) m;
        // for each *instance* of message, track the number of attempts to send.
        // XNetMessage with the same payload are equals(), but we want to track instances,
        // to see what msg was actually re-send, and which was just replicated
        synchronized (this) {
            retryCounters.computeIfAbsent(msg, (k) -> new AtomicInteger()).incrementAndGet();
            originalMessage = msg;
        }
        super.forwardToPort(m, reply);
    }

    @Override
    protected AbstractMRMessage takeMessageToTransmit(AbstractMRListener[] ll) {
        AbstractMRMessage mrm = super.takeMessageToTransmit(ll);
        if (blockMessageQueue) {
            try {
                messageQueuePermits.acquire();
                injectMessages();
            }
            catch (InterruptedException ex) {
            }
        }
        return mrm;
    }

    @Override
    protected AbstractMRMessage pollMessage() {
        AbstractMRMessage mrm = super.pollMessage();
        if (mrm == null) {
            return null;
        }
        injectMessages();
        return mrm;
    }

    @Override
    protected void handleTimeout(AbstractMRMessage msg, AbstractMRListener l) {
        if (!threadStopRequest) {
            super.handleTimeout(msg, l);
            timeoutOccured = true;
        }
    }

}
