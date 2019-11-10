package jmri.jmrit.symbolicprog;

import java.awt.Color;
import java.beans.PropertyChangeListener;
import javax.swing.BoundedRangeModel;
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* Extends a JSlider so that its color & value are consistent with
 * an underlying variable; we return one of these in DecValVariable.getNewRep.
 *
 * @author   Bob Jacobsen   Copyright (C) 2001
 */
public class DecVarSlider extends JSlider implements ChangeListener {
    private final PropertyChangeListener varListener = this::originalPropertyChanged;
    
    DecVarSlider(DecVariableValue var, int min, int max) {
        super(new DefaultBoundedRangeModel(min, 0, min, max));
        _var = var;
        // set the original value
        setValue(Integer.parseInt(_var.getValueString()));
        // listen for changes here
        addChangeListener(this);
    }

    @Override
    public void removeNotify() {
        _var.removePropertyChangeListener(varListener);
        super.removeNotify();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // listen for changes to associated variable
        _var.addPropertyChangeListener(varListener);
        updateState();
    }

    @Override
    public void stateChanged(ChangeEvent e) {
        // called for new values of a slider - set the variable value as needed
        // e.getSource() points to the JSlider object - find it in the list
        JSlider j = (JSlider) e.getSource();
        BoundedRangeModel r = j.getModel();

        _var.setIntValue(r.getValue());
        _var.setState(AbstractValue.EDITED);
    }
    
    void updateState() {
        Color c = _var.state2Color(_var.getState());
        setBackground(c);
        if (c == null  || c == _var.getDefaultColor()) {
            setOpaque(false);
        } else {
            setOpaque(true);
        }
        setVisible(_var.getAvailable());
    }

    DecVariableValue _var;

    void originalPropertyChanged(java.beans.PropertyChangeEvent e) {
        if (log.isDebugEnabled()) {
            log.debug("VarSlider saw property change: " + e);
        }
        switch (e.getPropertyName()) {
            // update this color from original state
            case "Value":
                int newValue = Integer.parseInt(((JTextField) _var.getCommonRep()).getText());
                setValue(newValue);
                break;
            case "State": 
            case "Available":
                updateState();
        }
    }

    // initialize logging
    private final static Logger log = LoggerFactory.getLogger(DecVarSlider.class);

}
