package jmri.jmrix.lenzplus.comm;

import jmri.ProgrammingMode;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import org.openide.util.Lookup;

/**
 * Allows CommandHandlers controlled access to the rest of the system. This interface is internal to the
 * LenzPlus subsystem and should not be exposed or used in the rest of JMRI.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface CommandService extends Lookup.Provider {
    public CommandQueue getCommandQueue();
    /**
     * The current operating mode. {@code null} means normal operations.
     * @return programming mode.
     */
    public ProgrammingMode getMode();
    
    /**
     * Informs the service that a programming mode has been entered (or left). Use
     * {@code null} to indicate normal operations mode.
     * @param m the entered programming mode or {@code null}.
     */
    public void modeEntered(ProgrammingMode m);
    
    /**
     * Records the expected accessory state after a command takes effect.
     * The method should be called from a {@link CommandHandler#sent} method
     * if the message changes accessory in the layout which can be later reflected
     * in feedback from the layout.
     * @param accId accessory number
     * @param state the commanded state, one of {@link jmri.Turnout#CLOSED}, {@link jmri.Turnout#THROWN}
     */
    public void expectAccessoryState(int accId, int state);
    
    /**
     * Returns the expected accessory state. The accessory state records last-known
     * state that CAN BE observed by the command station. Specifically, unlike 
     * Turnout cache, it records turnout state change as soon as the operation request
     * is sent, even before the first reply (e.g. feedback) is processed. This is
     * to allow matching whether data from the layout match JMRI state sent to the layout
     * or differs because of some external event.
     * 
     * @param id accessory ID
     * @return state, one of {@link jmri.Turnout#CLOSED}, {@link jmri.Turnout#THROWN}, {@link jmri.Turnout#UNKNOWN}
     */
    public int getAccessoryState(int id);
    
    public boolean send(XNetPlusMessage msg, XNetListener callback);
    public CommandState send(CommandHandler handler, XNetPlusMessage command, XNetListener callback);
}
