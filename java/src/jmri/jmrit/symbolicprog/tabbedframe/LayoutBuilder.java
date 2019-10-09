/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.tabbedframe;

import java.awt.GridBagConstraints;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSeparator;
import org.jdom2.Element;

/**
 * This interface abstracts placing individual components into the layout. Each LayoutBuilder represents 
 * one level of {@code column, row, grid, oup}. After construction, the {@code LayoutBuilder} accepts components,
 * and its life is finalized by {@link #build} call that produces the component, typically the containing panel.
 * <p/>
 * The layout contains "slots" where e.g. label-control pair may reside; a slot then contains (conceptually) a "cell"
 * for label and another for control. Layout of these cells within a slot is up to the builder implementation.
 * As a special case, grids are directly supported, allowing to place content to a specific cell 
 * according to {@link GridBagConstraints}.
 * <p/>
 * Construction must be finished by {@link #build}, which yields an initialized JComponent instance.
 * <p/>
 * Note that if the components on the level are not placed into a separate parent panel, they must be collectively
 * represented by some JComponent instance - either one of the created ones, or a special (even not part of the
 * component Tree). That representative must distribute {@link JComponent#setVisible} to all components on the
 * layout, so they properly hide and show.
 * 
 * @author sdedic
 */
public interface LayoutBuilder {
    
    /**
     * Relative position of a label with respect to its associated control.
     */
    enum RelativePos {
        LEFT, RIGHT, ABOVE, BELOW;
        
        static RelativePos fromString(String s) {
            if (s == null) {
                return LEFT;
            }
            try {
                return valueOf(s.toUpperCase());
            } catch (IllegalArgumentException ex) {
                // just ignore and return the default
                return LEFT;
            }
        }
    };
    
    
    /**
     * Advances to the next item. The method should be called at the start of component processing, can
     * be called before the very first component.
     * 
     */
    public void nextItem();
    
    /**
     * Configures possible additional info for the next element. This information
     * applies only to the immedate next add* call; the information will be reset
     * by a call to {@link #nextItem()}.
     * <p/>
     * Note: this method is currently unused, but allows for layout-specific extensions
     * in the XML format.
     * @param e XML configuration element
     */
    public void configureItem(Element e);
    
    /**
     * Adds label-control pair to the panel. The labelPos determines the relative
     * position of the label and the associated control.
     * <p/>
     * @param l the label associated with the control
     * @param control the control 
     * @param labelPos relative position of the label
     */
    public void addLabelControl(JLabel l, JComponent control, RelativePos labelPos);
    
    /**
     * Adds a separator. The separator spans horizontally across the whole contents
     * of the current group.
     * @param sep the separator instance
     */
    public void addHorizontalSeparator(JSeparator sep);
    
    /**
     * Adds an opaque component to the group. The component occupies the entire
     * display slot horizontally (for columns) or vertically (for rows).
     * 
     * @param c component to add
     */
    public void addFullSlotComponent(JComponent c);
    
    /**
     * Adds a vertical component, with defined weight to consume the
     * available space. The component stretches vertically in both columns and rows.
     * 
     * @param c component to add
     * @param weight weight
     */
    public void addVerticalComponent(JComponent c, float weight);
    
    /**
     * Adds a component as a grid cell. Applicable only for grid sections, other
     * builders may throw an exception.
     * @param component
     * @param constraints 
     */
    public void addGridCell(JComponent component, GridBagConstraints constraints);
    
    /**
     * Adds a component that occupies a single slot in the layout.
     * @param component component to add.
     */
    public void addCellComponent(JComponent component);
    
    /**
     * Creates a horizontally separated section in the current group.
     * @return builder instance for section contents
     */
    public LayoutBuilder rowSection();

    /**
     * Creates a vertically separated section in the current group.
     * @return builder instance for section contents
     */
    public LayoutBuilder columnSection();

    /**
     * Creates a grid-like panel.
     * @return 
     */
    public LayoutBuilder gridSection();
    
    /**
     * Creates a group of components, which can be visible/invisible all at time
     * @return 
     */
    public LayoutBuilder groupSection();

    /**
     * Finishes the LayoutBuilder and returns the representative component or 
     * parent (usually panel).
     * @return panel component instance 
     */
    public JComponent build();
}
