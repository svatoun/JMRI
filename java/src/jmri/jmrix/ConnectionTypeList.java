package jmri.jmrix;

import javax.annotation.Nonnull;
import jmri.spi.JmriServiceProviderInterface;

/**
 * Definition of objects to handle configuring a layout connection.
 *
 * Implementing classes <em>must</em> be registered as service providers of this
 * type to be recognized and usable.
 * <p>
 * General design documentation is available on the 
 * <a href="http://jmri.org/help/en/html/doc/Technical/SystemStructure.shtml">Structure of External System Connections page</a>.
 *
 * @author Bob Jacobsen Copyright (C) 2001, 2003
 * @see JmrixConfigPane
 * @see ConnectionConfig
 * @see java.util.ServiceLoader
 */
public interface ConnectionTypeList extends JmriServiceProviderInterface {

    /**
     * Get a list of classes that can configure a layout connection for the
     * manufacturers specified in {@link #getManufacturers() }.
     * Until JMRI 4.20, the list can only contain <b>fully qualified names</b>
     * of implementation classes which implement {@link ConnectionConfig}.
     * <p>
     * From JMRI 4.20, the list can contain also factory methods: if the string
     * contains a colon (:), the part before the colon is read as a fully qualified
     * classname (with forward-compatible translation support), and the part after
     * the colon is the number of <b>static no-arg method</b> which must return
     * a {@link ConnectionConfig} instance.
     * <p>
     * This allows to eliminate classes, which only serve as identifiers with no
     * important behaviour changes (i.e. added configuration parameters).
     *
     * @return an Array of classes or an empty Array if none
     */
    @Nonnull
    public String[] getAvailableProtocolClasses();

    /**
     * Get a list of manufacturer names supported by the classes specified in
     * {@link #getAvailableProtocolClasses() }.
     *
     * @return an Array of manufacturers or an empty Array if none
     */
    @Nonnull
    public String[] getManufacturers();
}
