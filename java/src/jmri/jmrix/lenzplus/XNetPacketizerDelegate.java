/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import java.io.InputStream;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;

/**
 * Allows to encapsulate XpressNet message bytes.
 * @author sdedic
 */
public interface XNetPacketizerDelegate {
    public void attachTo(XNetProtocol protocol);
    public int addHeaderToOutput(byte[] msg, XNetMessage m);
    public int lengthOfByteStream(XNetMessage m);
    public void loadChars(XNetReply msg, InputStream istream) throws java.io.IOException;
}
