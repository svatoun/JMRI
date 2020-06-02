package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetTestSimulator;
import jmri.jmrix.lenzplus.port.USBPacketizerSupport;
import org.junit.Before;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetPlusTurnoutLZV100UsbIT extends XNetPlusTurnoutITBase {

    @Before
    @Override
    public void initSimulator() throws Exception {
        initializeLayout(new XNetTestSimulator.LZV100_USB(true), new USBPacketizerSupport());
    }
}
