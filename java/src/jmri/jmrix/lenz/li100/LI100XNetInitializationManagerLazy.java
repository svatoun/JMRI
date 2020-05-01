package jmri.jmrix.lenz.li100;

import jmri.jmrix.lenz.XNetInitializationManagerLazy;
import jmri.jmrix.lenz.XNetProgrammerManager;
import jmri.jmrix.lenz.XNetSystemConnectionMemo;

/**
 * An example, how the LI100XNetInitializationManager could be reduced in code,
 * with a changed XNetInitializationManager. Doing actions in a constructor is 
 * a completely screwed design that damages code reusability.
 */
public class LI100XNetInitializationManagerLazy extends XNetInitializationManagerLazy {

    public LI100XNetInitializationManagerLazy(XNetSystemConnectionMemo memo) {
        super(memo);
    }
    
    @Override
    protected XNetProgrammerManager createProgrammerManager() {
        return new XNetProgrammerManager(new LI100XNetProgrammer(systemMemo.getXNetTrafficController()), systemMemo);
    }
}
