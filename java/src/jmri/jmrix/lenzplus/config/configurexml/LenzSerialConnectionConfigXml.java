/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.config.configurexml;

import jmri.jmrix.lenz.configurexml.AbstractXNetSerialConnectionConfigXml;
import jmri.jmrix.lenz.li100.LI100Adapter;
import jmri.jmrix.lenz.liusb.LIUSBAdapter;
import jmri.jmrix.lenzplus.config.ConnectionType;
import jmri.jmrix.lenzplus.config.LenzSerialConnectionConfig;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
public class LenzSerialConnectionConfigXml extends AbstractXNetSerialConnectionConfigXml {
    private ConnectionType flavour;
    private LenzSerialConnectionConfig config;
    
    @Override
    protected void getInstance() {
        if (adapter != null) {
            return;
        }
//        LenzPlusSystemConnectionMemo memo = new LenzPlusSystemConnectionMemo();
        switch (flavour) {
            case GEN_LI:
            case LI100:
                adapter = new LI100Adapter();
                break;
            case liusb:
            case liusbDR5000:
                adapter = new LIUSBAdapter();
                break;
            default:
                throw new IllegalArgumentException(flavour.name());
        }
//        adapter.setSystemConnectionMemo(memo);
    }

    @Override
    protected void getInstance(Object object) {
        config = ((LenzSerialConnectionConfig)object);
        adapter = config.getAdapter();
    }

    @Override
    protected void register() {
        this.register(new LenzSerialConnectionConfig(adapter, flavour));
    }

    public boolean load(Element shared, Element perNode) {
        flavour = ConnectionType.valueOf(perNode.getAttributeValue("flavour"));
        return super.load(shared, perNode);
    }
    
    protected void extendElement(Element e) {
        e.setAttribute("flavour", config.getFlavour().name());
    }

    private static final Logger log = LoggerFactory.getLogger(LenzSerialConnectionConfigXml.class);

}
