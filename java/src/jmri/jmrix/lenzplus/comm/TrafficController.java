/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenz.XNetListener;
import jmri.jmrix.lenzplus.XNetPlusMessage;

/**
 * A reduced interface to {@link XNetTrafficController} necessary for
 * sending messages by individual {@link CommandHandler}s.
 * @author sdedic
 */
public interface TrafficController {
    public <T> T lookup(Class<T> service);
    public void sendMessageToDevice(XNetPlusMessage msg, XNetListener l);
}
