package jmri.implementation;

import jmri.Conditional;
import jmri.util.JUnitUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Paul Bender Copyright (C) 2017
 */
public class JmriMultiStatePropertyListenerTest {

    @Test
    public void testCTor() {
        JmriMultiStatePropertyListener t =
                new JmriMultiStatePropertyListener("foo",0,"bar",
                        Conditional.Type.SENSOR_ACTIVE,new DefaultConditional("foo"),0);
        Assert.assertNotNull("exists",t);
    }

    @Before
    public void setUp() {
        JUnitUtil.setUp();
    }

    @After
    public void tearDown() {
        JUnitUtil.tearDown();
    }

    // private final static Logger log = LoggerFactory.getLogger(JmriMultiStatePropertyListenerTest.class);

}
