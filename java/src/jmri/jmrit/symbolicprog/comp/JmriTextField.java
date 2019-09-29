/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.comp;

import java.awt.Color;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.Document;

/**
 * {@link JTextField} clone that works around L&F. On GTK L&F, the background
 * color used to indicate the CVvalue's state is not honoured. GTK delegates painting
 * of the component to native code, which uses the system L&F settings.
 * <p/>
 * {@code JmriTextField} buffers the native drawing, then bulk-replaces color pixels
 * to the appropriate value of the background. 
 * @author sdedic
 */
public class JmriTextField extends JTextField {
    /**
     * True, if the L&F during creation is GTK.
     */
    final boolean isGTKLF;
    
    {
        isGTKLF = "GTK".equalsIgnoreCase(UIManager.getLookAndFeel().getID());
    }
    
    private LB outBorder = new LB(getBackground(), 2);

    public JmriTextField() {
        initBorder();
    }

    public JmriTextField(String text) {
        super(text);
        initBorder();
    }

    public JmriTextField(int columns) {
        super(columns);
        initBorder();
    }

    public JmriTextField(String text, int columns) {
        super(text, columns);
        initBorder();
    }

    public JmriTextField(Document doc, String text, int columns) {
        super(doc, text, columns);
        initBorder();
    }
    
    private void initBorder() {
        Border b = getBorder();
        if (b != null) {
            setBorder(new CompoundBorder(outBorder, b));
            outBorder.setLineColor(getBackground());
        }
    }
    
    @Override
    public void setBackground(Color bg) {
        super.setBackground(bg);
        if (outBorder != null) {
            outBorder.setLineColor(bg);
        }
    }
    
    static class LB extends LineBorder {

        public LB(Color color) {
            super(color);
        }

        public LB(Color color, int thickness) {
            super(color, thickness);
        }

        public LB(Color color, int thickness, boolean roundedCorners) {
            super(color, thickness, roundedCorners);
        }
        
        void setLineColor(Color c) {
            this.lineColor = c;
        }
        
    }
}

