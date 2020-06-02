package jmri.jmrix.lenzplus;

import jmri.Turnout;
import jmri.jmrix.lenz.XNetAddress;
import jmri.jmrix.lenz.XNetSystemConnectionMemo;
import jmri.jmrix.lenz.XNetTurnoutManager;

/**
 * Simple extension which will just create {@link XNetPlusTurnout}s.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutManager extends XNetTurnoutManager {

    public XNetPlusTurnoutManager(XNetSystemConnectionMemo memo) {
        super(memo);
    }

    @Override
    public Turnout createNewTurnout(String systemName, String userName) {
        // check if the output bit is available
        int bitNum = XNetAddress.getBitFromSystemName(systemName, getSystemPrefix());
        if (bitNum == -1) {
            return (null);
        }
        // create the new Turnout object
        Turnout t = new XNetPlusTurnout(getSystemPrefix(), bitNum, tc);
        t.setUserName(userName);
        return t;
    }
}
