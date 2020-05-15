/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenzplus.comm.CommandState.Phase;

/**
 *
 * @author sdedic
 */
public class XNetPlusCommAccess {
    public static boolean toPhase(CommandState s, Phase p) {
        return s.toPhase(p);
    }
    
    public static void attachHandler(CommandState s, CommandHandler h) {
        s.attachHandler(h);
    }
    
    public static void attachQueue(CommandHandler h, CommandService q) {
        h.attachQueue(q);
    }

    public static boolean advance(CommandHandler h) {
        return h.advance();
    }
}
