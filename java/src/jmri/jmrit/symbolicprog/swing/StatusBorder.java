/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog.swing;

import java.awt.Color;
import javax.swing.border.LineBorder;

/**
 * Just publishes setter for the 'lineColor' property.
 * @author sdedic
 */
public class StatusBorder extends LineBorder {

    public StatusBorder(Color color) {
        super(color);
    }

    public StatusBorder(Color color, int thickness) {
        super(color, thickness);
    }

    public StatusBorder(Color color, int thickness, boolean roundedCorners) {
        super(color, thickness, roundedCorners);
    }

    public void setLineColor(Color c) {
        this.lineColor = c;
    }
}
