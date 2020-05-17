/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class adds delays, and in the future maybe mode dependencies on top
 * of the blocking and grouping of CommandQueue.
 * 
 * @author sdedic
 */
public class SchedulingQueue extends CommandQueue {
    /**
     * Service used to schedule delayed messages.
     */
    private final ScheduledExecutorService  schedulerService = Executors.newSingleThreadScheduledExecutor();
    
    /**
     * Generated messages, which are scheduled to some later time. Used to
     * potentially cancel the scheduled message.
     * <p>
     * This field can be altered from the transmit thread AND layout thread.
     */
    @GuardedBy("this")
    private final Map<CommandState, Future>   delayedMessages = new LinkedHashMap<>();
    
    @Override
    public synchronized void add(CommandState state, boolean block) {
        XNetPlusMessage msg = state.getMessage();
        if (msg.isDelayed()) {
            if (block) {
                throw new IllegalArgumentException("Scheduling blocked messages is not supported yet");
            }
            Future existing = delayedMessages.remove(state);
            if (existing != null) {
                throw new IllegalStateException("Cannot reschedule message.");
            }
            int d = msg.getDelay();
            if (d < 0) {
                d = -d;
            }
            LOG.debug("Scheduling {} after {}ms", state, d);
            state.toPhase(CommandState.Phase.SCHEDULED);
            Future<?> future = schedulerService.schedule(
                () -> postMessage(state), d, TimeUnit.MILLISECONDS);
            delayedMessages.put(state, future);
            block = true;
        }
        super.add(state, block);
    }
    
    private synchronized void postMessage(CommandState state) {
        Future<?> f = delayedMessages.remove(state);
        if (delayedMessages != null) {
            LOG.debug("Unblocking after timeout: {} ", state);
            unblock(state);
        }
    }
    
    @Nonnull
    public synchronized Future<?> getFutureCommand(CommandState s) {
        Future<?> f = delayedMessages.get(s);
        if (f != null) {
            return f;
        }
        if (s.getPhase().passed(CommandState.Phase.QUEUED)) {
            return CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture c = new CompletableFuture();
            c.completeExceptionally(new IllegalStateException(s.getPhase().toString()));
            return c;
        }
    }

    @Override
    public synchronized boolean remove(CommandState s) {
        cancel(s);
        return super.remove(s);
    }
    
    public synchronized boolean cancel(CommandState s) {
        Future<?> f = delayedMessages.remove(s);
        if (f != null) {
            synchronized (s) {
                if (s.getPhase().isActive()) {
                    s.toPhase(CommandState.Phase.EXPIRED);
                }
            }
            if (f.cancel(true)) {
                unblock(s);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingQueue.class);
}
