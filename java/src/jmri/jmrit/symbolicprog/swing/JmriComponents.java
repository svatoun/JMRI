/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.swing;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.text.Document;
import jmri.jmrit.symbolicprog.VariableValue;

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
    
    /**
     * The only reason for subclassing is awkward initialization of a default color in
     * various *Variable subclasses. So will sync background color to the desired one
     */
    static class VarTextField extends JTextField {
        private final UIDelegate uiDelegate;
        
        public VarTextField(UIDelegate uidel, Document doc, String text, int columns) {
            super(doc, text, columns);
            this.uiDelegate = uidel;
        }

        public void addNotify() {
            super.addNotify();
            setBackground(uiDelegate.getStateColor());
        }
    }
    
    public JTextField textField(Document doc, String initText, int col, UIDelegate ui) {
        return addStatusDecorator(new VarTextField(ui, doc, initText, col), ui);
    }

    public <T> JComboBox<T> comboBox(T[] items, UIDelegate del) {
        JComboBox<T> combo = new JComboBox<>(items);
        return addStatusDecorator(combo, del);
    }
    
    public <T extends JComponent> T addStatusDecorator(T component, UIDelegate del) {
        assert component != null;
        EventForwarder fwd = new EventForwarder(del);
        /*
        if (component instanceof JComboBox) {
            addStatusBorder(component, del);
            ((JComboBox)component).addActionListener(fwd);
        } else if (component instanceof JTextField) {
            addStatusBorder(component, del);
            ((JTextField)component).addActionListener(fwd);
        } else {
        */
        if (component instanceof JTextField) {
//            component.setName("Tree.cellEditor");
        }
            BackColorStatusDecorator deco = new BackColorStatusDecorator(component, del);
            component.addPropertyChangeListener(deco);
        //}
        component.addFocusListener(fwd);
        return component;
    }
    
    /**
     * UI delegate for the the JComponent subclasses to communicate with
     * JMRI VariableValues.
     */
    public static interface UIDelegate {
        /**
         * @return status background color.
         */
        public Color getStateColor();
        
        /**
         * Informs that the field was entered
         */
        public default void enterField() {}
        
        /**
         * Informs that the field has been exited
         */
        public default void exitField() {}
        
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
    
    public <T extends JComponent> T addStatusBorder(T jc, UIDelegate ui) {
        if (!isGTK) {
            BackColorStatusDecorator deco = new BackColorStatusDecorator(jc, ui);
            jc.addPropertyChangeListener(deco);
            return jc;
        }
        BorderDecorator bdec = new BorderDecorator(jc);
        jc.addPropertyChangeListener(bdec);
        return jc;
    }
    
    private static class EventForwarder implements ActionListener, FocusListener {
        private final UIDelegate var;

        public EventForwarder(UIDelegate var) {
            this.var = var;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            var.actionPerformed(e);
        }

        @Override
        public void focusGained(FocusEvent e) {
            var.enterField();
        }

        @Override
        public void focusLost(FocusEvent e) {
            var.exitField();
        }
    }
    
    private static class BackColorStatusDecorator implements PropertyChangeListener {
        private final UIDelegate delegate;
        private final JComponent component;
        
        public BackColorStatusDecorator(JComponent component, UIDelegate delegate) {
            this.delegate = delegate;
            this.component = component;
            Color c = delegate.getStateColor();
            if (c != null && c != VariableValue.stateColorFromValue(VariableValue.UNKNOWN)) {
                component.setBackground(c);
            }
            delegate.addPropertyChangeListener(this);
        }
        
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getPropertyName().equals("State")) {
                component.setBackground(delegate.getStateColor());
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
