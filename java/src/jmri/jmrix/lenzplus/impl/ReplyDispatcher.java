package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.StateMemento;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface ReplyDispatcher {

    public void snapshot(StateMemento m);

    public void update(StateMemento m);

    public void commit(StateMemento m, boolean wakeUp);

    public ReplyOutcome distributeReply(StateMemento m, XNetPlusReply msg, XNetListener target);
    
    public void commandFinished(StateMemento m, ReplyOutcome outcome);
}
