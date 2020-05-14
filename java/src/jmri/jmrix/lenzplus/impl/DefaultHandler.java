/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.comm.CommandHandler;
import jmri.jmrix.lenzplus.comm.CommandState;
import jmri.jmrix.lenzplus.XNetPlusMessage;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author sdedic
 */
public class DefaultHandler extends CommandHandler {
    
    /**
     * Specific replies for individual commands. Positive match: if a reply
     * is not found in this map, it's most probably unexpected.
     */
    private static final Map<Integer, Integer> ACEPTABLE_RESPONSES = new HashMap<>();
    private static final Map<Integer, Set<Integer>> ALTERNATE_RESPONSES = new HashMap<>();
    private static final Set<Integer> OK_UNEXPECTED = new HashSet<>();
    
    private static void addAcceptableReply(int command, int response) {
        ACEPTABLE_RESPONSES.put(command, response);
    }

    private static void addReplyAlternative(int command, int response) {
        ALTERNATE_RESPONSES.computeIfAbsent(command, (c) -> new HashSet<>()).add(response);
    }

    private static void setReplyAlternative(int command, int... responses) {
        addReplyAlternative(command, responses);
        addUnexpectedOK(command);
    }
    
    private static void addReplyAlternative(int command, int... responses) {
        for (int a : responses) {
            addReplyAlternative(command, a);
        }
    }

    private static void addExclusiveReply(int command, int response) {
        ACEPTABLE_RESPONSES.put(command, response);
        addUnexpectedOK(command);
    }
    
    private static void addUnexpectedOK(int command) {
        OK_UNEXPECTED.add(command);
    }
    
    // Default configuration
    static {
        addExclusiveReply(0x21_21, 0x63_21); // software version 
        addExclusiveReply(0x21_24, 0x62_22); // status
        addExclusiveReply(0x42, 0x42);     // accessory info request
        
        setReplyAlternative(0xE3_00, 
                0xE4,  // Normal
                0xE5,  // Part of multi-unit in command station
                0xE2,  // Multi-unit address
                0xE6  // Double-header
        );

        addExclusiveReply(0xE3_07, 0xE3_50);    // func status
        
        // Address inquiry member of a Multi-unit request
        setReplyAlternative(0xE4_01,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );
        setReplyAlternative(0xE4_02,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );
        
        // Address inquiry Multi-unit request
        setReplyAlternative(0xE2_03,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );
        setReplyAlternative(0xE2_04,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );
        
        // Address inquiry locomotive at command station stack request
        setReplyAlternative(0xE3_05,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );
        setReplyAlternative(0xE3_06,
            0xE3_30, 0xE3_31, 0xE3_32, 0xE3_33, 0xE3_34
        );

        // resume operations request -> normal operations resumed, permit OK
        addReplyAlternative(0x21_81, 0x60_01, 0x61_00);
        
        // stop operations request -> track power off, permit OK
        addAcceptableReply(0x21_80, 0x60_00);
        addAcceptableReply(0x80_80, 0x81_00);
        
    }

    public DefaultHandler(CommandState commandMessage, XNetListener target) {
        super(commandMessage, target);
    }
    
    @Override
    public boolean acceptsReply(XNetPlusMessage msg, XNetPlusReply reply) {
        int oneByte = msg.getElement(0);
        int twoByte = (oneByte << 8) | msg.getElement(1);
        
        int replyOne = reply.getElement(0);
        int replyTwo = (replyOne << 8) | reply.getElement(1);
        
        if (reply.isOkMessage()) {
            // Only reject OKs in specific situations.
            return !(OK_UNEXPECTED.contains(twoByte) || OK_UNEXPECTED.contains(oneByte));
        }
        Integer a = ACEPTABLE_RESPONSES.get(twoByte);
        if (a == null) {
            ACEPTABLE_RESPONSES.get(oneByte);
        }
        if (a != null) {
            return a == replyOne || a == replyTwo;
        }
        
        Set<Integer> alts = ALTERNATE_RESPONSES.get(oneByte);
        if (alts == null) {
            alts = ALTERNATE_RESPONSES.get(twoByte);
        }
        if (alts != null) {
            return alts.contains(replyOne) || alts.contains(replyTwo);
        }
        
        // default: accept anything non-broadcasting
        return !reply.isBroadcast();
    }
}
