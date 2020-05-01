package jmri.jmrix.lenzplus.config;

import jmri.jmrix.lenz.*;
import jmri.jmrix.ConnectionTypeList;
import org.openide.util.lookup.ServiceProvider;

/**
 * Returns a list of valid lenz XpressNet Connection Types
 *
 * @author Bob Jacobsen Copyright (C) 2010
 * @author Kevin Dickerson Copyright (C) 2010
 *
 */
@ServiceProvider(service = ConnectionTypeList.class)
public class DigikeijsConnectionTypeList implements jmri.jmrix.ConnectionTypeList {
    public static final String DIGIKEIJS = "Digikeijs";

    @Override
    public String[] getAvailableProtocolClasses() {
        return new String[] {
            "jmri.jmrix.lenzplus.config.LenzSerialConnectionConfig:liusbDR5000",
            "jmri.jmrix.lenzplus.config.LenzEthernetConnectionConfig:liusbnetDR5000",
        };
    }

    @Override
    public String[] getManufacturers() {
        return new String[] { DIGIKEIJS };
    }
}
