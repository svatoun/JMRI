/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

/**
 *
 * @author sdedic
 */
public interface XNetMessageSink extends XNetInterface {
    public void sendHighPriorityXNetMessage(XNetMessage msg, XNetListener target);
    public XNetFeedbackMessageCache getFeedbackMessageCache();
}
