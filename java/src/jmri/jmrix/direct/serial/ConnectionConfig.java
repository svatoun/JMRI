package jmri.jmrix.direct.serial;

import jmri.util.SystemType;

/**
 * Definition of objects to handle configuring a layout connection via a
 * SerialDriverAdapter object.
 *
 * @author Bob Jacobsen Copyright (C) 2001, 2003
 */
public class ConnectionConfig extends jmri.jmrix.AbstractSerialConnectionConfig {

    /**
     * Ctor for an object being created during load process; Swing init is
     * deferred.
     * @param p serial port adapter.
     */
    public ConnectionConfig(jmri.jmrix.SerialPortAdapter p) {
        super(p);
    }

    /**
     * Ctor for a connection configuration with no preexisting adapter.
     * {@link #setInstance()} will fill the adapter member.
     */
    public ConnectionConfig() {
        super();
    }

    @Override
    public String name() {
        if (SystemType.isMacOSX()
                || (SystemType.isWindows() && Double.valueOf(System.getProperty("os.version")) >= 6)) {
            return Bundle.getMessage("DirectSerialNameNot");
        }

        return Bundle.getMessage("DirectSerialName");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setInstance() {
        if (adapter == null) {
            adapter = new SerialDriverAdapter();
        }
    }
    
}
