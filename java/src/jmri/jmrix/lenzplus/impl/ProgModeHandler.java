/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import jmri.jmrix.lenz.XNetConstants;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.comm.ReplyOutcome;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author sdedic
 */
public class ProgModeHandler extends CommandHandler {
    private int broadcasts;
    
    public ProgModeHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage, target);
    }

    @Override
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        if (reply.isOkMessage()) {
            return true;
        }
        if (reply.getElement(0) == XNetConstants.CS_INFO) {
            if (msg.getElement(1) == 0x81) {
                return reply.getElement(1) == 0x01;
            } else {
                return reply.getElement(1) == 0x02;
            }
        }
        return false;
    }

    @Override
    public ReplyOutcome processed(CommandState msg, XNetPlusReply reply) {
        ReplyOutcome ro = new ReplyOutcome(msg, reply);
        if (reply.isBroadcast()) {
            broadcasts++;
        }
        if (msg.getOkReceived() > 0  && broadcasts > 0) {
            return ReplyOutcome.finished(msg, reply);
        }
        if (reply.isBroadcast() && msg.getOkReceived() == 0) {
            ro.setAdditionalReplyRequired(true);
        } else if (msg.getStateReceived() == 0) {
            ro.setAdditionalReplyRequired(true);
        }
        return ro;
    }
}
