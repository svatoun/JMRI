package jmri.jmrix.lenz;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class XNetAccess {
    public static int getInternalState(XNetTurnout t) {
        return t.internalState;
    }
}
