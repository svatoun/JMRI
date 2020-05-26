package jmri.jmrix.lenzplus.comm;

import jmri.ProgrammingMode;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import org.openide.util.Lookup;

/**
 * Allows CommandHandlers controlled access to the rest of the system.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface CommandService extends Lookup.Provider {
    /**
     * The current operating mode. {@code null} means normal operations.
     * @return 
     */
    public ProgrammingMode getMode();
    
    /**
     * Informs the service that a programming mode has been entered (or left).
     * @param m 
     */
    public void modeEntered(ProgrammingMode m);
    
    /**
     * Records the expected accessory state after a command takes effect.
     * The method should be called from a {@link CommandHandler#sent} method
     * if the message changes accessory in the layout which can be later reflected
     * in feedback from the layout.
     * @param accId accessory number
     * @param state the commanded state
     */
    public void expectAccessoryState(int accId, int state);
    
    /**
     * Returns the expected accessory state. 
     * @param id
     * @return 
     */
    public int getAccessoryState(int id);
    
    public boolean send(XNetPlusMessage msg, XNetListener callback);
    public CommandState send(CommandHandler handler, XNetPlusMessage command, XNetListener callback);
}
