package jmri.jmrix.lenzplus.config.configurexml;

import jmri.jmrix.configurexml.AbstractNetworkConnectionConfigXml;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.liusbethernet.LIUSBEthernetAdapter;
import jmri.jmrix.lenzplus.port.USBPacketizerSupport;
import jmri.jmrix.lenzplus.XNetPlusTrafficController;
import jmri.jmrix.lenzplus.config.LenzPlusSystemConnectionMemo;
import jmri.jmrix.lenzplus.config.ConnectionType;
import jmri.jmrix.lenzplus.config.LenzEthernetConnectionConfig;
import jmri.jmrix.lenzplus.config.LenzPlusEthernetAdapter;
import org.jdom2.Element;

/**
 * Handle XML persistance of layout connections by persistening the LIUSB Server
 * (and connections). Note this is named as the XML version of a
 * ConnectionConfig object, but it's actually persisting the LIUSB Server.
 * <p>
 * NOTE: The LIUSB Server currently has no options, so this class does not store
 * any.
 * <p>
 * This class is invoked from jmrix.JmrixConfigPaneXml on write, as that class
 * is the one actually registered. Reads are brought here directly via the class
 * attribute in the XML.
 *
 * @author Paul Bender Copyright (C) 2011
 */
public class LenzEthernetConnectionConfigXml extends AbstractNetworkConnectionConfigXml {
    private ConnectionType flavour;
    private LenzEthernetConnectionConfig config;
    
    @Override
    protected void getInstance() {
        if (adapter != null) {
            return;
        }
        LenzPlusSystemConnectionMemo memo = new LenzPlusSystemConnectionMemo();
        LenzPlusEthernetAdapter a = new LenzPlusEthernetAdapter();
        a.setSystemConnectionMemo(memo);
        adapter = a;
    }

    @Override
    protected void getInstance(Object object) {
        config = ((LenzEthernetConnectionConfig)object);
        adapter = config.getAdapter();
    }

    @Override
    protected void register() {
        this.register(new LenzEthernetConnectionConfig(adapter, flavour));
    }

    @Override
    public boolean load(Element shared, Element perNode) {
        flavour = ConnectionType.valueOf(perNode.getAttributeValue("flavour"));
        return super.load(shared, perNode);
    }
    
    @Override
    protected void extendElement(Element e) {
        e.setAttribute("flavour", config.getFlavour().name());
    }

}
