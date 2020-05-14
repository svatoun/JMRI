/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus.impl;

import java.io.IOException;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author sdedic
 */
public interface ReplySource {
    public default XNetPlusReply take() throws IOException { return takeWithTimeout(0); }
    public XNetPlusReply takeWithTimeout(int timeout) throws IOException;
    public boolean isActive();
    public void resetExpectedReply(XNetPlusReply reply);
}
