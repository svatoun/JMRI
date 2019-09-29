/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.comp;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.text.Document;

/**
 *
 * @author sdedic
 */
public class JmriComponents {
    private static final JmriComponents INSTANCE = new JmriComponents();
    
    private final boolean isGTK = "GTK".equalsIgnoreCase(UIManager.getLookAndFeel().getID());
    
    public static JmriComponents getDefault() {
        return INSTANCE;
        
    }
    
    public JTextField createVarTextField(Document doc, String initText, int col, UIDelegate ui) {
        return addStatusBorder(new VarTextField(doc, initText, col, ui));
    }

    public <T> JComboBox<T> createComboBox(T[] items) {
        JComboBox<T> combo = new JComboBox<>(items);
        
        return combo;
    }
    
    public <T extends JComponent> T addStatusDecorator(T component) {
        assert component != null;
        if (component instanceof JTextField) {
            return addStatusBorder(component);
        } else {
            BorderDecorator deco = new BorderDecorator(component);
            component.addPropertyChangeListener(deco);
            return component;
        }
    }
    
    /**
     * UI delegate for the the JComponent subclasses to communicate with
     * JMRI VariableValues.
     */
    public static interface UIDelegate {
        /**
         * @return status background color.
         */
        public Color getBackground();
        
        /**
         * Informs that the field was entered
         */
        public void enterField();
        
        /**
         * Informs that the field has been exited
         */
        public void exitField();
        
        /**
         * Attaches a listener.
         * @param l listener instance.
         */
        public void addPropertyChangeListener(PropertyChangeListener l);
        public void removePropertyChangeListener(PropertyChangeListener l);
        
        /**
         * Informs that the component has been activated, and the input
         * should update the CV.
         * @param a action event that triggered the component.
         */
        public void actionPerformed(ActionEvent a);
    }

    /**
     * Optionally adds a status border around the component. On GTK+ L&F, which does not
     * support Swing background color, adds a LineBorder around the component.
     * Does nothing on other L&Fs.
     * 
     * @param <T> the component type
     * @param jc JComponent to wrap.
     * @return the configured component, usually {@code jc}.
     */
    public <T extends JComponent> T addStatusBorder(T jc) {
        if (!isGTK) {
            return jc;
        }
        BorderDecorator bdec = new BorderDecorator(jc);
        jc.addPropertyChangeListener(bdec);
        return jc;
    }
    
    private static class BackColorStatusDecorator implements PropertyChangeListener {
        private final UIDelegate delegate;
        private final JComponent component;
        
        public BackColorStatusDecorator(JComponent component, UIDelegate delegate) {
            this.delegate = delegate;
            this.component = component;
            delegate.addPropertyChangeListener(this);
        }
        
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getPropertyName().equals("State")) {
                component.setBackground(delegate.getBackground());
                component.setOpaque(true);
            }
        }
    }
    
    private static class BorderDecorator implements PropertyChangeListener {
        private StatusBorder    statusBorder;
        private Border          origBorder;
        private final JComponent      contents;
        
        BorderDecorator(JComponent jc) {
            this.contents = jc;
            
            statusBorder = new StatusBorder(jc.getBackground(), 2);
            wrapBorder();
            syncBackground();
        }
        
        private void wrapBorder() {
            Border b = contents.getBorder();
            if (b == origBorder) {
                return;
            } else while (b instanceof CompoundBorder) {
                CompoundBorder cb = (CompoundBorder)b;
                if (cb.getOutsideBorder() == statusBorder || cb.getInsideBorder() == statusBorder) {
                    return;
                }
                b = cb.getInsideBorder();
            }
            // the check for CompoundBorder will prevent the event from recursing.
            contents.setBorder(new CompoundBorder(statusBorder, b));
        }
        
        private void syncBackground() {
            statusBorder.setLineColor(contents.getBackground());
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            String pn = evt.getPropertyName();
            if (pn != null) {
                switch (pn) {
                    case "border":
                        wrapBorder();
                        break;
                    case "background":
                        syncBackground();
                        break;
                }
            } else {
                wrapBorder();
                syncBackground();
            }
        }
    }
}
