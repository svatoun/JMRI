package jmri.jmrix.lenzplus.config;

/**
 * Returns a list of valid lenz XpressNet Connection Types
 *
 * @author Bob Jacobsen Copyright (C) 2010
 * @author Kevin Dickerson Copyright (C) 2010
 *
 */
//@ServiceProvider(service = ConnectionTypeList.class)
public class CanadaDigiCZConnectionTypeList implements jmri.jmrix.ConnectionTypeList {

    public static final String CANADA = "Paco Canada";
    public static final String DIGICZ = "DIGI-CZ";

    @Override
    public String[] getAvailableProtocolClasses() {
        return new String[]{
        };
    }

    @Override
    public String[] getManufacturers() {
        return new String[] { CANADA, DIGICZ };
    }

}
