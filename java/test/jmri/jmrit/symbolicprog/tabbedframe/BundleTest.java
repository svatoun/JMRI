package jmri.jmrit.symbolicprog.tabbedframe;


import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the Bundle class
 *
 * @author Bob Jacobsen Copyright (C) 2012
 */
public class BundleTest  {

    @Test public void testGoodKeys() {
        Assert.assertEquals("Read", Bundle.getMessage("ButtonRead"));
        Assert.assertEquals("Tools", Bundle.getMessage("MenuTools"));
        Assert.assertEquals("Turnout", Bundle.getMessage("BeanNameTurnout"));
    }

    @Test(expected = java.util.MissingResourceException.class)
    public void testBadKey() {
            Bundle.getMessage("FFFFFTTTTTTT");
    }


}
