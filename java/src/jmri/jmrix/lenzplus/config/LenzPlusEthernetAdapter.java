/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.config;

import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetInitializationManager;
import jmri.jmrix.lenz.XNetTrafficController;
import jmri.jmrix.lenz.liusbethernet.LIUSBEthernetAdapter;
import jmri.jmrix.lenzplus.port.USBPacketizerSupport;
import jmri.jmrix.lenzplus.XNetPlusTrafficController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class LenzPlusEthernetAdapter extends LIUSBEthernetAdapter {
    
    public void configure() {
        log.debug("configure called");
        // connect to a packetizing traffic controller
        XNetTrafficController packets = new XNetPlusTrafficController(new LenzCommandStation()).
                        setPacketizer(new USBPacketizerSupport());
        packets.connectPort(this);

        // start operation
        // packets.startThreads();
        this.getSystemConnectionMemo().setXNetTrafficController(packets);

        new XNetInitializationManager(this.getSystemConnectionMemo());
        new jmri.jmrix.lenz.XNetHeartBeat(this.getSystemConnectionMemo());
    }
    
    private static final Logger log = LoggerFactory.getLogger(LenzPlusEthernetAdapter.class);
}
