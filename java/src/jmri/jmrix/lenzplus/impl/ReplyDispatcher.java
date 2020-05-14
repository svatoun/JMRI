/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.StateMemento;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author sdedic
 */
public interface ReplyDispatcher {

    public void snapshot(StateMemento m);

    public void update(StateMemento m);

    public void commit(StateMemento m, boolean wakeUp);

    public ReplyOutcome distributeReply(StateMemento m, XNetPlusReply msg, XNetListener target);
    
    public void commandFinished(StateMemento m, ReplyOutcome outcome);
}
