package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetTestSimulator;
import jmri.jmrix.lenzplus.port.USBPacketizerSupport;
import org.junit.Before;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutDR5kIT extends XNetPlusTurnoutITBase {

    @Before
    public void initSimulator() throws Exception {
        initializeLayout(new XNetTestSimulator.DR5000(true), new USBPacketizerSupport());
    }
}
