package jmri.jmrix.lenzplus;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;

/**
 * Adapter boilerplate class for listeners, which need to handle just
 * part of the callbacks.
 * 
 * @author svatopluk.dedic@gmail.com Copyright(c) 2020
 */
public abstract class XNetAdapter implements XNetListener {
    @Override
    public void message(XNetReply msg) {
    }

    @Override
    public void message(XNetMessage msg) {
    }

    @Override
    public void notifyTimeout(XNetMessage msg) {
    }
}
