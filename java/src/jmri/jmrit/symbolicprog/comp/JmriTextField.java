/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.comp;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
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
    private static final int BORDER_BACKGROUND_X_OFFSET = 2;
    private static final int BORDER_BACKGROUND_Y_OFFSET = 2;
    
    /**
     * True, if the L&F during creation is GTK.
     */
    final boolean isGTKLF;
    
    {
        isGTKLF = "GTK".equalsIgnoreCase(UIManager.getLookAndFeel().getID());
//        isGTKLF = false;
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
    
    @Override
    protected void paintComponent(Graphics g) {
        if (isGTKLF && false) {
            paintGTKWorkaround(g);
        } else {
            super.paintComponent(g);
        }
    }
        
    /**
     * Works around background painting in GTK L&F. Paints the component into 
     * a buffered image, then changes all background pixels to the desired color.
     * GTK L&F uses a border around the input line, which contains a thin line, and
     * background-colored inner inset. A pixel from the border is used to determine
     * system's background color. That one will be replaced by Swing's background
     * color set in the {@link #setBackground(java.awt.Color){.
     * 
     * @param g graphics
     */
    private void paintGTKWorkaround(Graphics g) {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), 
                BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG = bi.createGraphics();
        super.paintComponent(tempG);
        Graphics2D g2d = (Graphics2D) g.create();
        Border b = getBorder();
        Insets ins;
        
        if (b == null) {
            ins = new Insets(0, 0, 0, 0);
        } else {
            ins = getBorder().getBorderInsets(this);
        }
        int bkg = bi.getRGB(Math.max(0, ins.left - BORDER_BACKGROUND_X_OFFSET),
                Math.max(0, ins.top - BORDER_BACKGROUND_Y_OFFSET));
        Color cc = new Color(bkg);
        System.err.println("Background color: "  + cc);
        if (getText().equals("111")) {
            System.err.println("Enabled: " + isEnabled());
        }
        int newbk = getBackground().getRGB();
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int px = bi.getRGB(x, y);
                if (px == bkg) {
                    bi.setRGB(x, y, newbk);
                }
            }
        }
        g2d.drawImage(bi, 0, 0, null);
        tempG.dispose();
        g2d.dispose();
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

