package jmri.jmrix.tams;

import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Paul Bender Copyright (C) 2017
 */
public class TamsProgrammerManagerTest {

    @Test
    public void testCTor() {
        TamsTrafficController tc = new TamsTrafficController();
        TamsSystemConnectionMemo memo = new TamsSystemConnectionMemo(tc);  
        TamsProgrammerManager t = new TamsProgrammerManager(new TamsProgrammer(tc),memo);
        Assert.assertNotNull("exists",t);
    }

    @Before
    public void setUp() {
        JUnitUtil.setUp();
    }

    @After
    public void tearDown() {
        JUnitUtil.clearShutDownManager(); // put in place because AbstractMRTrafficController implementing subclass was not terminated properly
        JUnitUtil.tearDown();
    }

    // private final static Logger log = LoggerFactory.getLogger(TamsProgrammerManagerTest.class);

}
