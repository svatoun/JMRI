/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.port;

import java.io.IOException;
import java.io.InputStream;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author sdedic
 */
public interface XNetProtocol {
    public byte readByteProtected(InputStream is) throws IOException;
    public void notifyMessageStart(XNetPlusReply reply);
    public boolean endOfMessage(XNetPlusReply reply);
    public boolean isReplyExpected();
    public void forwardToPort(XNetPlusMessage m, XNetListener reply);
}
