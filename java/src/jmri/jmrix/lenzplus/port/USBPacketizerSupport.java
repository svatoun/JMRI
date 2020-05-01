/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.port;

import java.io.InputStream;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Newer encapsulation of XPressNet mesages, used by USB and network
 * adapters.
 * @author sdedic
 */
public final class USBPacketizerSupport implements XNetPacketizerDelegate {
    private XNetProtocol    protocol;

    @Override
    public void attachTo(XNetProtocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public int addHeaderToOutput(byte[] msg, XNetMessage m) {
        log.debug("Appending 0xFF 0xFE to start of outgoing message");
        msg[0] = (byte) 0xFF;
        msg[1] = (byte) 0xFE;
        return 2;
    }

    @Override
    public int lengthOfByteStream(XNetMessage m) {
        return m.getNumDataElements() + 2;
    }

    @Override
    public void loadChars(XNetReply msg, InputStream istream) throws java.io.IOException {
        int i;
        byte lastbyte = (byte) 0xFF;
        log.debug("loading characters from port");
        for (i = 0; i < msg.maxSize(); i++) {
            byte char1 = protocol.readByteProtected(istream);
            if (i == 0) {
                protocol.notifyMessageStart(msg);
            }
            // This is a test for the LIUSB device
            while ((i == 0) && ((char1 & 0xF0) == 0xF0)) {
                if ((char1 & 0xFF) != 0xF0 && (char1 & 0xFF) != 0xF2) {
                    // save this so we can check for unsolicited
                    // messages.
                    lastbyte = char1;
                    //  toss this byte and read the next one
                    char1 = protocol.readByteProtected(istream);
                }

            }
            // LIUSB messages are preceeded by 0xFF 0xFE if they are
            // responses to messages we sent.  If they are unrequested
            // information, they are preceeded by 0xFF 0xFD.
            if (lastbyte == (byte) 0xFD) {
                if (i == 0) {
                    log.debug("Receiving unsolicited message, starting with {}", Integer.toHexString(char1));
                }
                msg.setUnsolicited();
            }
            msg.setElement(i, char1 & 0xFF);
            if (protocol.endOfMessage(msg)) {
                break;
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(USBPacketizerSupport.class);
}
