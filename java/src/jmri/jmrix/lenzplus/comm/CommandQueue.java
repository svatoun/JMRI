/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
 * 
 * @author sdedic
 */
public class CommandQueue {
    /**
     * List of messages, which may be active.
     */
    private final LinkedList<CommandState>  active = new LinkedList<>();
    
    /**
     * All queued messages, for fast access.
     */
    private final Set<CommandState> allQueued = new LinkedHashSet<>();
    
    /**
     * Indication that messages for a layout item/slot/accessory may need to be blocked.
     * Value is the highest-priority blocked message for that item.
     */
    private Map<Object, CommandState> blockedSlots = new HashMap<>();
    
    /**
     * Lists of messages queued after a blocked message.
     */
    private Map<CommandState, List<CommandState>> blockedMap = new HashMap<>();
    
    /**
     * Lock counters for individual blocked messages.
     */
    private Map<CommandState, AtomicInteger> blocks = new HashMap<>();

    /**
     * Ever-increasing stamp for messages that enter the queue. Messages are stamped
     * once they are about to be popped from the head of the queue. If they become blocked
     * at that time, they retain the stamp for later reinsertion between other blocked
     * and reinserted messages.
     */
    private long stamp = 1;
    
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
    
    public synchronized Stream<CommandState> getQueued() {
        return allQueued.stream().filter(c -> c.getPhase().isActive());
    }
    
    private void addBlocked(CommandState s) {
        if (s.getPhase() != Phase.SCHEDULED) {
            s.toPhase(Phase.BLOCKED);
        }
        blocks.computeIfAbsent(s, (x) -> new AtomicInteger()).incrementAndGet();
        priorityInsert(s, active, false);
    }
    
    private void makeBlocked(CommandState s) {
        if (s.getPhase() != Phase.SCHEDULED) {
            s.toPhase(Phase.BLOCKED);
        }
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
    
    /**
     * Removes an item from the queue. Returns false, if the item
     * was not present.
     * @param s item to remove.
     * @return false, if the item was not present.
     */
    public synchronized boolean remove(CommandState s) {
        if (!s.getPhase().passed(Phase.FINISHED)) {
            throw new IllegalStateException();
        }
        if (!allQueued.remove(s)) {
            return false;
        }
        do {
            unblock(s);
        } while (blocks.containsKey(s));
        active.remove(s);
        return true;
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
    
    private CommandState peekOrPoll(boolean poll) {
        while (true) {
            CommandState s = active.poll();
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

    public int size() {
        return allQueued.size();
    }
    
    public synchronized CommandState peek() {
        CommandState s = peekOrPoll(false);
        if (s != null) {
            active.addFirst(s);
        }
        return s;
    }
}
