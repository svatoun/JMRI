/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.config;

import jmri.TurnoutManager;
import jmri.jmrix.lenz.XNetInitializationManagerLazy;
import jmri.jmrix.lenz.XNetSystemConnectionMemo;
import jmri.jmrix.lenzplus.XNetPlusTurnoutManager;

/**
 * Overrides standard jmrix.lenz services with alternative implementations,
 * where applicable.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class LenzPlusInitializationManager extends XNetInitializationManagerLazy {

    public LenzPlusInitializationManager(XNetSystemConnectionMemo memo) {
        super(memo);
    }

    @Override
    protected void defineServices() {
        super.defineServices();
        register(TurnoutManager.class, () -> new XNetPlusTurnoutManager(this.systemMemo));
    }
}
