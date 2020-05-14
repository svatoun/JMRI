/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenzplus;

import static jmri.jmrix.AbstractMRTrafficController.AUTORETRYSTATE;
import static jmri.jmrix.AbstractMRTrafficController.IDLESTATE;
import static jmri.jmrix.AbstractMRTrafficController.NORMALMODE;
import static jmri.jmrix.AbstractMRTrafficController.NOTIFIEDSTATE;
import static jmri.jmrix.AbstractMRTrafficController.OKSENDMSGSTATE;
import static jmri.jmrix.AbstractMRTrafficController.POLLSTATE;
import static jmri.jmrix.AbstractMRTrafficController.PROGRAMINGMODE;
import static jmri.jmrix.AbstractMRTrafficController.WAITMSGREPLYSTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINNORMMODESTATE;
import static jmri.jmrix.AbstractMRTrafficController.WAITREPLYINPROGMODESTATE;
import jmri.jmrix.lenz.XNetListener;

/**
 * The memento exports internal state of TrafficController to
 * be manipulated without changing the "master state" in the Controller.
 * The state can be saved using {@link ReplyDispatcher#snapshot}, manipulated in protected variables
 * and finally put back by {@link #commit}.
 * <p>
 * It allows to take a state snapshot when the first expected message arrives
 * after TrafficController sends out a command. Then the state and mode
 * can be changed and held in the Memento until the command is finished.
 * More messages may need to be received AND processed before the command finishes.
 * The state changes remain transient and invisible to TrafficController in
 * the meantime.
 * <p>
 * When the Memento is {@link ReplyDispatcher#commit}ted, state and mode change and the
 * transmit thread wakes up.
 */
public class StateMemento {
    protected int retransmitCount;
    protected int origCurrentState;
    protected int mCurrentState;
    protected int mCurrentMode;
    protected XNetPlusMessage lastMessage;
    protected XNetListener mLastSender;

    public static String stateName(int state) {
        switch (state) {
            case IDLESTATE: return "idle";
            case NOTIFIEDSTATE: return "notified";
            case WAITMSGREPLYSTATE: return "waitMsg";
            case WAITREPLYINPROGMODESTATE: return "waitReply-ProgMode";
            case WAITREPLYINNORMMODESTATE: return "waitReply-NormMode";
            case OKSENDMSGSTATE: return "okSend";
            case AUTORETRYSTATE: return "autoretry";
            case POLLSTATE: return "poll";
            default: return "unknown(" + state + ")";
        }
    }

    public static String modeName(int mode) {
        switch (mode) {
            case NORMALMODE: return "normal";
            case PROGRAMINGMODE: return "pgm";
            default: return "unknown(" + mode + ")";
        }
    }
    
    protected void copyState(StateMemento to, StateMemento from) {
        to.lastMessage = from.lastMessage;
        to.mCurrentMode = from.mCurrentMode;
        to.mCurrentState = from.mCurrentState;
        to.mLastSender = from.mLastSender;
        to.retransmitCount = from.retransmitCount;
    }
}
