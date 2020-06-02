/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.config;

import jmri.jmrix.ConnectionConfig;
import jmri.jmrix.lenz.li100.LI100Adapter;
import jmri.jmrix.lenz.liusb.LIUSBAdapter;
import jmri.util.SystemType;

/**
 *
 * @author sdedic
 * @author Bob Jacobsen Copyright (C) 2001, 2003
 * @see LI100Adapter
 */
public class LenzSerialConnectionConfig extends jmri.jmrix.lenz.AbstractXNetSerialConnectionConfig {
    private final ConnectionType flavour;

    public LenzSerialConnectionConfig(ConnectionType flavour) {
        super();
        this.flavour = flavour;
    }
    
    public LenzSerialConnectionConfig(jmri.jmrix.SerialPortAdapter p, ConnectionType flavour) {
        super(p);
        this.flavour  = flavour;
    }

    @Override
    public String name() {
        return Bundle.getMessage("ConnectionName_" + flavour);
    }

    @Override
    protected String[] getPortFriendlyNames() {
        if (flavour.name().startsWith("liusb") && SystemType.isWindows()) {
            return new String[]{Bundle.getMessage("LIUSBSerialPortOption"), "LI-USB"};
        }
        return new String[]{};
    }

    public ConnectionType getFlavour() {
        return flavour;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    protected void setInstance() {
        if (adapter != null) {
            return;
        }
        switch (flavour) {
            case LI100:
                adapter = new LI100Adapter();
                break;
            case liusb:
            case liusbDR5000:
                adapter = new LIUSBAdapter();
                break;
        }
    }
    
    public static ConnectionConfig LI100() {
        return new LenzSerialConnectionConfig(ConnectionType.LI100);
    }
    
    public static ConnectionConfig liusb() {
        return new LenzSerialConnectionConfig(ConnectionType.liusb);
    }
    
    public static ConnectionConfig liusbDR5000() {
        return new LenzSerialConnectionConfig(ConnectionType.liusbDR5000);
        
    }

    @Override
    public String typeId() {
        return getClass().getName() + ":" + flavour.name();
    }
    
}
