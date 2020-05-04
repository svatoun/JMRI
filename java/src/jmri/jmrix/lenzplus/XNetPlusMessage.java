/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetMessage;

/**
 *
 * @author sdedic
 */
public class XNetPlusMessage extends XNetMessage {
    private volatile int initialDelay;
    
    public XNetPlusMessage(XNetMessage message) {
        super(message);
    }

    public XNetPlusMessage(String s) {
        super(s);
    }
    
    public boolean isDelayed() {
        return initialDelay > 0;
    }
    
    public int getDelay() {
        return initialDelay;
    }
    
    public XNetPlusMessage delayed(int millis) {
        this.initialDelay = millis;
        return this;
    }
}
