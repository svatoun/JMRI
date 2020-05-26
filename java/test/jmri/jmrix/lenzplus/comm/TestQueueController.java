package jmri.jmrix.lenzplus.comm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
class TestQueueController extends QueueController {
    Map<Integer, Integer> accessoryState = new HashMap<>();
    boolean disableExpiration = true;
    CommandState lastSentState;
    TestSchedulingQueue testQueue;
    
    public TestQueueController(TrafficController controller) {
        super(controller, new TestSchedulingQueue());
        testQueue = (TestSchedulingQueue)commandQueue;
    }

    @Override
    protected void assureLayoutThread() {
    }

    @Override
    public CommandState send(CommandHandler h, XNetPlusMessage msg, XNetListener callback) {
        CommandState s = super.send(h, msg, callback);
        lastSentState = s;
        return s;
    }

    @Override
    void expireTransmittedMessages() {
        if (!disableExpiration) {
            super.expireTransmittedMessages();
        }
    }
    
    static class TestSchedulingQueue extends SchedulingQueue {
        Map<CommandState, Semaphore> delayMap = new HashMap<>();
        Map<CommandState, Semaphore> reached = new HashMap<>();
        
        @Override
        synchronized void postMessage(CommandState state) {
            Semaphore q = reached.computeIfAbsent(state, (s) -> new Semaphore(0));
            Semaphore s = delayMap.get(state);
            if (s == null) {
                super.postMessage(state);
                q.release();
                return;
            }
            Executors.newCachedThreadPool().execute(() -> {
                try {
                    s.acquire();
                    super.postMessage(state);
                    q.release();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            });
        }
    }
    
}
