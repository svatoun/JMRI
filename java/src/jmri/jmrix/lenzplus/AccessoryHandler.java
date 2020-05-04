/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;

/**
 *
 * @author sdedic
 */
public class AccessoryHandler extends CommandHandler {
    
    public AccessoryHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage, target);
    }
    
}
