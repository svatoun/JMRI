/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import java.awt.Component;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 *
 * @author sdedic
 */
public class DescriptiveComboRenderer<E, V> implements ListCellRenderer {
    private final ListCellRenderer delegate;
    private final ValueAccessor valueAccessor;
    private Supplier<String> componentTooltipProvider;
    
    public DescriptiveComboRenderer(ListCellRenderer delegate, ValueAccessor accessor) {
        this.delegate = delegate;
        this.valueAccessor = accessor;
    }

    public DescriptiveComboRenderer setComponentTooltipProvider(Supplier<String> componentTooltipProvider) {
        this.componentTooltipProvider = componentTooltipProvider;
        return this;
    }
    
    @Override
    public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        Component c = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        if (!(c instanceof JLabel) || !valueAccessor.accept(value)) {
            return c;
        }
        JLabel label = (JLabel)c;
        String s = valueAccessor.getDisplayName(value);
        label.setText(s != null ? s : ""); // NOI18N
        label.setIcon(valueAccessor.getIcon(value));
        label.setToolTipText(valueAccessor.getDescription(value));
        
        return c;
    }
}
