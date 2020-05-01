/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures processing state for an {@link XNetMessage}. The state is separated
 * from XNetMessage because it should not be messed up by regular clients: the
 * state controls the command-reply protocol of XPressNet.
 * <p>
 * Otherwise it could be directly on a {@link XNetMessage}, as there's 1:1 mapping
 * between CommandState and an outgoing message.
 * 
 * @author sdedic
 */
public class CommandState {
    /**
     * Describes phase of processing this message. The message should transition
     * from CREATED to either FINISHED or EXPIRED through all the intermediate
     * phases. The exception are
     * <ul>
     * <li>If a message is rejected (station busy), it falls back to QUEUED.
     * <li>If a message is discarded, it will transition to EXPIRED from any state
     * except FINISHED
     * </ul>
     * A message may be CONFIRMED, but not yet FINISHED, if there are more replies
     * possible to that message. 
     * <p>
     * FINISHED or EXPIRED messages are never sent by the transmit loop.
     */
    public static enum Phase {
        /**
         * The message was created, but not passed to transmit thread.
         */
        CREATED(false, false), 
        /**
         * The message entered transmission queue.
         */
        QUEUED(true, false), 
        
        /**
         * The message has been (is being) sent to XpressNet interface
         */
        SENT(true, false), 
        
        /**
         * A confirmation has been received for the message.
         */
        CONFIRMED(true, true), 
        
        /**
         * Confirmed repeatedly, by a different message
         */
        CONFIRMED_AGAIN(true, true), 
        
        /**
         * The message has completed processing.
         */
        FINISHED(false, true),
        
        /**
         * The message has expired with no confirmation.
         */
        EXPIRED(false, false);

        public boolean isActive() {
            return active;
        }

        public boolean isConfirmed() {
            return confirmed;
        }

        private final boolean active;
        private final boolean confirmed;
        
        private Phase(boolean active, boolean confirmed) {
            this.active = active;
            this.confirmed = confirmed;
        }
    }
    
    private final XNetMessage   command;
    
    /**
     * Diagnostics: The time the message was posted into the transmit queue.
     */
    private long timeQueued;
    
    /**
     * Diagnostics: The time the message was really sent.
     */
    private long timeSent;
    
    /**
     * Diagnostics: The time the message was first confirmed.
     */
    private long timeConfirmed;
    
    /**
     * Number of OK messages received.
     */
    private int okReceived;
    
    /**
     * Number of OK messages received so far.
     */
    private int stateReceived;
    
    /**
     * The current processing phase.
     */
    private volatile Phase phase = Phase.CREATED;

    public CommandState(XNetMessage command) {
        this.command = command;
    }

    public int getOkReceived() {
        return okReceived;
    }

    public int getStateReceived() {
        return stateReceived;
    }
    
    public void addOkMessage() {
        okReceived++;
    }
    
    public void addStateMessage() {
        stateReceived++;
    }
    
    public XNetMessage getMessage() {
        return command;
    }
    
    /**
     * Transitions the message to a next phase. 
     * @param toPhase the new phase.
     */
    synchronized boolean toPhase(Phase toPhase) {
        if (toPhase == Phase.CONFIRMED) {
            if (this.phase.isConfirmed()) {
               toPhase = Phase.CONFIRMED_AGAIN;
            }
        }
        if (this.phase == toPhase) {
            return false;
        }
        log.debug("Message {} toPhase: {}", this, toPhase);
        if (toPhase == Phase.EXPIRED) {
            if (phase != Phase.EXPIRED) {
                checkTransitionOne(toPhase);
            }
        } else {
            switch (phase) {
                case SENT: case QUEUED: case CONFIRMED_AGAIN:
                    break;
                default:
                    checkTransitionOne(toPhase);
            }
        }
        this.phase = toPhase;
        
        long t = System.currentTimeMillis();
        switch (phase) {
            case QUEUED: this.timeQueued = t; break;
            case SENT:      this.timeSent = t; break;
            case CONFIRMED: this.timeConfirmed = t; break;
        }
        return true;
    }
    
    private void checkTransitionOne(Phase toPhase) {
        if (toPhase.ordinal() == this.phase.ordinal() + 1) {
            return;
        }
        throw new IllegalArgumentException("Invalid transition from state " + this.phase.name() + " to " + toPhase.name());
    }
    
    public Phase getPhase() {
        return phase;
    }
    
    private static void addTime(StringBuilder sb, long time, String name) {
        if (time <= 0) {
            return;
        }
        sb.append(String.format(", %s=%2$tM:%2$tS.%2$tL", name, time));
    }
    
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "%s; Phase: %s (%d/%d)" + // 4 parameters
                 command.toString(), phase.name(), okReceived, stateReceived));
        addTime(sb, timeQueued, "queued");
        addTime(sb, timeSent, "sent");
        addTime(sb, timeConfirmed, "confirmed");
        
        return sb.toString();
    }
    
    private static final Logger log = LoggerFactory.getLogger(CommandState.class);
}
