/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

/**
 * Represents a task posted to XPressNet. A task must be acknowledged
 * either explicitly (OK message from the XNet bridge), an appropriate
 * response message from the command station, or by a broadcast message.
 * The type of acknowledgment is task-specific.
 * 
 * @author sdedic
 */
public class XNetCommandTask<T extends XNetListener> {
    public enum State {
        IDLE,
        ARMED, COMMNANDSENT, ACK,
        OFFSENT, OFFDONE
    };
    
    private static final int REPLY_OK = -2;
    
    private final XNetMessage   command;
    private final T  target;
    private final int replyCode;
    private int broadcastsAccepted;

    public XNetCommandTask(XNetMessage command, T target, int replyCode) {
        this.command = command;
        this.target = target;
        this.replyCode = replyCode;
    }
    
    protected T getTarget() {
        return target;
    }
    
    public boolean accepts(XNetReply reply) {
        if (replyCode == REPLY_OK) {
            
        }
    }
    
    static class Turnout extends XNetCommandTask<XNetTurnout> {
        private final int originalState;
        private final int desiredState;
        
        public Turnout(XNetMessage command, XNetTurnout t, int originalState, int desiredState) {
            super(command, t, -1);
            this.originalState = originalState;
            this.desiredState = desiredState;
        }
    }
}
