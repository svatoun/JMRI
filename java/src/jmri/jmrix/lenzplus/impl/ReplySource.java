package jmri.jmrix.lenzplus.impl;

import java.io.IOException;
import jmri.jmrix.lenzplus.XNetPlusReply;

/**
 *
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface ReplySource {
    public default XNetPlusReply take() throws IOException { return takeWithTimeout(0); }
    public XNetPlusReply takeWithTimeout(int timeout) throws IOException;
    public boolean isActive();
    public void resetExpectedReply(XNetPlusReply reply);
}
