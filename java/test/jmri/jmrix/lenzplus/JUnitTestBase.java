/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Before;

/**
 *
 * @author sdedic
 */
public class JUnitTestBase {
    @Before
    public void setUp() {
        String old = System.setProperty("java.awt.headless", "true");
        try {
            JUnitUtil.setUp();
        } finally {
            if (old == null) {
                System.getProperties().remove("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", old);
            }
        }
    }
    
    @After
    public void tearDown() {
        JUnitUtil.tearDown();
    }
}
