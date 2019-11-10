package jmri.jmrit.symbolicprog;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.logging.Level;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decimal representation of a value.
 *
 * @author Bob Jacobsen Copyright (C) 2001
 */
public class DecVariableValue extends VariableValue
        implements ActionListener, FocusListener {

    public DecVariableValue(String name, String comment, String cvName,
            boolean readOnly, boolean infoOnly, boolean writeOnly, boolean opsOnly,
            String cvNum, String mask, int minVal, int maxVal,
            HashMap<String, CvValue> v, JLabel status, String stdname) {
        super(name, comment, cvName, readOnly, infoOnly, writeOnly, opsOnly, cvNum, mask, v, status, stdname);
        _maxVal = maxVal;
        _minVal = minVal;
        _value = new JTextField("0", fieldLength());
        _defaultColor = _value.getBackground();
        _value.setBackground(COLOR_UNKNOWN);
        // connect to the JTextField value, cv
        _value.addActionListener(this);
        _value.addFocusListener(this);
        CvValue cv = _cvMap.get(getCvNum());
        cv.addPropertyChangeListener(this);
        cv.setState(CvValue.FROMFILE);
    }

    @Override
    public void setToolTipText(String t) {
        super.setToolTipText(t);   // do default stuff
        _value.setToolTipText(t);  // set our value
    }

    int _maxVal;
    int _minVal;

    int fieldLength() {
        if (_maxVal <= 255) {
            return 3;
        }
        return (int) Math.ceil(Math.log10(_maxVal)) + 1;
    }

    @Override
    public CvValue[] usesCVs() {
        return new CvValue[]{_cvMap.get(getCvNum())};
    }

    @Override
    public Object rangeVal() {
        return "Decimal: " + _minVal + " - " + _maxVal;
    }

    String oldContents = "";

    void enterField() {
        oldContents = _value.getText();
    }

    int textToValue(String s) {
        return (Integer.parseInt(s));
    }

    String valueToText(int v) {
        return (Integer.toString(v));
    }

    void exitField() {
        if (_value == null) {
            // There's no value Object yet, so just ignore & exit
            return;
        }
        // what to do for the case where _value != null?
        if (!_value.getText().equals("")) {
            // there may be a lost focus event left in the queue when disposed so protect
            if (!oldContents.equals(_value.getText())) {
                try {
                    int newVal = textToValue(_value.getText());
                    int oldVal = textToValue(oldContents);
                    updatedTextField();
                    prop.firePropertyChange("Value", oldVal, newVal);
                } catch (java.lang.NumberFormatException ex) {
                    _value.setText(oldContents);
                }
            }
        } else {
            //As the user has left the contents blank, we shall re-instate the old
            // value as, when a write to decoder is performed, the cv remains the same value.
            _value.setText(oldContents);
        }
    }

    /**
     * Invoked when a permanent change to the JTextField has been made. Note
     * that this does _not_ notify property listeners; that should be done by
     * the invoker, who may or may not know what the old value was. Can be
     * overridden in subclasses that want to display the value differently.
     */
    @Override
    void updatedTextField() {
        if (log.isDebugEnabled()) {
            log.debug("updatedTextField");
        }
        // called for new values - set the CV as needed
        CvValue cv = _cvMap.get(getCvNum());
        // compute new cv value by combining old and request
        int oldCv = cv.getValue();
        int newVal;
        try {
            newVal = textToValue(_value.getText());
        } catch (java.lang.NumberFormatException ex) {
            newVal = 0;
        }
        int newCv = setValueInCV(oldCv, newVal, getMask(), _maxVal);
        if (oldCv != newCv) {
            cv.setValue(newCv);
        }
    }

    /**
     * ActionListener implementations
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (log.isDebugEnabled()) {
            log.debug("actionPerformed");
        }
        try {
            int newVal = textToValue(_value.getText());
            updatedTextField();
            prop.firePropertyChange("Value", null, newVal);
        } catch (java.lang.NumberFormatException ex) {
            _value.setText(oldContents);
        }
    }

    /**
     * FocusListener implementations
     */
    @Override
    public void focusGained(FocusEvent e) {
        if (log.isDebugEnabled()) {
            log.debug("focusGained");
        }
        enterField();
    }

    @Override
    public void focusLost(FocusEvent e) {
        if (log.isDebugEnabled()) {
            log.debug("focusLost");
        }
        exitField();
    }

    // to complete this class, fill in the routines to handle "Value" parameter
    // and to read/write/hear parameter changes.
    @Override
    public String getValueString() {
        return _value.getText();
    }

    @Override
    public void setIntValue(int i) {
        setValue(i);
    }

    @Override
    public int getIntValue() {
        return textToValue(_value.getText());
    }

    @Override
    public Object getValueObject() {
        return Integer.valueOf(_value.getText());
    }

    @Override
    public Component getCommonRep() {
        if (getReadOnly()) {
            JLabel r = new JLabel(_value.getText());
            addPropertyChangeListener((evt) -> {
                if ("Available".equals(evt.getPropertyName())) { // NOI18N
                    r.setVisible(getAvailable());
                }
            });
            updateRepresentation(r);
            return r;
        } else {
            return _value;
        }
    }

    // FIXME: this is not necessary to override. As representations are
    // created by this class, the representation can listen on property changes
    // and make visible itself.
    // since `reps' is not used for any other reason, it can be discarded; the JComponent
    // can be a WeakListener, so it will not be kept; in addition, it can register
    // itself in its add/removeNotify.
    @Override
    public void setAvailable(boolean a) {
        _value.setVisible(a);
        super.setAvailable(a);
    }

    @Override
    public Component getNewRep(String format) {
        if (format.equals("vslider")) {
            DecVarSlider b = new DecVarSlider(this, _minVal, _maxVal);
            b.setOrientation(JSlider.VERTICAL);
            updateRepresentation(b);
            return b;
        } else if (format.equals("hslider")) {
            DecVarSlider b = new DecVarSlider(this, _minVal, _maxVal);
            b.setOrientation(JSlider.HORIZONTAL);
            updateRepresentation(b);
            return b;
        } else if (format.equals("hslider-percent")) {
            DecVarSlider b = new DecVarSlider(this, _minVal, _maxVal);
            b.setOrientation(JSlider.HORIZONTAL);
            if (_maxVal > 20) {
                b.setMajorTickSpacing(_maxVal / 2);
                b.setMinorTickSpacing((_maxVal + 1) / 8);
            } else {
                b.setMajorTickSpacing(5);
                b.setMinorTickSpacing(1); // because JSlider does not SnapToValue
                b.setSnapToTicks(true);   // like it should, we fake it here
            }
            b.setSize(b.getWidth(), 28);
            Hashtable<Integer, JLabel> labelTable = new Hashtable<Integer, JLabel>();
            labelTable.put(Integer.valueOf(0), new JLabel("0%"));
            if (_maxVal == 63) {   // this if for the QSI mute level, not very universal, needs work
                labelTable.put(Integer.valueOf(_maxVal / 2), new JLabel("25%"));
                labelTable.put(Integer.valueOf(_maxVal), new JLabel("50%"));
            } else {
                labelTable.put(Integer.valueOf(_maxVal / 2), new JLabel("50%"));
                labelTable.put(Integer.valueOf(_maxVal), new JLabel("100%"));
            }
            b.setLabelTable(labelTable);
            b.setPaintTicks(true);
            b.setPaintLabels(true);
            updateRepresentation(b);
            if (!getAvailable()) {
                b.setVisible(false);
            }
            return b;
        } else {
            JComponent val;
            
            boolean nonEditable = getReadOnly() || getInfoOnly();
            if (false && !nonEditable && _minVal > Integer.MIN_VALUE && _maxVal < Integer.MAX_VALUE) {
                String initText = _value.getText();
                Number initV;
                try {
                    initV = Integer.parseInt(initText);
                } catch (NumberFormatException ex) {
                    initV = Integer.valueOf("0");
                }
                val = createSpinner(initV);
            } else {
                JTextField value = new VarTextField(_value.getDocument(), _value.getText(), fieldLength(), this);
                if (nonEditable) {
                    value.setEditable(false);
                }
                val = value;
            }
            updateRepresentation(val);
            return val;
        }
    }
    
    private JComponent createSpinner(Number initV) {
        SpinnerModel model = new SpinnerNumberModel(initV, _minVal, _maxVal, 1);
        JSpinner spinner = new JSpinner(model);
        JSpinner.NumberEditor ne = new JSpinner.NumberEditor(spinner, "#");
        ne.getTextField().setHorizontalAlignment(JTextField.LEFT);
        spinner.setEditor(ne);
//        ne.getTextField().setDocument(_value.getDocument());
        ne.getTextField().setFocusLostBehavior(JFormattedTextField.COMMIT);
        
        // Spinner does not update on reaction to document, but to focus lost;
        // to have the behaviour the same for all fields, let's commit its value
        // each time the document is updated
        SpinnerDocumentUpdater updater = new SpinnerDocumentUpdater(ne, _value.getDocument());
        _value.getDocument().addDocumentListener(updater);
        ne.getTextField().getDocument().addDocumentListener(updater);
        ne.getTextField().addPropertyChangeListener(updater);
        this.addPropertyChangeListener(new StatusColorizer(spinner, ne, this));
        spinner.setBackground(_value.getBackground());
        ne.getTextField().setBackground(_value.getBackground());
        return spinner;
    }
    
    private static class StatusColorizer implements PropertyChangeListener {
        private final JComponent    target;
        private final VariableValue variable;
        private final JSpinner.DefaultEditor editor;

        public StatusColorizer(JComponent target, JSpinner.DefaultEditor editor, VariableValue variable) {
            this.target = target;
            this.editor = editor;
            this.variable = variable;
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (variable != evt.getSource() || evt.getPropertyName() == null) {
                return;
            }
            switch (evt.getPropertyName()) {
                case "Value":
                    if (editor.isFocusOwner()) {
                        return;
                    }
                    try {
                        editor.commitEdit();
                    } catch (ParseException ex) {
                        // ignore;
                    }
                    break;
                    
                case "State":
                    // because of bug in VariableValue refused to be fixed, need to evaluate after the current event
                    // terminates:
                    SwingUtilities.invokeLater(() -> {
                        Color c = VariableValue.stateColorFromValue(variable.getState());
                        target.setBackground(c);
                        editor.getTextField().setBackground(c);
                    });
                    break;
            }
        }
    }
    
    private static class SpinnerDocumentUpdater implements DocumentListener, PropertyChangeListener {
        private final JSpinner.DefaultEditor editor;
        private final Document otherDocument;
        
        private boolean inProgress;
        
        public SpinnerDocumentUpdater(JSpinner.DefaultEditor editor, Document other) {
            this.editor = editor;
            this.otherDocument = other;
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (inProgress) {
                return;
            }
            inProgress = true;
            try {
                if (evt.getSource() == editor.getTextField()) {
                    if ("value".equals(evt.getPropertyName())) {
                        String s = editor.getTextField().getText();
                        AbstractDocument dd = (AbstractDocument)otherDocument;
                        try {
                            dd.replace(0, dd.getLength(), s, null);
                        } catch (BadLocationException ex) {
                            // should not happen.
                        }
                    }
                }
            } finally {
                inProgress = false;
            }
        }
        
        @Override
        public void insertUpdate(DocumentEvent e) {
            if (inProgress) {
                return;
            }
            inProgress = true;
            try {
                if (e.getDocument() == editor.getTextField().getDocument()) {

                } else {
                    try {
                        try {
                            editor.getTextField().setText(e.getDocument().getText(0, e.getDocument().getLength()));
                        } catch (BadLocationException ex) {
                            // should not happen.
                        }
                        editor.commitEdit();
                    } catch (ParseException ex) {
                        // just ignore, should not be thrown
                    }
                }
            } finally {
                inProgress = false;
            }
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            insertUpdate(e);
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            // no op.
        }
    }

    /**
     * Set a new value, including notification as needed. This does the
     * conversion from string to int, so if the place where formatting needs to
     * be applied
     */
    public void setValue(int value) {
        int oldVal;
        try {
            oldVal = textToValue(_value.getText());
        } catch (java.lang.NumberFormatException ex) {
            oldVal = -999;
        }
        if (value < _minVal) value = _minVal;
        if (value > _maxVal) value = _maxVal;
        if (log.isDebugEnabled()) {
            log.debug("setValue with new value " + value + " old value " + oldVal);
        }
        if (oldVal != value) {
            _value.setText(valueToText(value));
            updatedTextField();
            prop.firePropertyChange("Value", Integer.valueOf(oldVal), Integer.valueOf(value));
        }
    }

    Color _defaultColor;

    // implement an abstract member to set colors
    Color getDefaultColor() {
        return _defaultColor;
    }

    Color getColor() {
        return _value.getBackground();
    }

    @Override
    void setColor(Color c) {
        if (c != null) {
            _value.setBackground(c);
        } else {
            _value.setBackground(_defaultColor);
        }
        // prop.firePropertyChange("Value", null, null);
    }

    /**
     * Notify the connected CVs of a state change from above
     *
     */
    @Override
    public void setCvState(int state) {
        _cvMap.get(getCvNum()).setState(state);
    }

    @Override
    public boolean isChanged() {
        CvValue cv = _cvMap.get(getCvNum());
        if (log.isDebugEnabled()) {
            log.debug("isChanged for " + getCvNum() + " state " + cv.getState());
        }
        return considerChanged(cv);
    }

    @Override
    public void readChanges() {
        if (isChanged()) {
            readAll();
        }
    }

    @Override
    public void writeChanges() {
        if (isChanged()) {
            writeAll();
        }
    }

    @Override
    public void readAll() {
        setToRead(false);
        setBusy(true);  // will be reset when value changes
        //super.setState(READ);
        _cvMap.get(getCvNum()).read(_status);
    }

    @Override
    public void writeAll() {
        setToWrite(false);
        if (getReadOnly()) {
            log.error("unexpected write operation when readOnly is set");
        }
        setBusy(true);  // will be reset when value changes
        _cvMap.get(getCvNum()).write(_status);
    }

    // handle incoming parameter notification
    @Override
    public void propertyChange(java.beans.PropertyChangeEvent e) {
        // notification from CV; check for Value being changed
        if (log.isDebugEnabled()) {
            log.debug("Property changed: " + e.getPropertyName());
        }
        if (e.getPropertyName().equals("Busy")) {
            if (((Boolean) e.getNewValue()).equals(Boolean.FALSE)) {
                setToRead(false);
                setToWrite(false);  // some programming operation just finished
                setBusy(false);
            }
        } else if (e.getPropertyName().equals("State")) {
            CvValue cv = _cvMap.get(getCvNum());
            if (cv.getState() == STORED) {
                setToWrite(false);
            }
            if (cv.getState() == READ) {
                setToRead(false);
            }
            setState(cv.getState());
        } else if (e.getPropertyName().equals("Value")) {
            // update value of Variable
            CvValue cv = _cvMap.get(getCvNum());
            int newVal = getValueInCV(cv.getValue(), getMask(), _maxVal);
            setValue(newVal);  // check for duplicate done inside setVal
        }
    }

    // stored value, read-only Value
    JTextField _value = null;

    /* Internal class extends a JTextField so that its color is consistent with
     * an underlying variable
     *
     * @author   Bob Jacobsen   Copyright (C) 2001
     */
    public class VarTextField extends JTextField {
        private final L listener = new L();
        
        private class L implements FocusListener, PropertyChangeListener, ActionListener {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                thisActionPerformed(e);
            }

            @Override
            public void focusGained(FocusEvent e) {
                if (log.isDebugEnabled()) {
                    log.debug("focusGained");
                }
                enterField();
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (log.isDebugEnabled()) {
                    log.debug("focusLost");
                }
                exitField();
            }

            @Override
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                originalPropertyChanged(e);
            }
        }
        
        VarTextField(Document doc, String text, int col, DecVariableValue var) {
            super(doc, text, col);
            _var = var;
            // listen for changes to ourself
            addActionListener(listener);
            addFocusListener(listener);
        }

        DecVariableValue _var;

        void thisActionPerformed(java.awt.event.ActionEvent e) {
            // tell original
            _var.actionPerformed(e);
        }

        void originalPropertyChanged(java.beans.PropertyChangeEvent e) {
            // update this color from original state
            if (null == e.getPropertyName()) {
                updateState();
            }
            switch (e.getPropertyName()) {
                case "State":
                case "Available":
                    updateState();
                    break;
                default:
                    // just do nothing
            }
        }
        
        private void updateState() {
            setBackground(state2Color(_var.getState()));
            setVisible(_var.getAvailable());
        }

        @Override
        public void addNotify() {
            super.addNotify();
            // listen for changes to original state
            // PENDING: should use WeakListener, makes upwards reference from model
            // to UI layer.
            _var.addPropertyChangeListener(listener);
            updateState();
        }

        @Override
        public void removeNotify() {
            _var.removePropertyChangeListener(listener);
            super.removeNotify();
        }
    }

    // clean up connections when done
    @Override
    public void dispose() {
        if (log.isDebugEnabled()) {
            log.debug("dispose");
        }
        if (_value != null) {
            _value.removeActionListener(this);
        }
        _cvMap.get(getCvNum()).removePropertyChangeListener(this);

        _value = null;
    }

    // initialize logging
    private final static Logger log = LoggerFactory.getLogger(DecVariableValue.class);

}
