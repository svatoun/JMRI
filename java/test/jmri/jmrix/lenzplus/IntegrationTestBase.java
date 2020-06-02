package jmri.jmrix.lenzplus;

import jmri.InstanceManager;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetSystemConnectionMemo;
import jmri.jmrix.lenz.XNetTestSimulator;
import jmri.jmrix.lenz.XNetTrafficController;
import jmri.jmrix.lenz.XNetTurnoutManager;
import jmri.jmrix.lenzplus.config.LenzPlusInitializationManager;
import jmri.jmrix.lenzplus.config.LenzPlusSystemConnectionMemo;
import jmri.jmrix.lenzplus.port.DefaultPacketizerSupport;
import jmri.jmrix.lenzplus.port.XNetPacketizerDelegate;
import jmri.util.JUnitAppender;
import jmri.util.JUnitUtil;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Before;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class IntegrationTestBase extends JUnitTestBase {
    XNetTestSimulator testAdapter;
    TestXNetPlusTrafficController lnis;
    
    XNetTurnoutManager xnetManager;
    
    TestXNetPlusTrafficController output;
    
    /**
     * If true, will clear errors at the end.
     */
    boolean clearErrors;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // prepare an interface
        jmri.util.JUnitUtil.resetInstanceManager();
        jmri.util.JUnitUtil.initInternalSensorManager();
        jmri.util.JUnitUtil.initInternalTurnoutManager();
        jmri.InstanceManager.store(new jmri.NamedBeanHandleManager(), jmri.NamedBeanHandleManager.class);
    }
    
    protected void initializeLayout(XNetTestSimulator adapter) throws Exception {
        initializeLayout(adapter, new DefaultPacketizerSupport());
    }
    
    protected void initializeLayout(XNetTestSimulator adapter, XNetPacketizerDelegate packetizer) throws Exception {
        testAdapter = adapter;
        lnis = new TestXNetPlusTrafficController(new LenzCommandStation());
        output = lnis;
        lnis.setPacketizer(packetizer);
        LenzPlusSystemConnectionMemo memo = new LenzPlusSystemConnectionMemo(lnis);
        testAdapter.setSystemConnectionMemo(memo);
        testAdapter.setInitMgrProvider(()-> new LenzPlusInitializationManager(memo));
        testAdapter.configure(lnis);
        xnetManager = (XNetTurnoutManager)InstanceManager.getDefault().getInstance(XNetSystemConnectionMemo.class).getTurnoutManager();
        testAdapter.drainPackets(true);
    }
    
    @After
    @Override
    public void tearDown() throws Exception {
        XNetTrafficController ctrl = (XNetTrafficController)output; 
        ctrl.terminateThreads();
        ctrl.disconnectPort(testAdapter);
        testAdapter.dispose();
        JUnitUtil.clearShutDownManager(); // put in place because AbstractMRTrafficController implementing subclass was not terminated properly
        if (clearErrors) {
            JUnitAppender.end();
            JUnitAppender.resetUnexpectedMessageFlags(Level.ERROR);
        }
        
        super.tearDown();
    }

}
