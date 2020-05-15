/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;

/**
 * Allows CommandHandlers controlled access to the rest of the system.
 * @author sdedic
 */
public interface CommandService {
    public void requestAccessoryStatus(int id);
    public void expectAccessoryState(int accId, int state);
    public int getAccessoryState(int id);
    
    public boolean send(XNetPlusMessage msg, XNetListener callback);
    public CommandState send(CommandHandler handler, XNetPlusMessage command, XNetListener callback);
}
