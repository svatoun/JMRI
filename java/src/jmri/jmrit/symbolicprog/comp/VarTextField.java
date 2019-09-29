/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.comp;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.beans.PropertyChangeListener;
import javax.swing.text.Document;
import jmri.jmrit.symbolicprog.comp.JmriComponents.UIDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author sdedic
 */
class VarTextField extends JmriTextField {
    private final static Logger log = LoggerFactory.getLogger(VarTextField.class);
    
    public VarTextField(Document doc, String text, int col, UIDelegate var) {
        super(doc, text, col);
        _var = var;
        // get the original color right
        setBackground(_var.getBackground());
        // listen for changes to ourself
        addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                thisActionPerformed(e);
            }
        });
        addFocusListener(new java.awt.event.FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                if (log.isDebugEnabled()) {
                    log.debug("focusGained");
                }
                _var.enterField();
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (log.isDebugEnabled()) {
                    log.debug("focusLost");
                }
                _var.exitField();
            }
        });
        // listen for changes to original state
        _var.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            @Override
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                originalPropertyChanged(e);
            }
        });
    }

    UIDelegate _var;

    void thisActionPerformed(java.awt.event.ActionEvent e) {
        // tell original
        _var.actionPerformed(e);
    }

    void originalPropertyChanged(java.beans.PropertyChangeEvent e) {
        // update this color from original state
        if (e.getPropertyName().equals("State")) {
            setBackground(_var.getBackground());
        }
    }

}
