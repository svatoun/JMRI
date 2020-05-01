/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.config;

import jmri.jmrix.lenz.AbstractXNetInitializationManager;
import jmri.jmrix.lenz.XNetSystemConnectionMemo;

/**
 *
 * @author sdedic
 */
public class LenzPlusInitializationManager extends AbstractXNetInitializationManager {

    public LenzPlusInitializationManager(XNetSystemConnectionMemo memo) {
        super(memo);
    }

    @Override
    protected void init() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
}
