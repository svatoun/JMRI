/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.event.AncestorListener;
import javax.swing.event.ListDataListener;

/**
 *
 * @author sdedic
 */
public class ComboWithDescription<E> extends JComboBox<E> {
    /**
     * The original value of tooltip, set from outside.
     */
    private String originalTooltip;
    
    /**
     * Combines the default tooltip, and a tooltip for
     * the currently selected item, if defines a tooltip.
     */
    private String combinedTooltipText;
    
    private DescriptiveComboModel<E>  descriptionModel = NULL_DESCRIPTIONS;

    @Override
    public String getToolTipText() {
        return super.getToolTipText();
    }

    @Override
    public void setModel(ComboBoxModel<E> aModel) {
        if (aModel instanceof DescriptiveComboModel) {
            this.descriptionModel = (DescriptiveComboModel<E>)aModel;
        }
        super.setModel(aModel);
    }

    @Override
    public void setToolTipText(String text) {
        this.originalTooltip = text;
        super.setToolTipText(text);
    }
    
    private static final DescriptiveComboModel NULL_DESCRIPTIONS = new DescriptiveComboModel<Object>() {
        @Override
        public void addDescription(Object item, String description) {
        }

        @Override
        public void removeDescription(Object item) {
        }

        @Override
        public String getDescription(Object item) {
            return null;
        }
    };
}
