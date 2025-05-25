/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Beans/BeanInfo.java to edit this template
 */
package jmri.jmrit.roster;

import java.beans.*;

/**
 *
 * @author sdedic
 */
public class RosterEntryBeanInfo extends SimpleBeanInfo {

    // Bean descriptor//GEN-FIRST:BeanDescriptor
    /*lazy BeanDescriptor*/
    private static BeanDescriptor getBdescriptor(){
        BeanDescriptor beanDescriptor = new BeanDescriptor  ( jmri.jmrit.roster.RosterEntry.class , null ); // NOI18N//GEN-HEADEREND:BeanDescriptor
        // Here you can add code for customizing the BeanDescriptor.

        return beanDescriptor;     }//GEN-LAST:BeanDescriptor


    // Property identifiers//GEN-FIRST:Properties
    private static final int PROPERTY_attributeList = 0;
    private static final int PROPERTY_attributes = 1;
    private static final int PROPERTY_comment = 2;
    private static final int PROPERTY_dateModified = 3;
    private static final int PROPERTY_dateUpdated = 4;
    private static final int PROPERTY_dccAddress = 5;
    private static final int PROPERTY_dccLocoAddress = 6;
    private static final int PROPERTY_decoderComment = 7;
    private static final int PROPERTY_decoderFamily = 8;
    private static final int PROPERTY_decoderModel = 9;
    private static final int PROPERTY_displayName = 10;
    private static final int PROPERTY_fileName = 11;
    private static final int PROPERTY_functionImage = 12;
    private static final int PROPERTY_functionLabel = 13;
    private static final int PROPERTY_functionLockable = 14;
    private static final int PROPERTY_functionSelectedImage = 15;
    private static final int PROPERTY_groups = 16;
    private static final int PROPERTY_iconPath = 17;
    private static final int PROPERTY_id = 18;
    private static final int PROPERTY_imagePath = 19;
    private static final int PROPERTY_longAddress = 20;
    private static final int PROPERTY_MAXFNNUM = 21;
    private static final int PROPERTY_maxSpeedPCT = 22;
    private static final int PROPERTY_mfg = 23;
    private static final int PROPERTY_model = 24;
    private static final int PROPERTY_open = 25;
    private static final int PROPERTY_owner = 26;
    private static final int PROPERTY_pathName = 27;
    private static final int PROPERTY_propertyChangeListeners = 28;
    private static final int PROPERTY_propertyNames = 29;
    private static final int PROPERTY_protocol = 30;
    private static final int PROPERTY_protocolAsString = 31;
    private static final int PROPERTY_roadName = 32;
    private static final int PROPERTY_roadNumber = 33;
    private static final int PROPERTY_shuntingFunction = 34;
    private static final int PROPERTY_soundLabel = 35;
    private static final int PROPERTY_speedProfile = 36;
    private static final int PROPERTY_URL = 37;

    // Property array 
    /*lazy PropertyDescriptor*/
    private static PropertyDescriptor[] getPdescriptor(){
        PropertyDescriptor[] properties = new PropertyDescriptor[38];
    
        try {
            properties[PROPERTY_attributeList] = new PropertyDescriptor ( "attributeList", jmri.jmrit.roster.RosterEntry.class, "getAttributeList", null ); // NOI18N
            properties[PROPERTY_attributes] = new PropertyDescriptor ( "attributes", jmri.jmrit.roster.RosterEntry.class, "getAttributes", null ); // NOI18N
            properties[PROPERTY_comment] = new PropertyDescriptor ( "comment", jmri.jmrit.roster.RosterEntry.class, "getComment", "setComment" ); // NOI18N
            properties[PROPERTY_dateModified] = new PropertyDescriptor ( "dateModified", jmri.jmrit.roster.RosterEntry.class, "getDateModified", null ); // NOI18N
            properties[PROPERTY_dateUpdated] = new PropertyDescriptor ( "dateUpdated", jmri.jmrit.roster.RosterEntry.class, "getDateUpdated", null ); // NOI18N
            properties[PROPERTY_dccAddress] = new PropertyDescriptor ( "dccAddress", jmri.jmrit.roster.RosterEntry.class, "getDccAddress", "setDccAddress" ); // NOI18N
            properties[PROPERTY_dccLocoAddress] = new PropertyDescriptor ( "dccLocoAddress", jmri.jmrit.roster.RosterEntry.class, "getDccLocoAddress", null ); // NOI18N
            properties[PROPERTY_decoderComment] = new PropertyDescriptor ( "decoderComment", jmri.jmrit.roster.RosterEntry.class, "getDecoderComment", "setDecoderComment" ); // NOI18N
            properties[PROPERTY_decoderFamily] = new PropertyDescriptor ( "decoderFamily", jmri.jmrit.roster.RosterEntry.class, "getDecoderFamily", "setDecoderFamily" ); // NOI18N
            properties[PROPERTY_decoderModel] = new PropertyDescriptor ( "decoderModel", jmri.jmrit.roster.RosterEntry.class, "getDecoderModel", "setDecoderModel" ); // NOI18N
            properties[PROPERTY_displayName] = new PropertyDescriptor ( "displayName", jmri.jmrit.roster.RosterEntry.class, "getDisplayName", null ); // NOI18N
            properties[PROPERTY_fileName] = new PropertyDescriptor ( "fileName", jmri.jmrit.roster.RosterEntry.class, "getFileName", "setFileName" ); // NOI18N
            properties[PROPERTY_functionImage] = new IndexedPropertyDescriptor ( "functionImage", jmri.jmrit.roster.RosterEntry.class, null, null, "getFunctionImage", "setFunctionImage" ); // NOI18N
            properties[PROPERTY_functionLabel] = new IndexedPropertyDescriptor ( "functionLabel", jmri.jmrit.roster.RosterEntry.class, null, null, "getFunctionLabel", "setFunctionLabel" ); // NOI18N
            properties[PROPERTY_functionLockable] = new IndexedPropertyDescriptor ( "functionLockable", jmri.jmrit.roster.RosterEntry.class, null, null, "getFunctionLockable", "setFunctionLockable" ); // NOI18N
            properties[PROPERTY_functionSelectedImage] = new IndexedPropertyDescriptor ( "functionSelectedImage", jmri.jmrit.roster.RosterEntry.class, null, null, "getFunctionSelectedImage", "setFunctionSelectedImage" ); // NOI18N
            properties[PROPERTY_groups] = new PropertyDescriptor ( "groups", jmri.jmrit.roster.RosterEntry.class, "getGroups", null ); // NOI18N
            properties[PROPERTY_iconPath] = new PropertyDescriptor ( "iconPath", jmri.jmrit.roster.RosterEntry.class, "getIconPath", "setIconPath" ); // NOI18N
            properties[PROPERTY_id] = new PropertyDescriptor ( "id", jmri.jmrit.roster.RosterEntry.class, "getId", "setId" ); // NOI18N
            properties[PROPERTY_imagePath] = new PropertyDescriptor ( "imagePath", jmri.jmrit.roster.RosterEntry.class, "getImagePath", "setImagePath" ); // NOI18N
            properties[PROPERTY_longAddress] = new PropertyDescriptor ( "longAddress", jmri.jmrit.roster.RosterEntry.class, "isLongAddress", "setLongAddress" ); // NOI18N
            properties[PROPERTY_MAXFNNUM] = new PropertyDescriptor ( "MAXFNNUM", jmri.jmrit.roster.RosterEntry.class, "getMAXFNNUM", null ); // NOI18N
            properties[PROPERTY_maxSpeedPCT] = new PropertyDescriptor ( "maxSpeedPCT", jmri.jmrit.roster.RosterEntry.class, "getMaxSpeedPCT", "setMaxSpeedPCT" ); // NOI18N
            properties[PROPERTY_mfg] = new PropertyDescriptor ( "mfg", jmri.jmrit.roster.RosterEntry.class, "getMfg", "setMfg" ); // NOI18N
            properties[PROPERTY_model] = new PropertyDescriptor ( "model", jmri.jmrit.roster.RosterEntry.class, "getModel", "setModel" ); // NOI18N
            properties[PROPERTY_open] = new PropertyDescriptor ( "open", jmri.jmrit.roster.RosterEntry.class, "isOpen", "setOpen" ); // NOI18N
            properties[PROPERTY_owner] = new PropertyDescriptor ( "owner", jmri.jmrit.roster.RosterEntry.class, "getOwner", "setOwner" ); // NOI18N
            properties[PROPERTY_pathName] = new PropertyDescriptor ( "pathName", jmri.jmrit.roster.RosterEntry.class, "getPathName", null ); // NOI18N
            properties[PROPERTY_propertyChangeListeners] = new PropertyDescriptor ( "propertyChangeListeners", jmri.jmrit.roster.RosterEntry.class, "getPropertyChangeListeners", null ); // NOI18N
            properties[PROPERTY_propertyNames] = new PropertyDescriptor ( "propertyNames", jmri.jmrit.roster.RosterEntry.class, "getPropertyNames", null ); // NOI18N
            properties[PROPERTY_protocol] = new PropertyDescriptor ( "protocol", jmri.jmrit.roster.RosterEntry.class, "getProtocol", "setProtocol" ); // NOI18N
            properties[PROPERTY_protocolAsString] = new PropertyDescriptor ( "protocolAsString", jmri.jmrit.roster.RosterEntry.class, "getProtocolAsString", null ); // NOI18N
            properties[PROPERTY_roadName] = new PropertyDescriptor ( "roadName", jmri.jmrit.roster.RosterEntry.class, "getRoadName", "setRoadName" ); // NOI18N
            properties[PROPERTY_roadNumber] = new PropertyDescriptor ( "roadNumber", jmri.jmrit.roster.RosterEntry.class, "getRoadNumber", "setRoadNumber" ); // NOI18N
            properties[PROPERTY_shuntingFunction] = new PropertyDescriptor ( "shuntingFunction", jmri.jmrit.roster.RosterEntry.class, "getShuntingFunction", "setShuntingFunction" ); // NOI18N
            properties[PROPERTY_soundLabel] = new IndexedPropertyDescriptor ( "soundLabel", jmri.jmrit.roster.RosterEntry.class, null, null, "getSoundLabel", "setSoundLabel" ); // NOI18N
            properties[PROPERTY_speedProfile] = new PropertyDescriptor ( "speedProfile", jmri.jmrit.roster.RosterEntry.class, "getSpeedProfile", "setSpeedProfile" ); // NOI18N
            properties[PROPERTY_URL] = new PropertyDescriptor ( "URL", jmri.jmrit.roster.RosterEntry.class, "getURL", "setURL" ); // NOI18N
        }
        catch(IntrospectionException e) {
            e.printStackTrace();
        }//GEN-HEADEREND:Properties
        // Here you can add code for customizing the properties array.

        return properties;     }//GEN-LAST:Properties

    // EventSet identifiers//GEN-FIRST:Events
    private static final int EVENT_propertyChangeListener = 0;

    // EventSet array
    /*lazy EventSetDescriptor*/
    private static EventSetDescriptor[] getEdescriptor(){
        EventSetDescriptor[] eventSets = new EventSetDescriptor[1];
    
        try {
            eventSets[EVENT_propertyChangeListener] = new EventSetDescriptor ( jmri.jmrit.roster.RosterEntry.class, "propertyChangeListener", java.beans.PropertyChangeListener.class, new String[] {"propertyChange"}, "addPropertyChangeListener", "removePropertyChangeListener" ); // NOI18N
        }
        catch(IntrospectionException e) {
            e.printStackTrace();
        }//GEN-HEADEREND:Events
        // Here you can add code for customizing the event sets array.

        return eventSets;     }//GEN-LAST:Events

    // Method identifiers//GEN-FIRST:Methods
    private static final int METHOD_addPropertyChangeListener0 = 0;
    private static final int METHOD_addPropertyChangeListener1 = 1;
    private static final int METHOD_changeDateUpdated2 = 2;
    private static final int METHOD_deleteAttribute3 = 3;
    private static final int METHOD_ensureFilenameExists4 = 4;
    private static final int METHOD_fromFile5 = 5;
    private static final int METHOD_getAttribute6 = 6;
    private static final int METHOD_getGroups7 = 7;
    private static final int METHOD_getIndexedProperty8 = 8;
    private static final int METHOD_getProperty9 = 9;
    private static final int METHOD_getPropertyChangeListeners10 = 10;
    private static final int METHOD_hasIndexedProperty11 = 11;
    private static final int METHOD_hasProperty12 = 12;
    private static final int METHOD_loadAttributes13 = 13;
    private static final int METHOD_loadCvModel14 = 14;
    private static final int METHOD_loadFunctions15 = 15;
    private static final int METHOD_loadFunctions16 = 16;
    private static final int METHOD_loadSounds17 = 17;
    private static final int METHOD_printEntry18 = 18;
    private static final int METHOD_printEntryDetails19 = 19;
    private static final int METHOD_printEntryLine20 = 20;
    private static final int METHOD_putAttribute21 = 21;
    private static final int METHOD_readFile22 = 22;
    private static final int METHOD_removePropertyChangeListener23 = 23;
    private static final int METHOD_removePropertyChangeListener24 = 24;
    private static final int METHOD_setDateModified25 = 25;
    private static final int METHOD_setDateModified26 = 26;
    private static final int METHOD_setIndexedProperty27 = 27;
    private static final int METHOD_setProperty28 = 28;
    private static final int METHOD_store29 = 29;
    private static final int METHOD_titleString30 = 30;
    private static final int METHOD_toString31 = 31;
    private static final int METHOD_updateFile32 = 32;
    private static final int METHOD_wrapComment33 = 33;
    private static final int METHOD_writeFile34 = 34;

    // Method array 
    /*lazy MethodDescriptor*/
    private static MethodDescriptor[] getMdescriptor(){
        MethodDescriptor[] methods = new MethodDescriptor[35];
    
        try {
            methods[METHOD_addPropertyChangeListener0] = new MethodDescriptor(jmri.BasicRosterEntry.class.getMethod("addPropertyChangeListener", new Class[] {java.beans.PropertyChangeListener.class})); // NOI18N
            methods[METHOD_addPropertyChangeListener0].setDisplayName ( "" );
            methods[METHOD_addPropertyChangeListener1] = new MethodDescriptor(jmri.beans.Bean.class.getMethod("addPropertyChangeListener", new Class[] {java.lang.String.class, java.beans.PropertyChangeListener.class})); // NOI18N
            methods[METHOD_addPropertyChangeListener1].setDisplayName ( "" );
            methods[METHOD_changeDateUpdated2] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("changeDateUpdated", new Class[] {})); // NOI18N
            methods[METHOD_changeDateUpdated2].setDisplayName ( "" );
            methods[METHOD_deleteAttribute3] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("deleteAttribute", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_deleteAttribute3].setDisplayName ( "" );
            methods[METHOD_ensureFilenameExists4] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("ensureFilenameExists", new Class[] {})); // NOI18N
            methods[METHOD_ensureFilenameExists4].setDisplayName ( "" );
            methods[METHOD_fromFile5] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("fromFile", new Class[] {java.io.File.class})); // NOI18N
            methods[METHOD_fromFile5].setDisplayName ( "" );
            methods[METHOD_getAttribute6] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("getAttribute", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_getAttribute6].setDisplayName ( "" );
            methods[METHOD_getGroups7] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("getGroups", new Class[] {jmri.jmrit.roster.Roster.class})); // NOI18N
            methods[METHOD_getGroups7].setDisplayName ( "" );
            methods[METHOD_getIndexedProperty8] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("getIndexedProperty", new Class[] {java.lang.String.class, int.class})); // NOI18N
            methods[METHOD_getIndexedProperty8].setDisplayName ( "" );
            methods[METHOD_getProperty9] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("getProperty", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_getProperty9].setDisplayName ( "" );
            methods[METHOD_getPropertyChangeListeners10] = new MethodDescriptor(jmri.beans.Bean.class.getMethod("getPropertyChangeListeners", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_getPropertyChangeListeners10].setDisplayName ( "" );
            methods[METHOD_hasIndexedProperty11] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("hasIndexedProperty", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_hasIndexedProperty11].setDisplayName ( "" );
            methods[METHOD_hasProperty12] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("hasProperty", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_hasProperty12].setDisplayName ( "" );
            methods[METHOD_loadAttributes13] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("loadAttributes", new Class[] {org.jdom2.Element.class})); // NOI18N
            methods[METHOD_loadAttributes13].setDisplayName ( "" );
            methods[METHOD_loadCvModel14] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("loadCvModel", new Class[] {jmri.jmrit.symbolicprog.VariableTableModel.class, jmri.jmrit.symbolicprog.CvTableModel.class})); // NOI18N
            methods[METHOD_loadCvModel14].setDisplayName ( "" );
            methods[METHOD_loadFunctions15] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("loadFunctions", new Class[] {org.jdom2.Element.class})); // NOI18N
            methods[METHOD_loadFunctions15].setDisplayName ( "" );
            methods[METHOD_loadFunctions16] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("loadFunctions", new Class[] {org.jdom2.Element.class, java.lang.String.class})); // NOI18N
            methods[METHOD_loadFunctions16].setDisplayName ( "" );
            methods[METHOD_loadSounds17] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("loadSounds", new Class[] {org.jdom2.Element.class, java.lang.String.class})); // NOI18N
            methods[METHOD_loadSounds17].setDisplayName ( "" );
            methods[METHOD_printEntry18] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("printEntry", new Class[] {jmri.util.davidflanagan.HardcopyWriter.class})); // NOI18N
            methods[METHOD_printEntry18].setDisplayName ( "" );
            methods[METHOD_printEntryDetails19] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("printEntryDetails", new Class[] {java.io.Writer.class})); // NOI18N
            methods[METHOD_printEntryDetails19].setDisplayName ( "" );
            methods[METHOD_printEntryLine20] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("printEntryLine", new Class[] {jmri.util.davidflanagan.HardcopyWriter.class})); // NOI18N
            methods[METHOD_printEntryLine20].setDisplayName ( "" );
            methods[METHOD_putAttribute21] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("putAttribute", new Class[] {java.lang.String.class, java.lang.String.class})); // NOI18N
            methods[METHOD_putAttribute21].setDisplayName ( "" );
            methods[METHOD_readFile22] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("readFile", new Class[] {})); // NOI18N
            methods[METHOD_readFile22].setDisplayName ( "" );
            methods[METHOD_removePropertyChangeListener23] = new MethodDescriptor(jmri.BasicRosterEntry.class.getMethod("removePropertyChangeListener", new Class[] {java.beans.PropertyChangeListener.class})); // NOI18N
            methods[METHOD_removePropertyChangeListener23].setDisplayName ( "" );
            methods[METHOD_removePropertyChangeListener24] = new MethodDescriptor(jmri.beans.Bean.class.getMethod("removePropertyChangeListener", new Class[] {java.lang.String.class, java.beans.PropertyChangeListener.class})); // NOI18N
            methods[METHOD_removePropertyChangeListener24].setDisplayName ( "" );
            methods[METHOD_setDateModified25] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("setDateModified", new Class[] {java.util.Date.class})); // NOI18N
            methods[METHOD_setDateModified25].setDisplayName ( "" );
            methods[METHOD_setDateModified26] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("setDateModified", new Class[] {java.lang.String.class})); // NOI18N
            methods[METHOD_setDateModified26].setDisplayName ( "" );
            methods[METHOD_setIndexedProperty27] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("setIndexedProperty", new Class[] {java.lang.String.class, int.class, java.lang.Object.class})); // NOI18N
            methods[METHOD_setIndexedProperty27].setDisplayName ( "" );
            methods[METHOD_setProperty28] = new MethodDescriptor(jmri.beans.ArbitraryBean.class.getMethod("setProperty", new Class[] {java.lang.String.class, java.lang.Object.class})); // NOI18N
            methods[METHOD_setProperty28].setDisplayName ( "" );
            methods[METHOD_store29] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("store", new Class[] {})); // NOI18N
            methods[METHOD_store29].setDisplayName ( "" );
            methods[METHOD_titleString30] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("titleString", new Class[] {})); // NOI18N
            methods[METHOD_titleString30].setDisplayName ( "" );
            methods[METHOD_toString31] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("toString", new Class[] {})); // NOI18N
            methods[METHOD_toString31].setDisplayName ( "" );
            methods[METHOD_updateFile32] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("updateFile", new Class[] {})); // NOI18N
            methods[METHOD_updateFile32].setDisplayName ( "" );
            methods[METHOD_wrapComment33] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("wrapComment", new Class[] {java.lang.String.class, int.class})); // NOI18N
            methods[METHOD_wrapComment33].setDisplayName ( "" );
            methods[METHOD_writeFile34] = new MethodDescriptor(jmri.jmrit.roster.RosterEntry.class.getMethod("writeFile", new Class[] {jmri.jmrit.symbolicprog.CvTableModel.class, jmri.jmrit.symbolicprog.VariableTableModel.class})); // NOI18N
            methods[METHOD_writeFile34].setDisplayName ( "" );
        }
        catch( Exception e) {}//GEN-HEADEREND:Methods
        // Here you can add code for customizing the methods array.

        return methods;     }//GEN-LAST:Methods

    private static java.awt.Image iconColor16 = null;//GEN-BEGIN:IconsDef
    private static java.awt.Image iconColor32 = null;
    private static java.awt.Image iconMono16 = null;
    private static java.awt.Image iconMono32 = null;//GEN-END:IconsDef
    private static String iconNameC16 = null;//GEN-BEGIN:Icons
    private static String iconNameC32 = null;
    private static String iconNameM16 = null;
    private static String iconNameM32 = null;//GEN-END:Icons

    private static final int defaultPropertyIndex = -1;//GEN-BEGIN:Idx
    private static final int defaultEventIndex = -1;//GEN-END:Idx


//GEN-FIRST:Superclass
    // Here you can add code for customizing the Superclass BeanInfo.

//GEN-LAST:Superclass
    /**
     * Gets the bean's <code>BeanDescriptor</code>s.
     *
     * @return BeanDescriptor describing the editable properties of this bean.
     *         May return null if the information should be obtained by
     *         automatic analysis.
     */
    @Override
    public BeanDescriptor getBeanDescriptor() {
        return getBdescriptor();
    }

    /**
     * Gets the bean's <code>PropertyDescriptor</code>s.
     *
     * @return An array of PropertyDescriptors describing the editable
     *         properties supported by this bean. May return null if the
     *         information should be obtained by automatic analysis.
     * <p>
     * If a property is indexed, then its entry in the result array will belong
     * to the IndexedPropertyDescriptor subclass of PropertyDescriptor. A client
     * of getPropertyDescriptors can use "instanceof" to check if a given
     * PropertyDescriptor is an IndexedPropertyDescriptor.
     */
    @Override
    public PropertyDescriptor[] getPropertyDescriptors() {
        return getPdescriptor();
    }

    /**
     * Gets the bean's <code>EventSetDescriptor</code>s.
     *
     * @return An array of EventSetDescriptors describing the kinds of events
     *         fired by this bean. May return null if the information should be
     *         obtained by automatic analysis.
     */
    @Override
    public EventSetDescriptor[] getEventSetDescriptors() {
        return getEdescriptor();
    }

    /**
     * Gets the bean's <code>MethodDescriptor</code>s.
     *
     * @return An array of MethodDescriptors describing the methods implemented
     *         by this bean. May return null if the information should be
     *         obtained by automatic analysis.
     */
    @Override
    public MethodDescriptor[] getMethodDescriptors() {
        return getMdescriptor();
    }

    /**
     * A bean may have a "default" property that is the property that will
     * mostly commonly be initially chosen for update by human's who are
     * customizing the bean.
     *
     * @return Index of default property in the PropertyDescriptor array
     *         returned by getPropertyDescriptors.
     * <P>
     * Returns -1 if there is no default property.
     */
    @Override
    public int getDefaultPropertyIndex() {
        return defaultPropertyIndex;
    }

    /**
     * A bean may have a "default" event that is the event that will mostly
     * commonly be used by human's when using the bean.
     *
     * @return Index of default event in the EventSetDescriptor array returned
     *         by getEventSetDescriptors.
     * <P>
     * Returns -1 if there is no default event.
     */
    @Override
    public int getDefaultEventIndex() {
        return defaultEventIndex;
    }

    /**
     * This method returns an image object that can be used to represent the
     * bean in toolboxes, toolbars, etc. Icon images will typically be GIFs, but
     * may in future include other formats.
     * <p>
     * Beans aren't required to provide icons and may return null from this
     * method.
     * <p>
     * There are four possible flavors of icons (16x16 color, 32x32 color, 16x16
     * mono, 32x32 mono). If a bean choses to only support a single icon we
     * recommend supporting 16x16 color.
     * <p>
     * We recommend that icons have a "transparent" background so they can be
     * rendered onto an existing background.
     *
     * @param iconKind The kind of icon requested. This should be one of the
     *                 constant values ICON_COLOR_16x16, ICON_COLOR_32x32,
     *                 ICON_MONO_16x16, or ICON_MONO_32x32.
     * @return An image object representing the requested icon. May return null
     *         if no suitable icon is available.
     */
    @Override
    public java.awt.Image getIcon(int iconKind) {
        switch (iconKind) {
            case ICON_COLOR_16x16:
                if (iconNameC16 == null) {
                    return null;
                } else {
                    if (iconColor16 == null) {
                        iconColor16 = loadImage(iconNameC16);
                    }
                    return iconColor16;
                }
            case ICON_COLOR_32x32:
                if (iconNameC32 == null) {
                    return null;
                } else {
                    if (iconColor32 == null) {
                        iconColor32 = loadImage(iconNameC32);
                    }
                    return iconColor32;
                }
            case ICON_MONO_16x16:
                if (iconNameM16 == null) {
                    return null;
                } else {
                    if (iconMono16 == null) {
                        iconMono16 = loadImage(iconNameM16);
                    }
                    return iconMono16;
                }
            case ICON_MONO_32x32:
                if (iconNameM32 == null) {
                    return null;
                } else {
                    if (iconMono32 == null) {
                        iconMono32 = loadImage(iconNameM32);
                    }
                    return iconMono32;
                }
            default:
                return null;
        }
    }
    
}
