package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetTestSimulator;
import jmri.jmrix.lenzplus.port.DefaultPacketizerSupport;
import org.junit.Before;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutNanoXIT extends XNetPlusTurnoutITBase {

    @Before
    @Override
    public void initSimulator() throws Exception {
        initializeLayout(new XNetTestSimulator.NanoXGenLi(true), new DefaultPacketizerSupport());
    }
}
