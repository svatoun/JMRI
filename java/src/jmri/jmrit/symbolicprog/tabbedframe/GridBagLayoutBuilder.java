package jmri.jmrit.symbolicprog.tabbedframe;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
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
     * The nesting level of this builder. This is roughtly the nesting
     * level of {@code row, column, grid, group} elements.
     */
    protected final int level;
    
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
    private GridBagLayoutBuilder(JPanel panel, int level) {
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
        switch (labelPos) {
            case LEFT:
                cs.anchor = GridBagConstraints.EAST;
                cs.ipadx = spaceWidth(l);
                panel.add(l, cs);
                cs.ipadx = 0;
                cs.gridx++;
                cs.anchor = GridBagConstraints.WEST;
                panel.add(rep, cs);
                break;
//        log.info("Exit item="+name+";cs.gridx="+cs.gridx+";cs.gridy="+cs.gridy+";cs.anchor="+cs.anchor+";cs.ipadx="+cs.ipadx);
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
                cs.anchor = GridBagConstraints.CENTER;
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
                cs.anchor = GridBagConstraints.CENTER;
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

    @Override
    public LayoutBuilder rowSection() {
        return new Row(createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder columnSection() {
        return new Column(createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder gridSection() {
        return new Grid(createGBPanel(), level + 1);
    }

    @Override
    public LayoutBuilder groupSection() {
        return new Row(createGBPanel(), level + 1);
    }

    @Override
    public void addCellComponent(JComponent component) {
        if (component.getParent() != null) {
            return;
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("{} adding cell {}, gbc={}", indent(), component, gbcs());
        }
        panel.add(component, cs);
    }

    @Override
    public JPanel build() {
        // add glue to the bottom to allow resize
        if (panel.getComponentCount() > 0) {
            panel.add(Box.createVerticalGlue());
            LOG.trace("{} glue added", indent());
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
     * Builds a panel, where items are placed left-to-right. Each non-display
     * element occupies the entire height of the row.
     */
    static class Row extends GridBagLayoutBuilder {

        public Row(JPanel panel, int level) {
            super(panel, level);
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

        public Column(JPanel panel, int level) {
            super(panel, level);
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
            cs.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(c, cs);
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
    }
    
    /**
     * Creates a panel, which does NOT support adding left to right or top to bottom,
     * but to the individual cells of a grid.
     */
    static class Grid extends GridBagLayoutBuilder {

        public Grid(JPanel panel, int level) {
            super(panel, level);
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
            super(panel, 1);
            panel.setLayout(new GridBagLayout());
        }

        @Override
        public JPanel build() {
            LOG.debug("RootBuilder finished");
            nextItem();
            GridBagConstraints spacerX = cs;
            cs.gridy = 0;
            cs.gridheight = GridBagConstraints.REMAINDER;
            cs.fill = GridBagConstraints.BOTH;
            cs.weightx = 1.0;
            panel.add(new JPanel(), cs);
            
            nextItem();
            cs.gridy = Math.max(0, cs.gridy) + 1;
            cs.gridx = 0;
            cs.gridwidth = GridBagConstraints.REMAINDER;
            cs.gridheight = GridBagConstraints.REMAINDER;
            cs.fill = GridBagConstraints.BOTH;
            cs.weighty = 1.0;
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
            panel.add(c, cs);
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
}
