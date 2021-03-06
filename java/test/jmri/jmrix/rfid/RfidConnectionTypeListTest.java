package jmri.jmrix.rfid;

import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * RfidConnectionTypeListTest.java
 *
 * Test for the jmri.jmrix.rfid.RfidConnectionTypeList class
 *
 * @author Paul Bender Copyright (C) 2012,2016
 */
public class RfidConnectionTypeListTest {

    @Test
    public void testCtor() {
        RfidConnectionTypeList c = new RfidConnectionTypeList();
        Assert.assertNotNull(c);
    }

    @Before
    public void setUp() {
        JUnitUtil.setUp();
    }

    @After
    public void tearDown() {
        JUnitUtil.tearDown();
    }

}
