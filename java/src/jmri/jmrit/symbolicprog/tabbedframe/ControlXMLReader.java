package jmri.jmrit.symbolicprog.tabbedframe;

import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javafx.scene.input.Mnemonic;
import javax.swing.*;
import javax.swing.table.*;
import jmri.jmrit.roster.RosterEntry;
import jmri.jmrit.symbolicprog.*;
import jmri.jmrit.symbolicprog.tabbedframe.GridBagLayoutBuilder.Grid;
import jmri.util.CvUtil;
import jmri.util.StringUtil;
import jmri.util.jdom.LocaleSelector;
import jmri.util.swing.Mnemonics;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reader of the decoder/panel XML format. This class reads DecoderPro XML definitions and builds
 * JComponents. But actual placing to a particular layout is delegated to {@link LayoutBuilder}.
 * <p/>
 * The {@code ControlXMLReader} provides a variable context to the processing, collects CVs used,
 * etc. The processing itself is done by {@link Processor} and its subclasses. The Processor
 * subclasses each process a container, allowing only specific elements to be recognized. Processor
 * instances also provide variable state for each container level processed, so they don't need
 * to be passed as parameters through chain of method calls. 
 * Processor may also modify how certain content items are interpreted.
 * <p/>
 * 
 * TODO:
 * - support mnemonic for labels, minus MacOSX
 * 
 * @author sdedic
 */
public class ControlXMLReader {
    private static final Logger LOG = LoggerFactory.getLogger(ControlXMLReader.class);
    
    public static final String VAR_SHORT_ADDRESS = "Short Address"; // NOI18N
    public static final String VAR_ADDRESS_FORMAT = "Address Format"; // NOI18N
    public static final String VAR_LONG_ADDRESS = "Long Address"; // NOI18N
    public static final String VAR_CONSIST_ADDRESS = "Consist Address"; // NOI18N
    
    public static final String ITEM_GROUP = "group"; // NOI18N
    public static final String ITEM_GRID = "grid"; // NOI18N
    public static final String ITEM_GRIDITEM = "griditem"; // NOI18N
    public static final String ITEM_ROW = "row"; // NOI18N
    public static final String ITEM_COLUMN = "column"; // NOI18N // NOI18N
    public static final String ITEM_FN_MAPPING = "fnmapping"; // NOI18N
    public static final String ITEM_DCC_ADDRESS = "dccaddress"; // NOI18N
    public static final String ITEM_CVTABLW = "cvtable"; // NOI18N
    public static final String ITEM_SOUNDLABEL = "soundlabel"; // NOI18N
    public static final String ITEM_LABEL = "label"; // NOI18N
    public static final String ITEM_DISPLAY = "display"; // NOI18N
    public static final String ITEM_SEPARATOR = "separator"; // NOI18N

    /**
     * Default variable layout, if not specified or unknown
     */
    private static final String DEFATTR_LAYOUT = "left"; // NOI18N
    private static final String DEFATTR_FORMAT = "default"; // NOI18N

    private static final String ATTR_EXT_FNS_ESU = "extFnsESU"; // NOI18N
    private static final String ATTR_FORMAT = "format"; // NOI18N
    private static final String ATTR_ITEM = "item"; // NOI18N
    private static final String ATTR_LABEL = "label"; // NOI18N
    private static final String ATTR_LAYOUT = "layout"; // NOI18N
    private static final String ATTR_NUM = "num"; // NOI18N
    private static final String ATTR_TEXT = "text"; // NOI18N
    private static final String ATTR_TOOLTIP = "tooltip"; // NOI18N

    /**
     * Marker component instance, which causes a "unknown name" message
     * to be logged.
     */
    protected static final JPanel UNKNOWN = new JPanel();
    
    /**
     * Root of the constructed panel.
     */
    protected final Element root;
    
    /**
     * Model for raw CV values.
     */
    protected final CvTableModel _cvModel;
    
    /**
     * Model for variables.
     */
    protected final VariableTableModel _varModel;
    
    /**
     * The roster entry being edited.
     */
    protected final RosterEntry rosterEntry;
    
    /**
     * The decoder model element.
     */
    protected final Element modelElem;
    
    
    /**
     * Controls the default for variable's label, if not defined by I18N label.
     */
    private boolean showStdName;
    
    /**
     * The current layout builder instance.
     */
    protected LayoutBuilder layoutBuilder;
    
    /**
     * True, if the panel contains CV value table. Will be initialized during
     * panel construction.
     */
    protected boolean isCvTablePane = false;
    
    /**
     * List of CV values displayed in the panel.
     */
    protected final TreeSet<Integer> cvList = new TreeSet<>(); //  TreeSet is iterated in order
    
    /**
     * This remembers the variables on this pane for the Read/Write sheet
     * operation. They are stored as a list of Integer objects, each of which is
     * the index of the Variable in the VariableTable.
     */
    protected final List<Integer> varList = new ArrayList<>();

    /**
     * list of fnMapping objects to dispose
     */
    protected final ArrayList<FnMapPanel> fnMapList = new ArrayList<>();
    
    /**
     * List of ESU functions to dispose.
     */
    protected final ArrayList<FnMapPanelESU> fnMapListESU = new ArrayList<>();
    
    /**
     * list of JPanel objects to removeAll
     */
    protected final ArrayList<JPanel> panelList = new ArrayList<>();
    
    /**
     * The current element being parsed.
     */
    protected Element e;
    
    public ControlXMLReader(Element root, CvTableModel cvModel, VariableTableModel tableModel, RosterEntry re, Element modelElem) {
        this.root = root;
        this._cvModel= cvModel;
        this._varModel = tableModel;
        this.rosterEntry = re;
        this.modelElem = modelElem;
    }
    
    public boolean isShowStdName() {
        return showStdName;
    }

    public void setShowStdName(boolean showStdName) {
        this.showStdName = showStdName;
    }
    
    protected void setElement(Element e) {
        this.e = e;
    }

    public boolean isIsCvTablePane() {
        return isCvTablePane;
    }

    public ArrayList<FnMapPanel> getFnMapList() {
        return fnMapList;
    }

    public ArrayList<FnMapPanelESU> getFnMapListESU() {
        return fnMapListESU;
    }

    public ArrayList<JPanel> getPanelList() {
        return panelList;
    }

    public TreeSet<Integer> getCvList() {
        return cvList;
    }

    public List<Integer> getVarList() {
        return varList;
    }
    
    private int processorLevel = 1;
    
    /**
     * Processes the element using the specified processor. Logs trace
     * messages before and after and delegates all real work to the processor
     * @param p
     * @param e
     * @return 
     */
    protected JComponent switchProcessor(Processor p, Element e) {
        LOG.trace("Entering processor: {}:{}", ++processorLevel, p);
        try {
            return p.interpretElement();
        } finally {
            LOG.trace("Exiting processor: {}:{}", processorLevel--, p);
        }
    }

    // PENDING: better setup, so that one can use subclasses of ControlXMLReader
    // to 
    public JPanel build(JPanel rootPane, LayoutBuilder rootPanelBuilder) {
        LOG.debug("Start building pane");
        this.layoutBuilder = rootPanelBuilder;
        Root proc = new Root();
        proc.processContent(root);
        return (JPanel)addPanel(layoutBuilder.build());
    }
    
    /**
     * If component is a panel, adds it to the panel list. Returns
     * the input parameter.
     * @param p created component
     * @return the same instance as p
     */
    private JComponent addPanel(JComponent p) {
        if (p instanceof JPanel) {
            panelList.add((JPanel)p);
        }
        return p;
    }
    
    protected Processor defaultProcessor() {
        return new Processor();
    }
    
    private String findElementName(Element e) {
        String s = e.getAttributeValue("item");
        if (s == null || "".equals(s)) {
            s = e.getAttributeValue("CV");
            if (s != null && !"".equals(s)) {
                s = "CV_" + s;
            }
        }
        if (s == null || "".equals(s)) {
            s = e.getAttributeValue("label");
        }
        if (s == null || "".equals(s)) {
            Element parent = e.getParentElement();
            int counter = 1;
            boolean foundNext = false;
            boolean found = false;
            for (Element c : parent.getChildren()) {
                if (c == e) {
                    found = true;
                }
                if (c.getName().equals(e.getName())) {
                    if (found) {
                        foundNext = true;
                        break;
                    } else {
                        counter++;
                    }
                }
            }
            if (foundNext) {
                s = "" + counter;
            } else {
                return e.getName();
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(e.getName());
        sb.append("[").append(s).append("]");
        return sb.toString();
    }
    
    private String unknownItemPath(Element e) {
        StringBuilder sb = new StringBuilder();
        while (e.getParentElement() != null) {
            String prepend = findElementName(e);
            if (sb.length() == 0) {
                sb.append(prepend);
            } else {
                sb.insert(0, prepend + "/");
            }
            e = e.getParentElement();
        }
        return sb.toString();
    }
    
    /**
     * Names of "ignored" content elements. They have either a special meaning (pane/name)
     * or are processed out of band by the parent (qualifier).
     */
    // PENDING: make extensible.
    private static final Set<String>  IGNORED_CONTENT = new HashSet<>(Arrays.asList(
        "qualifier",
        "name"
    ));
    
    /**
     * Processes one (or more) level of container. Some containers, like column and row
     * do not need any special variables. Grid containers work with x-y coordinates which
     * are incremented, so it saves the current values as member variables.
     * The {@link Processor} serves for columns, rows and groups. See {@link Grid} for
     * grid discussion.
     * <p/>
     * Global context, variable and panel lists are collected in the outer class' instance.
     * The Processor should contain variables for its own container or scope only.
     * <p/>
     * {@code make*} methods are responsible for creating a JComponent, based on the XML
     * configuration <b>and</b> for insertion of the component into the layout, using
     * {@link #layoutBuilder}. Override make* method to provide different presentation
     * for the XML element.
     */
    protected class Processor {
        /**
         * Processes contents of a definition. Takes children and tries to {@link #processElement}
         * them as instructions for placing controls.
         * @param def definition root.
         */
        public void processContent(Element def) {
            List<Element> elemList = def.getChildren();
            LOG.trace("componen-set starting with {} elements", elemList.size());
            for (int i = 0; i < elemList.size(); i++) {
                Element e = elemList.get(i);
                String name = e.getName();
                LOG.trace("component-set processing {} element", name);
                if (processElement(e) == UNKNOWN) {
                    LOG.warn("Unknown element name: {}, path: {} ", name, 
                            unknownItemPath(e));
                }
            }
        }
        
        private boolean lastItemNotEmpty = true;

        private boolean lastItemNotEmpty = true;

        /**
         * Processes one child element of the content. Attempts to {@link #interpretElement} 
         * as control or a container.
         * @param e configuration element
         * @return the created component, {@code null} or {@link #UNKNOWN}.
         */
        protected JComponent processElement(Element e) {
            // qualifiers will be processed by the parent. Skip.
            if (IGNORED_CONTENT.contains(e.getName())) {
                return null;
            }
            setElement(e);
            if (lastItemNotEmpty) {
                layoutBuilder.nextItem();
                layoutBuilder.configureItem(e);
            }
            lastItemNotEmpty = false;
            JComponent c = interpretElement();
            if (c == null || c == UNKNOWN) {
                return c;
            }
            lastItemNotEmpty = true;
            final JComponent fc = c;

            // PENDING: visual JComponent hierarchy follows the XML; if a child receives a qualifier "Q" from its parent XML
            // its parent JComponent will also receive that "upper" qualifier Q later, that causes the parent to become invisible
            // when Q's condition becomes false and recursively all the children become hidden. There's no need to go look up 
            // parent qualifiers and evaluate the conditions for children as well: adds unnecessary listeners.

            // handle qualification if any
            QualifierAdder qa = new QualifierAdder() {
                @Override
                protected Qualifier createQualifier(VariableValue var, String relation, String value) {
                    return new JComponentQualifier(fc, var, Integer.parseInt(value), relation);
                }

                @Override
                protected void addListener(java.beans.PropertyChangeListener qc) {
                    fc.addPropertyChangeListener(qc);
                }
            };
            qa.processModifierElements(e, _varModel);
            return c;
        }
        
        /**
         * Provides default behaviour for panel content items. Subclasses may override the method
         * to narrow the number of items allowed, or to provide different behaviour for XML elements.
         * If the method returns {@link #UNKNOWN}, unexpected element information will be logged. If
         * the component is not created for some reason (CV not present), the method should return {@code null}; 
         * additional error reporting is implementation-defined.
         * @return created JComponent instance, {@code null} pr {@link #UNKNOWN}.
         */
        protected JComponent interpretElement() {
            JComponent c;
            switch (e.getName()) {
                case ITEM_DISPLAY: 
                    c = makeVariable(e);
                    break;
                case ITEM_LABEL:
                    c = makeLabel(e);
                    break;
                case ITEM_SOUNDLABEL:
                    c = makeSoundLabel(e);
                    break;
                case ITEM_CVTABLW:
                    c = makeCVTable(e);
                    break;
                case ITEM_DCC_ADDRESS:
                    c = makeDccAddress(e);
                    break;
                case ITEM_FN_MAPPING:
                    c = makeFnMapping(e);
                    break;
                case ITEM_COLUMN:
                    c = makeColumn(e);
                    break;
                case ITEM_SEPARATOR:
                    c = makeSeparator(e);
                    break;
                case ITEM_ROW:
                    c = makeRow(e);
                    break;
                case ITEM_GRID:
                    c = makeGrid(e);
                    break;
                case ITEM_GROUP:
                    c = makeGroup(e);
                    break;
                default:
                    return UNKNOWN;
            }
            return c;
        }

        /**
         * Reads a XML attribute with fallback to a default value. If the
         * XML attribute is missing or is empty, the {@code defValue} will be
         * returned.
         * 
         * @param name attribute name
         * @param defValue default value for missing/empty attributes, may be {@code null}.
         * @return xml attribute string value or {@code defValue}
         */
        protected String readAttribute(String name, String defValue) {
            return readAttribute(e, name, defValue);
        }
        
        /**
         * Reads a XML attribute with fallback to a default value. If the
         * XML attribute is missing or is empty, the {@code defValue} will be
         * returned.
         * 
         * @param name attribute name
         * @param defValue default value for missing/empty attributes, may be {@code null}.
         * @param customE the element which attribute should be read
         * @return xml attribute string value or {@code defValue}
         */
        protected String readAttribute(Element customE, String name, String defValue) {
            Attribute attr = customE.getAttribute(name);
            if (attr == null) {
                return defValue;
            }
            String v = attr.getValue();
            if (v == null || v.isEmpty()) {
                return defValue;
            } else {
                return v;
            }
        }

        /**
         * Get a GUI representation of a particular variable for display.
         *
         * @param name Name used to look up the Variable object
         * @param var  XML Element which might contain a "format" attribute to be
         *             used in the {@link VariableValue#getNewRep} call from the
         *             Variable object; "tooltip" elements are also processed here.
         * @return JComponent representing this variable
         */
        private JComponent getRepresentation(String name, Element var) {
            int i = _varModel.findVarIndex(name);
            if (i < 0) {
                return null;
            }
            VariableValue variable = _varModel.getVariable(i);
            JComponent rep = null;
            String format = readAttribute(ATTR_FORMAT, DEFATTR_FORMAT);
            rep = getRep(i, format);
            // rep.setMaximumSize(rep.getPreferredSize());
            // set tooltip if specified here & not overridden by defn in Variable
            String tip;
            if (rep.getToolTipText() != null) {
                tip = rep.getToolTipText();
            } else {
                tip = LocaleSelector.getAttribute(var, ATTR_TOOLTIP);
            }
            rep.setToolTipText(modifyToolTipText(tip, variable));
            return rep;
        }

        /**
         * Takes default tool tip text, e.g. from the decoder element, and modifies
         * it as needed.
         * <p>
         * Intended to handle e.g. adding CV numbers to variables.
         *
         * @param start    existing tool tip text
         * @param variable the CV
         * @return new tool tip text
         */
        String modifyToolTipText(String start, VariableValue variable) {
            LOG.trace("modifyToolTipText: {}", variable.label());
            // this is the place to invoke VariableValue methods to (conditionally)
            // add information about CVs, etc in the ToolTip text

            // Optionally add CV numbers based on Roster Preferences setting
            start = CvUtil.addCvDescription(start, variable.getCvDescription(), variable.getMask());

            // Indicate what the command station can do
            // need to update this with e.g. the specific CV numbers
            /*
            FIXME: appended to all controls, is it good idea ?
            if (_cvModel.getProgrammer() != null
                    && !_cvModel.getProgrammer().getCanRead()) {
                start = StringUtil.concatTextHtmlAware(start, " (Hardware cannot read)");
            }
            if (_cvModel.getProgrammer() != null
                    && !_cvModel.getProgrammer().getCanWrite()) {
                start = StringUtil.concatTextHtmlAware(start, " (Hardware cannot write)");
            }
            */

            // indicate other reasons for read/write constraints
            if (variable.getReadOnly()) {
                start = StringUtil.concatTextHtmlAware(start, 
                        SymbolicProgBundle.getMessage("VariableReadOnly")); // NOI18N
            }
            if (variable.getWriteOnly()) {
                start = StringUtil.concatTextHtmlAware(start, 
                        SymbolicProgBundle.getMessage("VariableWriteOnly")); // NOI18N
            }

            return start;
        }

        private void addVarToList(String varName) {
            int iVar = _varModel.findVarIndex(varName);
            if (iVar >= 0) {
                varList.add(iVar);
            } else {
                LOG.debug("Could notfind " + varName);
            }
        }

        JComponent getRep(int i, String format) {
            return (JComponent) (_varModel.getRep(i, format));
        }

        protected JComponent makeSeparator(Element e) {
            JSeparator s = new JSeparator(javax.swing.SwingConstants.HORIZONTAL);
            layoutBuilder.addHorizontalSeparator(s);
            return s;
        }

        protected JComponent makeLabel(Element e) {
            String text = LocaleSelector.getAttribute(e, ATTR_TEXT);
            if (text == null || text.isEmpty()) {
                text = LocaleSelector.getAttribute(e, ATTR_LABEL); // label subelement not since 3.7.5
            }
            final JLabel l = new JLabel(text);
            l.setAlignmentX(1.0f);
            LOG.trace("Add label: {} ", l.getText());

            layoutBuilder.addFullSlotComponent(l);

            return l;
        }

        protected JComponent makeSoundLabel(Element e) {
            String labelText = rosterEntry.getSoundLabel(Integer.parseInt(LocaleSelector.getAttribute(e, ATTR_NUM))
            );
            final JLabel l = new JLabel(labelText);
            layoutBuilder.addFullSlotComponent(l);
            return l;
        }

        protected JComponent makeDccAddress(Element e) {
            JPanel l = new DccAddressPanel(_varModel);
            addVarToList(VAR_SHORT_ADDRESS);
            addVarToList(VAR_ADDRESS_FORMAT);
            addVarToList(VAR_LONG_ADDRESS);
            addVarToList(VAR_CONSIST_ADDRESS);
            panelList.add(l);
            if (l.getComponentCount() == 0) {
                return null;
            }
            layoutBuilder.addCellComponent(l);
            return l;
        }

        protected JComponent makeCVTable(Element e) {
            LOG.debug("starting to build CvTable pane");

            TableRowSorter<TableModel> sorter = new TableRowSorter<>(_cvModel);

            JTable cvTable = new JTable(_cvModel);

            sorter.setComparator(CvTableModel.NUMCOLUMN, new jmri.jmrit.symbolicprog.CVNameComparator());

            List<RowSorter.SortKey> sortKeys = new ArrayList<>();
            sortKeys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
            sorter.setSortKeys(sortKeys);

            cvTable.setRowSorter(sorter);

            cvTable.setDefaultRenderer(JTextField.class, new ValueRenderer());
            cvTable.setDefaultRenderer(JButton.class, new ValueRenderer());
            cvTable.setDefaultEditor(JTextField.class, new ValueEditor());
            cvTable.setDefaultEditor(JButton.class, new ValueEditor());
            cvTable.setRowHeight(new JButton("X").getPreferredSize().height);
            // have to shut off autoResizeMode to get horizontal scroll to work (JavaSwing p 541)
            // instead of forcing the columns to fill the frame (and only fill)
            cvTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            JScrollPane cvScroll = new JScrollPane(cvTable);
            cvScroll.setColumnHeaderView(cvTable.getTableHeader());

            // remember which CVs to read/write
            isCvTablePane = true;
            setCvListFromTable();

            LOG.debug("end of building CvTable pane");
            
            layoutBuilder.addFullSlotComponent(cvTable);
            return cvTable;
        }

        void setCvListFromTable() {
            // remember which CVs to read/write
            for (int j = 0; j < _cvModel.getRowCount(); j++) {
                cvList.add(j);
            }
            _varModel.setButtonModeFromProgrammer();
        }

        protected JComponent makeFnMapping(Element panelElem) {
            String extFnsESUval = readAttribute(modelElem, ATTR_EXT_FNS_ESU, null);
            JComponent n;
            if (extFnsESUval != null && !"no".equalsIgnoreCase(extFnsESUval)) {
                FnMapPanelESU l = new FnMapPanelESU(_varModel, varList, modelElem, rosterEntry, _cvModel);
                fnMapListESU.add(l); // remember for deletion
                n = l;
            } else {
                FnMapPanel l = new FnMapPanel(_varModel, varList, modelElem);
                fnMapList.add(l); // remember for deletion
                n = l;
            }
            layoutBuilder.addFullSlotComponent(n);
            return n;
        }

        /**
         * Processes contents of a collection of items like column or group. Uses the
         * passed {@link LayoutBuilder} to compose the contents, then returns the LayoutBuilder
         * product (from its {@link LayoutBuilder#build} method.
         * 
         * @param sectionBuilder builder for the section layout
         * @param e parent element, children will be processed as panel items.
         * @return JComponent representing the section
         */
        protected JComponent processSection(LayoutBuilder sectionBuilder, Element e) {
            LayoutBuilder save = layoutBuilder;
            try {
                layoutBuilder = sectionBuilder;
                lastItemNotEmpty = true;
                processContent(e);
            } finally {
                layoutBuilder = save;
            }
            JComponent result = sectionBuilder.build();
            if (result == null || result.getComponentCount() == 0) {
                return null;
            } else {
                return result;
            }
        }
        
        /**
         * Creates section with left-to-right layout.
         * @param e configuration element
         * @return JComponent representation
         */
        protected JComponent createRowSection(Element e) {
            return processSection(layoutBuilder.rowSection(), e);
        }
        
        /**
         * Creates section with top-to-down layout.
         * @param e configuration element
         * @return JComponent representation
         */
        protected JComponent createColumnSection(Element e) {
            return processSection(layoutBuilder.columnSection(), e);
        }
        
        /**
         * Creates a conditionally included group. The group may define include/exclude
         * for model/family of decoders; if the group is inapplicable, its contents is
         * skipped (method returns {@code null}).
         * <p/>
         * If included, the section has left-to-right layout (see {@link #createColumnSection}).
         * @param e configuration element
         * @return JComponent representation or {@code null}.
         */
        protected JComponent createGroupSection(Element e) {
            // handle include/exclude
            if (!PaneProgFrame.isIncludedFE(e, modelElem, rosterEntry, "", "")) {
                return null;
            }
            return processSection(layoutBuilder.columnSection(), e);
        }
        
        /**
         * Adds a component that occupies whole layout slot. 
         * @param c the component or {@code null}
         * @return the original value.
         */
        protected JComponent addSlotComponent(JComponent c) {
            if (c != null) {
                layoutBuilder.addFullSlotComponent(c);
            }
            return c;
        }

        /**
         * Processes contents and adds a row to the layout.
         * @param e configuration
         * @return JComponent representation.
         * @see  #createRowSection(org.jdom2.Element) 
         */
        protected JComponent makeRow(Element e) {
            return addSlotComponent(addPanel(createRowSection(e)));
        }

        /**
         * Processes contents and adds a column to the layout.
         * @param e configuration
         * @return JComponent representation.
         * @see  #createColumnSection(org.jdom2.Element) 
         */
        protected JComponent makeColumn(Element e) {
            return addSlotComponent(addPanel(createColumnSection(e)));
        }

        /**
         * Processes contents and adds a group to the layout.
         * @param e configuration
         * @return JComponent representation.
         * @see  #createGroupSection(org.jdom2.Element) 
         */
        protected JComponent makeGroup(Element e) {
            JComponent c = addPanel(createGroupSection(e));
            if (c != null) {
                layoutBuilder.addCellComponent(c);
            }
            return c;
        }
        
        /**
         * Processes contents and adds a grid to the layout. Individual items
         * of the grid must be a {@code griditem}s and contain cell coordinates; otherwise
         * the cells will be populated left-to-right top-to-bottom starting from the 
         * next free cell.
         * @param e configuration
         * @return JComponent representation.
         */
        protected JComponent makeGrid(Element e) {
            Processor level = new GridProcessor(e.getAttributes());
            JComponent c = level.processSection(layoutBuilder.gridSection(), e);
            if (c != null) {
                layoutBuilder.addFullSlotComponent(c);
            }
            return c;
        }

        /**
         * Creates and adds a CV variable to the layout. Creates a label for
         * the variable, and obtains a control from the variable model ({@link #_varModel}).
         * 
         * @param var configuration
         * @return JComponent representation
         */
        protected JComponent makeVariable(Element var) {
            // get the name
            String name = var.getAttributeValue(ATTR_ITEM);

            // if it doesn't exist, do nothing
            int i = _varModel.findVarIndex(name);
            if (i < 0) {
                LOG.trace("Variable \"{}\" not found, omitted", name);
                return null;
            }
    //        Leave here for now. Need to track pre-existing corner-case issue
    //        log.info("Entry item="+name+";cs.gridx="+cs.gridx+";cs.gridy="+cs.gridy+";cs.anchor="+cs.anchor+";cs.ipadx="+cs.ipadx);
            // check label orientation

            // load label if specified, else use name
            String label = LocaleSelector.getAttribute(var, ATTR_LABEL);
            if (label == null) {
                if (showStdName) {
                    label = name;
                } else {
                    // get name attribute from variable, as that's the mfg name
                    label = _varModel.getLabel(i);
                }
            }
            // get representation; store into the list to be programmed
            JComponent rep = getRepresentation(name, var);
            varList.add(i);
            
            // Consistency fix: if the text does not end with ':'
            // and the layout precedes (is above or left to) the control, add a colon
            LayoutBuilder.RelativePos relPos = LayoutBuilder.RelativePos.fromString(readAttribute(ATTR_LAYOUT, DEFATTR_LAYOUT));
            
            // PEDNING: I18n; the doublecolon should not be probably hardcoded.
            if (!label.endsWith(":") && !label.trim().isEmpty()) {
                switch (relPos) {
                    case LEFT: case ABOVE:
                        label = label.trim() + ":";
                        break;
                }
            }
            label = label.replace('_', '&'); 
            // create the paired label
            JLabel l = new WatchingLabel(label, rep);
            
            // PENIDNG: perhaps collect labels from the entire dialog scope, and then select 
            // some unique ones provided there are more alternatives in the text.
            Mnemonics.setLocalizedText(l, label);
            layoutBuilder.addLabelControl(l, rep, relPos);

            return rep;
        }
    }
    
    /**
     * Processed root of the tab panel, which is boxed-layout. The number of
     * recognized items is limited, but unknown elements are ignored, e.g. there are
     * &lt;name xml:lang=...&gt; elements.
     */
    protected class Root extends Processor {
        private Processor delegate = defaultProcessor();
        
        @Override
        protected JComponent interpretElement() {
            JComponent c;
            switch (e.getName()) {
                case ITEM_COLUMN:
                case ITEM_ROW:
                case ITEM_GRID:
                case ITEM_GROUP:
                    return delegate.interpretElement();
                default:
                    // root of panel contains name xml:lang elements
                    return null;
            }
        }
    }
    
    protected class GridProcessor extends Processor {
        private Processor delegate = defaultProcessor();

        private int gridxCurrent = -1;
        private int gridyCurrent = -1;
        private final List<Attribute> gridAttList;
        protected GridBagConstraints gridConstraints;

        public GridProcessor(List<Attribute> gridAttributes) {
            this.gridAttList = gridAttributes;
        }

        /** Special processing for groups, which wraps griditems.
         * 
         * @param e
         * @return 
         */
        protected JComponent createGroupSection(Element e) {
            // handle include/exclude
            if (!PaneProgFrame.isIncludedFE(e, modelElem, rosterEntry, "", "")) {
                return null;
            }
            // process with our own builder, so items will end up
            // in the same grid. Can't call processSection, as that
            // would terminate our builder with build().
            processContent(e);
            return null;
        }
        
        /**
         * Constrain recognized elements to just griditem, group.
         */
        @Override
        protected JComponent interpretElement() {
            JComponent c;
            gridConstraints = new GridBagConstraints();
            switch (e.getName()) {
                case ITEM_GRIDITEM:
                    return makeGridItem(e);
                case ITEM_GROUP:
                    return makeGroup(e);
                default:
                    return UNKNOWN;
            }
        }

        protected JComponent makeGridItem(Element e) {
            parseGridConstraints(e);
            JComponent c = delegate.processSection(layoutBuilder.rowSection(), e);
            if (c != null) {
                layoutBuilder.addGridCell(c, gridConstraints);
            }
            return c;
        }

        private String gridXLast;
        private String gridYLast;

        /**
         * Create a grid item from the JDOM Element
         *
         * @param element     element containing grid item contents
         */
        public void parseGridConstraints(Element element) {

            List<Attribute> itemAttList = element.getAttributes(); // get item-level attributes
            List<Attribute> attList = new ArrayList<>(gridAttList);
            attList.addAll(itemAttList); // merge grid and item-level attributes
            // gridx and gridy are buffered, and the last seen value will be used afterwards.
            
            gridXLast = gridYLast = null;
            
            for (int j = 0; j < attList.size(); j++) {
                Attribute attrib = attList.get(j);
                String attribName = attrib.getName();
                String attribRawValue = attrib.getValue();
                processWithExceptions(attribName, attribRawValue, false);
            }
            processWithExceptions("gridx", gridXLast, true);
            processWithExceptions("gridy", gridYLast, true);
        }
        
        private void processWithExceptions(String attribName, String attribRawValue, boolean d) {
            try {
                if (d) {
                    processGridAttr0(attribName, attribRawValue);
                } else {
                    processGridAttr(attribName, attribRawValue);   
                }
            } catch (NoSuchFieldException ex) {
                LOG.error("Unrecognized attribute \"" + attribName + "\", skipping");
            } catch (SecurityException ex) {
                LOG.error("Inaccessible attribute \"" + attribName + "\", skipping");
            } catch (ReflectiveOperationException ex) {
                LOG.error("Unable to set constraint \"" + attribName, ex);
            } catch (NumberFormatException ex) {
                LOG.error("Invalid value \"" + attribRawValue + "\" for attribute \"" + attribName + "\"");
            }
        }

        /**
         * Process one attribute for the grid constraints.
         *
         * @param attribName     attribute name
         * @param attribRawValue attribute value
         * @throws ReflectiveOperationException
         */
        private void processGridAttr(String attribName, String attribRawValue) throws ReflectiveOperationException {
            switch (attribName) {
                case "gridx":
                    gridXLast = attribRawValue;
                    break;
                case "gridy":
                    gridYLast = attribRawValue;
                    break;
                default:
                    processGridAttr0(attribName, attribRawValue);
            }
        }
        
        private void processGridAttr0(String attribName, String attribRawValue) throws ReflectiveOperationException {
            if (attribRawValue == null) {
                return;
            }
            Object value = null;
            
            Field f = GridBagConstraints.class.getField(attribName);
            f.setAccessible(true);
            switch (attribName.toLowerCase()) {
                case "gridx":
                    value = getSpecialValue(attribRawValue, new GridSpecialValues() {
                        @Override
                        public int current() {
                            return gridxCurrent;
                        }

                        @Override
                        public int next() {
                            return ++gridxCurrent;
                        }
                    });
                    if (value instanceof Integer) {
                        gridxCurrent = (Integer)value;
                    }
                    break;
                case "gridy":
                    value = getSpecialValue(attribRawValue, new GridSpecialValues() {
                        @Override
                        public int current() {
                            return gridyCurrent;
                        }

                        @Override
                        public int next() {
                            return ++gridyCurrent;
                        }
                    });
                    if (value instanceof Integer) {
                        gridyCurrent = (Integer)value;
                    }
                    break;
                default:
                    value = parseValue(f.getType(), attribName, attribRawValue);
                    break;
            }
            if (value != null) {
                f.set(gridConstraints, value);
            }
        }

        private Object parseValue(Class pt, String attribName, String attribRawValue) {
            Object value;
            if (pt == Integer.TYPE) {
                try {
                    return Integer.parseInt(attribRawValue);
                } catch (NumberFormatException ex) {
                    try {
                        Field constant = GridBagConstraints.class.getDeclaredField(attribRawValue);
                        constant.setAccessible(true);
                        return (Integer) GridBagConstraints.class.getField(attribRawValue).get(constant);
                    } catch (NoSuchFieldException ey) {
                        LOG.error("Invalid value \"" + attribRawValue + "\" for attribute \"" + attribName + "\"");
                    } catch (IllegalAccessException ey) {
                        LOG.error("Unable to set constraint \"" + attribName + ". IllegalAccessException error thrown.");
                    }
                }
                return null;
            } else if (pt == Double.TYPE) {
                value = Double.parseDouble(attribRawValue);
            } else if (pt == Insets.class) {
                String[] insetStrings = attribRawValue.split(",");
                if (insetStrings.length == 4) {
                    value = new Insets(
                            Integer.parseInt(insetStrings[0]),
                            Integer.parseInt(insetStrings[1]),
                            Integer.parseInt(insetStrings[2]),
                            Integer.parseInt(insetStrings[3])
                    );
                } else {
                    LOG.error("Invalid value \"" + attribRawValue + "\" for attribute \"" + attribName + "\"");
                    LOG.error("Value should be four integers of the form \"top,left,bottom,right\"");
                    return null;
                }
            } else {
                LOG.error("Required \"" + pt.getName() + "\" handler for attribute \"" + attribName + "\" not defined in JMRI code");
                LOG.error("Please file a JMRI bug report at https://sourceforge.net/p/jmri/bugs/new/");
                return null;
            }
            return value;
        }

        private Integer getSpecialValue(String v, GridSpecialValues vals) {
            switch (v.trim().toUpperCase(Locale.ENGLISH)) {
                case "NEXT":
                case "RELATIVE":
                    return vals.next();
                case "CURRENT":
                    return vals.current();
                case "":
                    // Discards any previously set value.
                    return null;
                default:
                    return Integer.parseInt(v);
            }
        }
    }

    /**
     * Callback interface that provides values for special
     * constants in gridx/y of grid items. Each axis is represented by its
     * own GridSpecialValues instance.
     */
    protected interface GridSpecialValues {
        /**
         * Returns the current value. Will not change the position.
         * @return current position in the axis.
         */
        public int current();
        /**
         * Increments the current axis position and returns the resulting value.
         * @return New value of the position along the axis.
         */
        public int next();
    }

    public String toString() {
        return getClass().getSimpleName();
    }
}
