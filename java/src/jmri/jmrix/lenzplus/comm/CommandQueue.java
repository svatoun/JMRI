package jmri.jmrix.lenzplus.comm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;

/**
 * Special scheduling queue, which can block certain groups of messages and respects
 * message priorities.
 * The purpose of this queue is to organize messages into the correct order to be 
 * transmitted to the command station. Messages with higher {@link XNetPlusMessage#getPriority()}
 * are always sent first. In case of same priority, the message that entered the queue first
 * will be sent first - unless it is blocked.
 * <p>
 * Blocked messages step out from the queue temporarily. If the message has {@link CommandHandler#getLayoutId()}
 * nonzero, all messages with same or lower priority for that ID will be blocked. This allows, for example
 * a delayed Output OFF message to block further commands to the same accessory.
 * <p>
 * When a message is unblocked, all messages it caused to block (which are not blocked themselves) re-enter
 * the queue; they will be reinserted according to their priority AND the original order in the queue (among
 * the same-priority messages). So if a delayed message unblocks further commands for a turnout,
 * those commands have a chance to be processed in the original order instead of being inserted at the end
 * of the queue.
 * <p>
 * This queue does not handle delays etc, a message can be blocked because of a delay, or because of
 * a dependency: that's up to the caller. The number of {@link #block} calls must match the number
 * of {@link #unblock} calls for the message to be released.
 * <p>
 * A message can be {@link #remove}d, which unblocks all messages waiting on it. The queue will
 * never return a message with {@link Phase#isActive} == false. If the caller does not
 * {@link #remove} messages that transition to {@link Phase#EXPIRED} or {@link Phase#FINISHED},
 * the {@link #size()} may become inconsistent.
 * <p>
 * <b>Implementation notes:</b> The implementation is 'lazy' so if a message is marked as blocked,
 * it is just marked, but remains at its position in the ordered queue. Once the message is about
 * to be returned from {@link #poll}, its blocked status is checked: both if the message itself
 * is not blocked, or whether some other blocked message does not block it. If so, it is stamped,
 * removed from the queue to a wait map. If the message becomes unblocked before it becomes the
 * queue head, it's processed just as the block never happened.
 * <p>
 * For each layout ID, a highest-priority blocker is tracked. All messages for the same layout ID
 * with lower priority are suspended by it.
 * <p>
 * The queue add operation is O(1). The removal may be even O(n): it's not optimized for high-volume
 * data.
 * <h3>Concurrent policy</h3>
 * The class should be thread-safe, use from any thread.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class CommandQueue {
    /**
     * List of messages, which may be active.
     */
    @GuardedBy("this")
    private final LinkedList<CommandState>  active = new LinkedList<>();
    
    /**
     * All queued messages, for fast access.
     */
    @GuardedBy("this")
    private final Set<CommandState> allQueued = new LinkedHashSet<>();
    
    /**
     * Indication that messages for a layout item/slot/accessory may need to be blocked.
     * Value is the highest-priority blocked message for that item.
     */
    @GuardedBy("this")
    private Map<Object, CommandState> blockedSlots = new HashMap<>();
    
    /**
     * Lists of messages queued after a blocked message.
     */
    @GuardedBy("this")
    private Map<CommandState, List<CommandState>> blockedMap = new HashMap<>();
    
    /**
     * Lock counters for individual blocked messages.
     */
    @GuardedBy("this")
    private Map<CommandState, AtomicInteger> blocks = new HashMap<>();

    /**
     * Ever-increasing stamp for messages that enter the queue. Messages are stamped
     * once they are about to be popped from the head of the queue. If they become blocked
     * at that time, they retain the stamp for later reinsertion between other blocked
     * and reinserted messages.
     */
    @GuardedBy("this")
    private long stamp = 1;

    /**
     * Queue for message replay, after rejected temporarily.
     */
    @GuardedBy("this")
    private final Queue<CommandState> replay = new LinkedList<>();
    
    
    public synchronized Stream<CommandState> getQueued() {
        if (allQueued.isEmpty()) {
            return Stream.empty();
        }
        return new ArrayList<>(allQueued).stream().filter(c -> c.getPhase().isActive());
    }
    
    /**
     * Removes an item from the queue. Returns false, if the item
     * was not present.
     * @param s item to remove.
     * @return false, if the item was not present.
     */
    public synchronized boolean remove(CommandState s) {
        synchronized (s) {
            if (!s.getPhase().passed(Phase.FINISHED)) {
                s.toPhase(Phase.EXPIRED);
            }
        }
        if (!allQueued.remove(s)) {
            return false;
        }
        do {
            unblock(s);
        } while (blocks.containsKey(s));
        replay.remove(s);
        active.remove(s);
        return true;
    }
    
    public synchronized boolean removeAll(Collection<CommandState> col) {
        return col.stream().map(this::remove).filter(b -> b).count() > 0;
    }
    
    /**
     * Unblocks the message. Returns -1, if the item was not explicitly
     * blocked. Returns 0, if the item was put into the active queue - that
     * means it can be blocked before it gets to head! Returns 1, if the item
     * is known to block because of other command.
     * <p>
     * Negative value can be seen as an error, or a concurrent unblock operation.
     * @param s the item to unblock.
     * @return unblock status.
     */
    public synchronized int unblock(CommandState s) {
        AtomicInteger i = blocks.remove(s);
        if (i == null || i.decrementAndGet() > 0) {
            return -1;
        }
        s.toPhase(Phase.QUEUED);
        List<CommandState> waits = blockedMap.remove(s);
        if (waits == null) {
            // nothing was blocked because of this command, yet.
            return 0;
        }
        Object id = s.getCommandGroupKey();
        if (id == null) {
            // can't have any blocked followers, just insert the message itself,
            // if it was blocked at all.
            reinsertAll(waits);
            return 0;
        }
        CommandState head = blockedSlots.get(id);
        if (head == null) {
            // nothing was blocked yet, even the mesage itself. Do nothing.
            return 0;
        }
        if (head != s) {
            // NOTE: maybe this branch is dead: it supposes, that higher-priority
            // message becomes blocked and takes over the role of slot head.
            // but at that time, it would absorb the former head's blockedMap contents,
            // so waits == null above and the code never reaches here.
            
            // another message is the head of the block
            if (head.getPriority() <= s.getPriority()) {
                // ... and has a priority, so it remains blocking, merge in our waits
                List<CommandState> newWaits = blockedMap.get(head);
                newWaits.addAll(waits);
                s.toPhase(Phase.BLOCKED);
                return 1;
            }
        }
        // s was the head item, or the head has lower priority than "s", so "s"
        // will be released; but waits have to be processed one by one.
        List<CommandState> remains = new ArrayList<>(waits);
        // select new head from waiting commands, which are iself still blocked:
        remains.retainAll(blocks.keySet());
        waits.removeAll(remains);
        reinsertAll(waits);
        if (remains.isEmpty()) {
            blockedSlots.remove(id);
            return 0;
        } 
        head = null;
        int p = Integer.MAX_VALUE;
        long st = Long.MAX_VALUE;
        for (CommandState r : remains) {
            int d = p - r.getPriority();
            long d2 = st - r.getStamp();
            if (d > 0 || ((d == 0) && d2 > 0)) {
                head = r;
                p = r.getPriority();
                st = r.getStamp();
            }
        }
        blockedSlots.put(id, head);
        blockedMap.put(head, remains);
        return 0;
    }
    
    /**
     * Polls for the next item to be processed. Respect priorities, active 
     * blocks and suspends. Returns {@code null}, if there's no item to process.
     * That does not mean the queue is empty - all items may be blocked or
     * suspended !
     * @return item to process.
     */
    public synchronized CommandState poll() {
        return peekOrPoll(true);
    }
    

    /**
     * Returns an approx number of messages in the queue. The count could be
     * precise, but it relies on that each message that is {@link Phase#EXPIRED}
     * is really removed from the queue. For correct processing, terminate messages
     * are filtered out from {@link #peek}, {@link #poll}, but <b>may be still counted</b>.
     * DO NOT USE this method to test if the queue is empty.
     * @return approximate number of messages in the queue.
     */
    public int size() {
        return allQueued.size();
    }
    
    /**
     * Determines if the queue is empty. It's not just a query, it actually deletes
     * inactive commands from the queue, changing the {@link #size}. The method
     * could be called occasionally, just in case to purge possible leftovers.
     * @return true, if the queue is empty.
     */
    public synchronized boolean checkEmpty() {
        getQueued().
            filter(c -> !c.getPhase().isActive()).
            forEach(this::remove);
        if (!blocks.isEmpty()) {
            return false;
        }
        return peek() != null;
    }
    
    /**
     * Determines if there's a message to be sent. Note that the returned
     * value may change by concurrent operation and is only informative. The command
     * may be blocked, preempted or removed before {@link #peek} and a corresponding
     * {@link #poll}. The only consistent value for command operation is given by 
     * {@link #poll}.
     * @return 
     */
    public synchronized CommandState peek() {
        CommandState s = replay.peek();
        if (s != null) {
            return s;
        }
        s = peekOrPoll(false);
        if (s != null) {
            active.addFirst(s);
        }
        return s;
    }

    /**
     * Replays a command. The command will be first served by the next {@link #poll}.
     * Replayed commands are poll-ed in the replay order, and take precedence before
     * any other commands, regardless of the priority.
     * @param state command to replay.
     */
    public synchronized void replay(CommandState state) {
        synchronized (state) {
            if (state.getPhase().isFinal()) {
                return;
            }
            state.toPhase(CommandState.Phase.REPLAYING);
        }
        allQueued.add(state);
        replay.add(state);
    }

    @GuardedBy("this")
    private void addBlocked(CommandState s) {
        synchronized (s) {
        if (s.getPhase() != Phase.SCHEDULED) {
            s.toPhase(Phase.BLOCKED);
            }
        }
        blocks.computeIfAbsent(s, (x) -> new AtomicInteger()).incrementAndGet();
        priorityInsert(s, active, false);
    }
    
    /**
     * Blocks the specified command. The command will not be returned form {@link #poll()}
     * until it is unblocked. Note: if the blocked command is unblocked again, before it 
     * reaches the head of the queue, it will maintain its position in the command stream.
     * @param s command
     * @return true, if the command was blocked.
     */
    public synchronized boolean block(CommandState s) {
        if (!allQueued.contains(s) || s.getPhase().passed(Phase.SENT)) {
            return false;
        }
        synchronized (s) {
            if (s.getPhase() != Phase.SCHEDULED) {
                s.toPhase(Phase.BLOCKED);
            }
        }
        blocks.computeIfAbsent(s, (x) -> new AtomicInteger()).incrementAndGet();
        return true;
    }
    

    @GuardedBy("this")
    void priorityInsert(CommandState s, List<CommandState> list, boolean reinsert) {
        if (reinsert) {
            s.toPhase(CommandState.Phase.QUEUED);
        }
        int p = s.getPriority();
        long st = s.getStamp();
        boolean match;
        if (p < XNetPlusMessage.DEFAULT_PRIORITY) {
            for (ListIterator<CommandState> lit = list.listIterator(); lit.hasNext(); ) {
                CommandState cs = lit.next();
                int d = cs.getPriority() - p;
                match = (d > 0);
                if (reinsert) {
                    // reinserts should attemp to add (already reached head of queue) first,
                    // and if there are other stamped, their stamps defined ordering.
                    if (d == 0 && st > 0) {
                        match = cs.getStamp() == 0 || cs.getStamp() > st;
                    }
                }
                if (match) {
                    lit.previous();
                    lit.add(s);
                    return;
                }
            }
            active.addLast(s);
        } else {
            for (ListIterator<CommandState> lit = list.listIterator(list.size()); lit.hasPrevious(); ) {
                CommandState cs = lit.previous();
                int d = cs.getPriority() - p;
                match = (d <= 0);
                if (reinsert) {
                    if (d == 0 && st > 0) {
                        match = cs.getStamp() != 0 && cs.getStamp() < st;
                    }
                }
                if (match) {
                    lit.next();
                    lit.add(s);
                    return;
                }
            }
            active.addFirst(s);
        }
    }
    
    synchronized public void add(CommandState s, boolean block) {
        allQueued.add(s);
        if (block) {
            addBlocked(s);
        } else {
            s.toPhase(Phase.QUEUED);
            priorityInsert(s, active, false);
        }
    }
    
    @GuardedBy("this")    
    private void makeBlocked(CommandState s) {
        s.toPhase(Phase.BLOCKED);
        Object id = s.getCommandGroupKey();
        if (id == null) {
            blockedMap.put(s, Collections.singletonList(s));
            return;
        }
        CommandState head = blockedSlots.get(id);
        if (head == null) {
            blockedSlots.put(id, s);
            List<CommandState> b = new ArrayList<>();
            b.add(s);
            blockedMap.put(s, b);
            return;
        }
        // the new block has higher priority, make it the new slot head,
        // and merge lists.
        if (head.getPriority() > s.getPriority()) {
            blockedSlots.put(id, s);
            List<CommandState> l = blockedMap.remove(head);
            l.add(s);
            blockedMap.put(s, l);
        } else {
            blockedMap.get(head).add(s);
        }
    }
    
    @GuardedBy("this")
    private void reinsertAll(List<CommandState> waits) {
        for (Iterator<CommandState> it = waits.iterator(); it.hasNext(); ) {
            CommandState w = it.next();
            if (w.getPhase().passed(Phase.FINISHED)) {
                it.remove();
            } else if (!blocks.containsKey(w)) {
                priorityInsert(w, active, true);
            }
        }
    }
    
    private CommandState peekOrPoll(boolean poll) {
        CommandState s = replay.poll();
        if (s != null) {
            return s;
        }
        while (true) {
            s = active.poll();
            if (s == null) {
                return s;
            }
            s.setStampOnce(stamp++);
            Object id = s.getCommandGroupKey();
            if (blocks.containsKey(s)) {
                makeBlocked(s);
                continue;
            }
            if (id != null) {
                CommandState head = blockedSlots.get(id);
                if (head != null) {
                    if (head.getPriority() <= s.getPriority()) {
                        blockedMap.get(head).add(s);
                        continue;
                    }
                }
            }
            boolean ok = poll ? allQueued.remove(s) : allQueued.contains(s);
            if (ok) {
                if (s.getPhase().isActive()) {
                    return s;
                }
            }
        }
    }
}
