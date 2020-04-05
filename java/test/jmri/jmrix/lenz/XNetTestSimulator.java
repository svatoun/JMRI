/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import jmri.jmrix.lenz.xnetsimulator.XNetSimulatorAdapter;

/**
 * A special flavour of XPressNet simulator suitable for testing. It allows
 * to delay responses, capture outgoing or incoming messages etc.
 * 
 * @author sdedic
 */
abstract class XNetTestSimulator extends XNetSimulatorAdapter {
    
    private List<XNetReply> replyBuffer = new ArrayList<>();
    private List<XNetReply> additionalReplies = new ArrayList<>();
    
    protected final BitSet accessoryState = new BitSet(1024);
    protected final BitSet accessoryOperated = new BitSet(1024);

    /**
     * If set to true, the {@link #repliesAllowed} semaphore will
     * release replies. If false, all replies are released immediately.
     */
    volatile boolean limitReplies;
    
    /**
     * Number of replies allowed to return to the JMRI.
     */
    Semaphore repliesAllowed = new Semaphore(0);
    
    private List<XNetMessage>   outgoingMessages = new ArrayList<>();
    private List<XNetReply>     incomingReplies = new ArrayList<>();

    /**
     * If true, accumulates sent/received messages. Use {@link #getOutgoingMessages()}
     * and {@link #getIncomingReplies()} to get messages. Use {@link #clearMesages()}
     * to reset the lists.
     */
    private volatile boolean captureMessages;

    public void setCaptureMessages(boolean captureMessages) {
        this.captureMessages = captureMessages;
    }

    public synchronized List<XNetMessage> getOutgoingMessages() {
        return new ArrayList<>(outgoingMessages);
    }

    public synchronized List<XNetReply> getIncomingReplies() {
        return new ArrayList<>(incomingReplies);
    }
    
    public synchronized void clearMesages() {
        outgoingMessages.clear();
        incomingReplies.clear();
    }
    
    private void insertAdditionalReplies() {
        replyBuffer.addAll(additionalReplies);
        additionalReplies.clear();
    }

    protected XNetReply addReply(XNetReply r) {
        additionalReplies.add(r);
        return r;
    }

    public void configure(XNetTrafficController ctrls) {
        super.configure(ctrls);
    }

    private void maybeWaitBeforeReply(XNetReply reply) {
        if (!limitReplies) {
            return;
        }
        if (reply instanceof XNetTurnoutMonitoringTest.PrimaryXNetReply) {
            try {
                repliesAllowed.acquire();
            } catch (InterruptedException ex) {
                java.util.logging.Logger.getLogger(XNetTurnoutMonitoringTest.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    @Override
    protected XNetMessage readMessage() {
        XNetMessage msg = super.readMessage();
        if (captureMessages) {
            synchronized (this) {
                outgoingMessages.add(msg);
            }
        }
        return msg;
    }
    
    private XNetReply captureReply(XNetReply r) {
        if (captureMessages) {
            synchronized (this) {
                incomingReplies.add(r);
            }
        }
        return r;
    }

    
    /**
     * Serves the bateched items through FIFO. The test class may generate
     * additional replies, which are ordered after the primary one.
     *
     * @param m XNet message instance
     * @return the current reply
     */
    @Override
    protected XNetReply generateReply(XNetMessage m) {
        insertAdditionalReplies();
        if (m == null) {
            if (replyBuffer.isEmpty()) {
                return null;
            }
            XNetReply r = replyBuffer.remove(0);
            maybeWaitBeforeReply(r);
            System.err.println("Returning reply: " + r + " ... " + r.toMonitorString());
            return captureReply(r);
        }
        XNetReply reply = super.generateReply(m);
        if (isPrimaryReply(m)) {
            reply = new XNetTurnoutMonitoringTest.PrimaryXNetReply(reply);
        }
        if (replyBuffer.isEmpty()) {
            insertAdditionalReplies();
            maybeWaitBeforeReply(reply);
            System.err.println("Returning reply: " + reply + " ... " + reply.toMonitorString());
            return captureReply(reply);
        }
        replyBuffer.add(reply);
        insertAdditionalReplies();
        XNetReply r = replyBuffer.remove(0);
        maybeWaitBeforeReply(reply);
        return captureReply(r);
    }

    protected boolean isPrimaryReply(XNetMessage msg) {
        return msg.getElement(0) == XNetConstants.ACC_OPER_REQ;
    }

    protected boolean lastAccessoryState;

    @Override
    protected XNetReply accReqReply(XNetMessage m) {
        int baseaddress = m.getElement(1);
        int subaddress = (m.getElement(2) & 0x06) >> 1;
        int address = (baseaddress * 4) + subaddress + 1;
        int output = m.getElement(2) & 0x01;
        boolean on = ((m.getElement(2) & 0x08)) == 0x08;
        lastAccessoryState = accessoryState.get(address);
        if (on) {
            accessoryState.set(address, output != 0);
        }
        System.err.println(m + " ..." + m.toMonitorString());
        return generateAccRequestReply(address, output, on);
    }

    protected abstract XNetReply generateAccRequestReply(int address, int output, boolean state);

    protected XNetReply accInfoReply(int dccTurnoutAddress) {
        dccTurnoutAddress--;
        int baseAddress = dccTurnoutAddress / 4;
        boolean upperNibble = dccTurnoutAddress % 4 >= 2;
        return accInfoReply(true, baseAddress, upperNibble);
    }

    @Override
    protected XNetReply accInfoReply(XNetMessage m) {
        boolean nibble = (m.getElement(2) & 0x01) == 0x01;
        int ba = m.getElement(1);
        return accInfoReply(false, ba, nibble);
    }

    /**
     * Return the turnout feedback type.
     * <ul>
     * <li>0x00 - turnout without feedback, ie DR5000
     * <li>0x01 - turnout with feedback, ie NanoX
     * <li>0x10 - feedback module
     * </ul>
     * @return
     */
    protected int getTurnoutFeedbackType() {
        return 0x01;
    }
    
    protected int getAccessoryStateBits(int a) {
        boolean state = accessoryState.get(a);
        int zbits = state ? 0b10 : 0b01;
        return zbits;
    }

    protected XNetReply accInfoReply(boolean broadcast, int baseAddress, boolean nibble) {
        XNetReply r = new XNetReply();
        r.setOpCode(broadcast ? XNetConstants.ACC_INFO_RESPONSE : XNetConstants.ACC_INFO_RESPONSE);
        r.setElement(1, baseAddress);
        int nibbleVal = 0;
        int a = baseAddress * 4 + 1;
        if (nibble) {
            a += 2;
        }
        int zbits = getAccessoryStateBits(a++);
        nibbleVal |= zbits;
        zbits = getAccessoryStateBits(a++);
        nibbleVal |= (zbits << 2);
        r.setElement(2, 0 << 7 | // turnout movement completed
        getTurnoutFeedbackType() << 5 | // two bits: accessory without feedback
        (nibble ? 1 : 0) << 4 | // upper / lower nibble
        nibbleVal & 0x0f);
        r.setElement(3, 0);
        r.setParity();
        return r;
    }
    
    static class NanoXGenLi extends XNetTestSimulator {
        protected XNetReply generateAccRequestReply(int address, int output, boolean state) {
            if (state) {
                return accInfoReply(address);
            } else {
                return okReply();
            }
        }
    }
    
    static class DR5000 extends XNetTestSimulator {
        @Override
        protected XNetReply generateAccRequestReply(int address, int output, boolean state) {
            if (state) {
                addReply(accInfoReply(address));
                return okReply();
            } else {
                return okReply();
            }
        }

        @Override
        protected int getTurnoutFeedbackType() {
            return 0;
        }
    }
    
    static class LZV100 extends XNetTestSimulator {

        @Override
        protected int getAccessoryStateBits(int a) {
            if (accessoryOperated.get(a)) {
                return super.getAccessoryStateBits(a);
            } else {
                // not operated
                return 0x00;
            }
        }

        @Override
        protected XNetReply generateAccRequestReply(int address, int output, boolean state) {
            XNetReply r;
            
            if (state) {
                if (accessoryOperated.get(address) && lastAccessoryState == (output != 0)) {
                    // just OK, the accessory is in the same state.
                    return okReply();
                } else {
                    accessoryOperated.set(address);
                    r = accInfoReply(address);
                    addReply(okReply());
                }
            } else {
                accessoryOperated.set(address);
                r = okReply();
            }
            return r;
        }
    }
}
