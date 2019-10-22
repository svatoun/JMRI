package jmri.jmrit.symbolicprog.tabbedframe;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Iterator;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.border.EmptyBorder;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a layout based on GridBagLayout. This is the original DecoderPro layout scheme.
 * The {@link ControlXMLReader} delegates all additions to the layout here.
 * 
 * @author sdedic
 */
public abstract class GridBagLayoutBuilder implements LayoutBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(GridBagLayoutBuilder.class);
    
    /**
     * Tags components as JMRI panels. Used as a {@link JComponent#getClientProperty} key.
     */
    private static final String TAG_JMRI_PANEL = "jmri.layout.panel"; // NOI18N
    
    /**
     * Value that identifies JComponent as "column" panel. See {@link #TAG_JMRI_PANE}
     */
    private static final String PANEL_COLUMN = "column"; // NOI18N

    /**
     * The nesting level of this builder. This is roughtly the nesting
     * level of {@code row, column, grid, group} elements.
     */
    protected final int level;

    /**
     * Parent builder. Can be used to inspect the hierarchy.
     */
    protected final GridBagLayoutBuilder    parent;
    
    /**
     * The JPanel which is being produced. Each row, column, group or grid item
     * has its own panel, each pane uses GridBagLayout.
     */
    protected final JPanel  panel;
    
    /**
     * Width of the space character, with the current font. Lazily initialized
     * and cached.
     */
    private int spaceWidth;
    
    /**
     * Indent according to the nesting depth, for debugging messages.
     */
    private String indent;

    /**
     * GridBagConstraints for the current panel item. It is initialized by {@link #nextItem()},
     * subclasses can customize in {@link #prepareNextItem(java.awt.GridBagConstraints)}.
     * <o/>
     * Do not assign new values!
     */
    protected GridBagConstraints cs;
    
    /**
     * Creates a new level of builder
     * @param panel the constructed panel
     * @param level nesting level
     */
    private GridBagLayoutBuilder(GridBagLayoutBuilder parent, JPanel panel, int level) {
        this.parent = parent;
        this.panel = panel;
        this.level = level;
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} Starting {}:{} ", indent(), level, name());
        }
    }

    @Override
    public void configureItem(Element e) {
        // no op
    }
    
    /**
     * Access to the parent builder chain
     * @return enumerates parents, the immediate parent first.
     */
    Iterable<GridBagLayoutBuilder> parents() {
        class It implements Iterator<GridBagLayoutBuilder> {
            private GridBagLayoutBuilder item = parent;
            @Override
            public boolean hasNext() {
                return item != null;
            }

            @Override
            public GridBagLayoutBuilder next() {
                GridBagLayoutBuilder result = item;
                item = result.parent;
                return result;
            }
        };
        return new Iterable<GridBagLayoutBuilder>() {
            @Override
            public Iterator<GridBagLayoutBuilder> iterator() {
                return new It();
            }
            
        };
    }
    
    protected String indent() {
        if (indent != null) {
            return indent;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return indent = sb.toString();
    }
    
    protected int spaceWidth(JLabel l) {
        if (spaceWidth == 0) {
            spaceWidth  = panel.getFontMetrics(l.getFont()).stringWidth(" ");
        }
        return spaceWidth;
    }

    protected int controlColumnGapWidth() {
        return panel.getFontMetrics(panel.getFont()).stringWidth(CONTROL_GAP_Ms);
    }
    
    /**
     * Represents gap between two main control columns. Since the gap width is measured through
     * font metrics, this should be a String of wide characters such as Ms.
     */
    private static final String CONTROL_GAP_Ms = "MMMMMMMMMM";
        
    protected String name() {
        return getClass().getSimpleName();
    }
    
    /**
     * Replacement for {@code toString} for {@link GridBagConstraints}.
     * @return string representation of important GBC properties, for debugging.
     */
    protected String gbcs() {
        StringBuilder sb = new StringBuilder();
        sb.append("[x = ").append(cs.gridx);
        sb.append(", y = ").append(cs.gridy);
        sb.append(", w = ").append(cs.gridwidth);
        sb.append(", h = ").append(cs.gridheight);
        sb.append(", f = ").append(cs.fill);
        sb.append(", a = ").append(cs.anchor);
        sb.append("]");
        return sb.toString();
    }
    
    @Override
    public void addLabelControl(JLabel l, JComponent rep, RelativePos labelPos) {
        cs.insets = createNextFlowInsets();
        switch (labelPos) {
            case LEFT:
                cs.anchor = GridBagConstraints.BASELINE_LEADING;
                cs.fill = GridBagConstraints.HORIZONTAL;
                panel.add(l, cs);
                cs.gridx++;
                
                // ESU bugfix: if a component does not have a baseline, align
                // it northwest, otherwise it would be centered (and misaligned)
                if (rep.getBaseline(0, 0) > 0) {
                    cs.anchor = GridBagConstraints.BASELINE_TRAILING;
                } else {
                    cs.anchor = GridBagConstraints.NORTHEAST;
                }
                panel.add(rep, cs);
                break;

            case RIGHT:
                cs.anchor = GridBagConstraints.EAST;
                panel.add(rep, cs);
                cs.gridx++;
                cs.anchor = GridBagConstraints.WEST;
                cs.ipadx = spaceWidth(l);
                panel.add(l, cs);
                break;

            case BELOW:
                // variable in center of upper line
                cs.anchor = GridBagConstraints.WEST;
                panel.add(rep, cs);
                // label aligned like others
                cs.gridy++;
                cs.anchor = GridBagConstraints.WEST;
                cs.ipadx = spaceWidth(l);
                panel.add(l, cs);
                break;
            case ABOVE:
                // label aligned like others
                cs.anchor = GridBagConstraints.WEST;
                cs.ipadx = spaceWidth(l);
                panel.add(l, cs);
                cs.ipadx = 0;
                // variable in center of lower line
                cs.gridy++;
                cs.anchor = GridBagConstraints.WEST;
                panel.add(rep, cs);
                break;
        } 
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} added label {}, control {}, rel={}, gbc={}", l.getText(), rep, labelPos, gbcs());
        }
    }

    @Override
    public void nextItem() {
        GridBagConstraints old = cs;
        GridBagConstraints n = new GridBagConstraints();
        if (old != null) {
            n.gridx = old.gridx;
            n.gridy = old.gridy;
        }
        cs = prepareNextItem(n);
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} advance, gbc={}", indent(), gbcs());
        }
    }
    
    @Override
    public void addVerticalComponent(JComponent c, float weight) {
        cs.fill = GridBagConstraints.VERTICAL;
        cs.weighty = weight;
        panel.add(c, cs);
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} adding vertical {}, weight={}, gbc={}", indent(), c, weight, gbcs());
        }
    }
    
    private JPanel createGBPanel() {
        JPanel p = new JPanel();
        p.setLayout(new GridBagLayout());
        return p;
    }
    
    protected Insets createNextFlowInsets() {
        return new Insets(0, 0, 6, 11);
    }

    @Override
    public LayoutBuilder rowSection() {
        return new Row(this, createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder columnSection() {
        return new Column(this, createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder gridSection() {
        return new Grid(this, createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder groupSection() {
        return new Row(this, createGBPanel(), level + 1);
    }

    @Override
    public void addCellComponent(JComponent component) {
        if (component.getParent() != null) {
            return;
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} adding cell {}, gbc={}", indent(), component, gbcs());
        }
        if (cs.gridx % 2 == 0) {
            cs.gridx++;
        }
        cs.fill = GridBagConstraints.HORIZONTAL;
        cs.insets = createNextFlowInsets();
        panel.add(component, cs);
    }

    @Override
    public JPanel build() {
        // add glue to the bottom to allow resize
        if (panel.getComponentCount() > 0) {
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} finishing: {}:{}", indent(), level, name());
        }
        return panel;
    }

    @Override
    public void addGridCell(JComponent component, GridBagConstraints constraints) {
        throw new UnsupportedOperationException("Invalid operation");
    }
    
    protected GridBagConstraints prepareNextItem(GridBagConstraints cs) {
        if (cs.gridx == 0 & cs.gridy == 0) {
            int a = cs.anchor;
            
            switch (cs.anchor) {
                case GridBagConstraints.CENTER:
                case GridBagConstraints.WEST:  a = GridBagConstraints.NORTHWEST; break;
                case GridBagConstraints.EAST:  a = GridBagConstraints.NORTHEAST; break;
            }
            cs.anchor = a;
        }
        return cs;
    }
    
    /**
     * Generates special layout for main columns.
     * The 1st level column (usually) represents one column of controls in the dialog.
     * Certain panes have two-column layout. The columns must be visually clearly discrete
     * so there must be some gap between them.
     * <p/>
     * This function generates an inset to the 2nd and following columns on the 1st level. for
     * other components returns the {@link GridBagConstraints} unchanged.
     * @param c component
     * @param cs the constraints
     * @return modified constraints
     */
    protected GridBagConstraints adjustMainColumnWidth(JComponent c, GridBagConstraints cs) {
        if (!isColumnComponent(c)) {
            return cs;
        }
        for (GridBagLayoutBuilder lb : parents()) {
            if (lb instanceof GridBagLayoutBuilder) {
                return cs;
            }
        }
        if (cs.gridx > 0) {
            cs.insets.left += controlColumnGapWidth();
        }
        return cs;
    }
    
    /**
     * Builds a panel, where items are placed left-to-right. Each non-display
     * element occupies the entire height of the row.
     */
    static class Row extends GridBagLayoutBuilder {

        public Row(GridBagLayoutBuilder parent, JPanel panel, int level) {
            super(parent, panel, level);
        }
        
        /**
         * Advance the X position in the grid
         * @param cs constraints
         * @return modified instance
         */
        @Override
        protected GridBagConstraints prepareNextItem(GridBagConstraints cs) {
            cs.gridy = 0;
            cs.gridx++;
            cs = super.prepareNextItem(cs);
            return cs;
        }
        
        @Override
        public void addHorizontalSeparator(JSeparator sep) {
            sep.setOrientation(JSeparator.VERTICAL);
            cs.fill = GridBagConstraints.BOTH;
            cs.gridheight = GridBagConstraints.REMAINDER;
            panel.add(sep, cs);
            if (LOG.isTraceEnabled()) {
                LOG.trace("{} added vert separator {}, gbc={}", indent(), sep, gbcs());
            }
        }

        @Override
        public void addFullSlotComponent(JComponent c) {
            cs.gridheight = GridBagConstraints.REMAINDER;
            panel.add(c, cs);
            if (LOG.isTraceEnabled()) {
                LOG.trace("{} added vert component {}, gbc={}", indent(), c, gbcs());
            }
        }
    }
    
    /**
     * Creates a panel where components are placed top to bottom.
     */
    static class Column extends GridBagLayoutBuilder {
        
        public Column(GridBagLayoutBuilder parent, JPanel panel, int level) {
            super(parent, panel, level);
            panel.putClientProperty("jmri.layout.panel", "column");
        }

        @Override
        protected GridBagConstraints prepareNextItem(GridBagConstraints cs) {
            cs.gridx = 0;
            cs.gridy++;
            cs = super.prepareNextItem(cs);
            return cs;
        }
        
        @Override
        public void addFullSlotComponent(JComponent c) {
            cs.anchor = GridBagConstraints.NORTHWEST;
            cs.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(c, adjustMainColumnWidth(c, cs));
            if (LOG.isTraceEnabled()) {            
                LOG.trace("{} added horiz component {}, gbc={}", indent(), c, gbcs());
            }
        }
        
        @Override
        public void addHorizontalSeparator(JSeparator sep) {
            cs.fill = GridBagConstraints.BOTH;
            cs.anchor = GridBagConstraints.WEST;
            cs.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(sep, cs);
            if (LOG.isTraceEnabled()) {
                LOG.trace("{} added horiz separator {}, gbc={}", indent(), sep, gbcs());
            }
        }

        @Override
        public JPanel build() {
            JPanel p = super.build(); 
            // advance to the next row
            nextItem();
            cs.fill = GridBagConstraints.BOTH;
            cs.weighty = 0.5;
            p.add(new JPanel(), cs);
            return p;
        }
        
        
    }
        
    /**
     * Creates a panel, which does NOT support adding left to right or top to bottom,
     * but to the individual cells of a grid.
     */
    static class Grid extends GridBagLayoutBuilder {

        public Grid(GridBagLayoutBuilder parent, JPanel panel, int level) {
            super(parent, panel, level);
        }

        @Override
        public void addHorizontalSeparator(JSeparator sep) {
            throw new UnsupportedOperationException("Invalid grid operation"); 
        }

        @Override
        public void addFullSlotComponent(JComponent c) {
            throw new UnsupportedOperationException("Invalid grid operation"); 
        }

        @Override
        protected GridBagConstraints prepareNextItem(GridBagConstraints cs) {
            return super.prepareNextItem(new GridBagConstraints());
        }

        @Override
        public void addGridCell(JComponent component, GridBagConstraints constraints) {
            panel.add(component, constraints);
        }
    }
    
    /**
     * The main tabbed panel builder; adds to a BoxLayout without any specific constraints.
     */
    public static class RootBuilder extends GridBagLayoutBuilder {
        public RootBuilder(JPanel panel) {
            super(null, panel, 1);
            panel.setLayout(new GridBagLayout());
            panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        }

        @Override
        public JPanel build() {
            LOG.debug("RootBuilder finished");
            nextItem();
            GridBagConstraints spacerX = cs;
            cs.gridy = 0;
            cs.gridheight = GridBagConstraints.REMAINDER;
            cs.fill = GridBagConstraints.BOTH;
            cs.weightx = 0.1;
            panel.add(new JPanel(), cs);
            
            nextItem();
            cs.gridy = Math.max(0, cs.gridy) + 1;
            cs.gridx = 0;
            cs.gridwidth = GridBagConstraints.REMAINDER;
            cs.gridheight = GridBagConstraints.REMAINDER;
            cs.fill = GridBagConstraints.BOTH;
            cs.weighty = 0.1;
            panel.add(new JPanel(), cs);
            return panel;
        }

        @Override
        protected GridBagConstraints prepareNextItem(GridBagConstraints cs) {
            cs.anchor = GridBagConstraints.NORTHWEST;
            cs.gridx++;
            cs.fill = GridBagConstraints.VERTICAL;
            return cs;
        }

        @Override
        public void addHorizontalSeparator(JSeparator sep) {
            LOG.debug("Adding separator {} to root", sep);
            panel.add(sep, cs);
        }

        @Override
        public void addCellComponent(JComponent component) {
            LOG.debug("Adding cell {} to root", component);
            panel.add(component, cs);
        }
        
        @Override
        public void addFullSlotComponent(JComponent c) {
            LOG.debug("Adding row {} to root", c);
            if (isColumnComponent(c)) {
                if (cs.gridx > 0) {
                    c.setBorder(new EmptyBorder(0, 12, 0, 0));
                }
            }
            panel.add(c, adjustMainColumnWidth(c, cs));
        }

        @Override
        public void addVerticalComponent(JComponent c, float weight) {
            LOG.debug("Adding vertical {} to root", c);
            panel.add(c, cs);
        }

        @Override
        public void addLabelControl(JLabel l, JComponent rep, RelativePos labelPos) {
            throw new IllegalArgumentException("Invalid operation");
        }
    }
    
    private static boolean isColumnComponent(JComponent c) {
        Object panelHint = c.getClientProperty(TAG_JMRI_PANEL); // NOI18N
        return PANEL_COLUMN.equals(panelHint);
    }
}
