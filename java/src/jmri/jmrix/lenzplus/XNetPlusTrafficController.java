/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.lenz.LenzCommandStation;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetPacketizer;
import jmri.jmrix.lenz.XNetReply;

/**
 *
 * @author sdedic
 */
public class XNetPlusTrafficController extends XNetPacketizer implements XNetProtocol {
    private XNetPacketizerDelegate  packetizer;
    
    public XNetPlusTrafficController(LenzCommandStation pCommandStation) {
        super(pCommandStation);
    }

    public XNetPacketizerDelegate getPacketizer() {
        return packetizer;
    }

    public XNetPacketizer setPacketizer(XNetPacketizerDelegate packetizer) {
        this.packetizer = packetizer;
        packetizer.attachTo(this);
        return this;
    }

    // Delegate encapsulation to a helper
    // -----------------------------------------------------
    @Override
    protected void loadChars(AbstractMRReply msg, DataInputStream istream) throws IOException {
        packetizer.loadChars((XNetReply)msg, istream);
    }

    @Override
    protected int lengthOfByteStream(AbstractMRMessage m) {
        return packetizer.lengthOfByteStream((XNetMessage)m);
    }

    @Override
    protected int addHeaderToOutput(byte[] msg, AbstractMRMessage m) {
        return packetizer.addHeaderToOutput(msg, (XNetMessage)m);
    }
    
    // XNetProtocol delegate implementation
    // -----------------------------------------------------
    @Override
    public byte readByteProtected(InputStream is) throws IOException {
        // for some weird reason, DataInputStream is required, although
        // not methods from the Data* interface is used.
        return super.readByteProtected((DataInputStream)istream);
    }

    @Override
    public void notifyMessageStart(XNetReply reply) {
        super.notifyMessageStart(reply);
    }

    @Override
    public boolean endOfMessage(XNetReply reply) {
        return super.endOfMessage(reply);
    }

    @Override
    public boolean isReplyExpected() {
        return mCurrentState != IDLESTATE;
    }
}
