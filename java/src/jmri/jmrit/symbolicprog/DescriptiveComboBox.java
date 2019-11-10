/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import java.util.Objects;
import javax.swing.ComboBoxModel;
import javax.swing.Icon;
import javax.swing.JComboBox;

/**
 *
 * @author sdedic
 */
public class DescriptiveComboBox<E> extends JComboBox<E> {
    /**
     * The original value of tooltip, set from outside.
     */
    private String originalTooltip;
    
    /**
     * Combines the default tooltip, and a tooltip for
     * the currently selected item, if defines a tooltip.
     */
    private String combinedTooltipText;
    
    private ValueAccessor<E, E>  accessor = NULL_DESCRIPTIONS;

    @Override
    public String getToolTipText() {
        return super.getToolTipText();
    }

    @Override
    public void setModel(ComboBoxModel<E> aModel) {
        if (aModel instanceof ValueAccessor) {
            this.accessor = (ValueAccessor<E, E>)aModel;
        }
        super.setModel(aModel);
    }

    @Override
    public void setToolTipText(String text) {
        this.originalTooltip = text;
        super.setToolTipText(text);
    }
    
    private static final ValueAccessor NULL_DESCRIPTIONS = new ValueAccessor<Object, Object>() {
        @Override
        public boolean accept(Object item) {
            return true;
        }
        
        @Override
        public String getDescription(Object item) {
            return null;
        }

        @Override
        public Icon getIcon(Object item) {
            return null;
        }

        @Override
        public Object getValue(Object item) {
            return item;
        }

        @Override
        public String getDisplayName(Object item) {
            return Objects.toString(item);
        }
    };
}
