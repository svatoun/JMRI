package jmri.jmrix.rfid.merg.concentrator.configurexml;

import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * ConcentratorReporterManagerXmlTest.java
 *
 * Test for the ConcentratorReporterManagerXml class
 *
 * @author   Paul Bender  Copyright (C) 2016
 */
public class ConcentratorReporterManagerXmlTest {

    @Test
    public void testCtor(){
      Assert.assertNotNull("ConcentratorReporterManagerXml constructor",new ConcentratorReporterManagerXml());
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

