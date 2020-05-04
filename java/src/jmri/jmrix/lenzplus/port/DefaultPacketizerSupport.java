/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.port;


import java.io.IOException;
import java.io.InputStream;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 * The basic (=none) encapsulation of Xpressnet protocol, used for
 * older devices.
 * 
 * @author sdedic
 */
public class DefaultPacketizerSupport implements XNetPacketizerDelegate {
    private XNetProtocol protocol;
    
    @Override
    public void attachTo(XNetProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public int addHeaderToOutput(byte[] msg, XNetMessage m) {
        return 0;
    }

    @Override
    public int lengthOfByteStream(XNetMessage m) {
        return m.getNumDataElements();
    }

    @Override
    public void forwardToPort(XNetPlusMessage m, XNetListener reply) {
        protocol.forwardToPort(m, reply);
    }

    @Override
    public void loadChars(XNetPlusReply msg, InputStream istream) throws IOException {
        int i;
        for (i = 0; i < msg.maxSize(); i++) {
            byte char1 = protocol.readByteProtected(istream);
            if (i == 0) {
                protocol.notifyMessageStart(msg);
            }
            msg.setElement(i, char1 & 0xFF);
            if (protocol.endOfMessage(msg)) {
                break;
            }
        }
        if (!protocol.isReplyExpected()) {
            msg.setUnsolicited();
        }
    }
    
}
