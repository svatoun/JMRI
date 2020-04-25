package jmri.jmrix;

import javax.swing.JPanel;

/**
 * Interface for objects that handle configuring a layout connection.
 * <p>
 * General design documentation is available on the 
 * <a href="http://jmri.org/help/en/html/doc/Technical/SystemStructure.shtml">Structure of External System Connections page</a>.
 *
 * @author Bob Jacobsen Copyright (C) 2001, 2003
 * @see JmrixConfigPane
 * @see PortAdapter
 */
public interface ConnectionConfig {

    public String name();

    public String getInfo();

    public PortAdapter getAdapter();

    public String getConnectionName();

    public String getManufacturer();

    public void setManufacturer(String Manufacturer);

    /** 
     * Load the Swing widgets needed to configure
     * this connection into a specified JPanel.
     * Used during the configuration process to 
     * fill out the preferences window with 
     * content specific to this Connection type.
     * The JPanel contents need to handle their own
     * gets/sets to the underlying Connection content.
     *
     * @param details the specific Swing object to be configured and filled
     */
    public void loadDetails(JPanel details);

    /**
     * Register the ConnectionConfig with the running JMRI process.
     * <p>
     * At a minimum, is responsible for:
     * <ul>
     * <li>Registering this object with the ConfigurationManager for persistance, typically at the "Preferences" level
     * <li>Adding this object to the default (@link ConnectionConfigManager}
     * </ul>
     */
    public void register();
    
    /** 
     * Done with this ConnectionConfig object.
     * Invoked in {@link JmrixConfigPane} when switching
     * away from this particular mode. 
     */
    public void dispose();

    public boolean getDisabled();

    public void setDisabled(boolean disabled);

    /**
     * Determine if configuration needs to be written to disk.
     *
     * @return true if configuration needs to be saved, false otherwise
     */
    public boolean isDirty();

    /**
     * Determine if application needs to be restarted for configuration changes
     * to be applied.
     *
     * @return true if application needs to restart, false otherwise
     */
    public boolean isRestartRequired();
    
    /**
     * Returns the connection type name. Unless the connection config implementation
     * is shared between several connection types, should return the implementation
     * class's {@link Class#getName()} for compatibility. 
     * <p>
     * The value must be exactly the same, as one of the values returned by 
     * {@link ConnectionTypeList#getAvailableProtocolClasses()}.
     * <p>
     * The default implementation returns the class name.
     * @return connection type name/identifier.
     * @since 4.20
     */
    public default String typeName() {
        return getClass().getName();
    }
}
