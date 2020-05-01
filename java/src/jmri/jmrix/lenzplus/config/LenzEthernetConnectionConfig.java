package jmri.jmrix.lenzplus.config;

import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.liusbethernet.*;
import jmri.jmrix.lenzplus.USBPacketizerSupport;
import jmri.jmrix.lenzplus.XNetPlusTrafficController;

/**
 */
public class LenzEthernetConnectionConfig extends ConnectionConfig {
    private final ConnectionType flavour;
    
    /**
     * Ctor for an object being created during load process.Swing init is deferred.
     * @param p
     */
    public LenzEthernetConnectionConfig(jmri.jmrix.NetworkPortAdapter p, ConnectionType flavour) {
        super(p);
        this.flavour = flavour;
    }

    /**
     * Ctor for a connection configuration with no preexisting adapter.
     * {@link #setInstance()} will fill the adapter member.
     */
    public LenzEthernetConnectionConfig(ConnectionType flavour) {
        super();
        this.flavour = flavour;
    }

    @Override
    public String name() {
        return Bundle.getMessage("ConnectionName_" + flavour);
    }
    
    protected void setInstance() {
        super.setInstance();
        LenzPlusSystemConnectionMemo memo = new LenzPlusSystemConnectionMemo();
        adapter.setSystemConnectionMemo(memo);
        ((LIUSBEthernetAdapter)adapter).setPacketizer(
                new XNetPlusTrafficController(new LenzCommandStation()).
                        setPacketizer(new USBPacketizerSupport())
        );
    }
    
    public ConnectionType getFlavour() {
        return flavour;
    }

    public static ConnectionConfig liusbnetDR5000() {
        return new LenzEthernetConnectionConfig(ConnectionType.liusbnetDR5000);
    }

    public static ConnectionConfig liusbEthernet() {
        return new LenzEthernetConnectionConfig(ConnectionType.liusbEthernet);
    }

    @Override
    public String typeName() {
        return getClass().getName() + ":" + flavour.name();
    }
}
