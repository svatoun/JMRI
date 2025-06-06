package jmri.jmrit.operations.setup.backup;

import java.awt.GraphicsEnvironment;
import jmri.jmrit.operations.OperationsTestCase;
import jmri.util.JUnitUtil;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.junit.Assume;

/**
 *
 * @author Paul Bender Copyright (C) 2017
 */
public class ManageBackupsDialogTest extends OperationsTestCase {

    @Test
    public void testCTor() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        ManageBackupsDialog t = new ManageBackupsDialog();
        Assert.assertNotNull("exists", t);
        JUnitUtil.dispose(t);
    }

    // private final static Logger log = LoggerFactory.getLogger(ManageBackupsDialogTest.class);
}
