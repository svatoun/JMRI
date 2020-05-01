/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.port;

import java.io.IOException;
import java.io.InputStream;
import jmri.jmrix.lenz.XNetReply;

/**
 *
 * @author sdedic
 */
public interface XNetProtocol {
    public byte readByteProtected(InputStream is) throws IOException;
    public void notifyMessageStart(XNetReply reply);
    public boolean endOfMessage(XNetReply reply);
    public boolean isReplyExpected();
}
