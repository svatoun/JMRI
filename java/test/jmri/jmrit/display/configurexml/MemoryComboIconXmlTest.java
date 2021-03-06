package jmri.jmrit.display.configurexml;

import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * MemoryComboIconXmlTest.java
 *
 * Test for the MemoryComboIconXml class
 *
 * @author   Paul Bender  Copyright (C) 2016
 */
public class MemoryComboIconXmlTest {

    @Test
    public void testCtor(){
      Assert.assertNotNull("MemoryComboIconXml constructor",new MemoryComboIconXml());
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

