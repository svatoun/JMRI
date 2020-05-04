package jmri.jmrix.lenzplus.config;

import jmri.InstanceManager;
import jmri.jmrix.lenz.*;
import jmri.TurnoutManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Paul Bender Copyright (C) 2010 -- Blueprint
 */
public class LenzPlusSystemConnectionMemo extends XNetSystemConnectionMemo {

    public LenzPlusSystemConnectionMemo(XNetTrafficController xt) {
        super(xt);
        log.debug("Created XNetPlusConnectionMemo");
        InstanceManager.store(this, LenzPlusSystemConnectionMemo.class); // also register as specific type
    }

    public LenzPlusSystemConnectionMemo() {
        super();
        log.debug("Created XNetPlusConnectionMemo");
        InstanceManager.store(this, LenzPlusSystemConnectionMemo.class); // also register as specific type
    }

    @Override
    public void setXNetTrafficController(XNetTrafficController xt) {
        if (getXNetTrafficController() != null && !xt.getClass().getName().contains("jmri.jmrix.lenzplus")) {
            return;
        }
        super.setXNetTrafficController(xt);
    }

    @Override
    public boolean provides(Class<?> type) {
        if (getDisabled()) {
            return false;
        }
        if (type == jmri.TurnoutManager.class) {
            return true;
        }
        return super.provides(type);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Class<?> T) {
        if (getDisabled()) {
            return null;
        }
        if (T == TurnoutManager.class) {
            return (T) getTurnoutManager();
        }
        return super.get(T);
    }

    private static final Logger log = LoggerFactory.getLogger(LenzPlusSystemConnectionMemo.class);

}
