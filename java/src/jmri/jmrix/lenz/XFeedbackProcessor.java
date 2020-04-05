/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrix.lenz;

import java.util.LinkedList;
import java.util.List;
import jmri.jmrix.lenz.XAction.Accessory;
import jmri.jmrix.lenz.XActionQueue.FeedbackState;

/**
 *
 * @author sdedic
 */
public class XFeedbackProcessor {
    
    /**
     * Processes a single feedback.
     */
    static class SingleFeedbackProcessor extends XActionQueue.FeedbackState {
        private final Helper helper;
        
        public SingleFeedbackProcessor(XActionQueue q, XNetReply reply) {
            super(q, reply);
            helper = new Helper(this);
        }

        @Override
        protected XNetMessage findConfirmedMessage() {
            Accessory conf = helper.findTransmittedToConfirm(true);
            getReply().setResponseTo(conf.getCommandMessage());
            return conf.getCommandMessage();
        }
        
        protected void execute() {
            
        }
    }
    
    static class MultiFeedbackProcessor extends XActionQueue.FeedbackState {
        
        public MultiFeedbackProcessor(XActionQueue q, XNetReply reply) {
            super(q, reply);
        }

        @Override
        protected XNetMessage findConfirmedMessage() {
            return null;
        }
        
        protected void execute() {
            if (!getReply().isFeedbackBroadcastMessage()) {
                return;
            }
            BatchHelper proc = new BatchHelper(this);
            proc.process();

            if (proc.getReportedList().isEmpty()) {
                return;
            }
            for (XNetListener l : proc.getReportedList()) {
                l.setMessageState(XNetTurnout.IDLE);
                l.message(getReply());
            }
            getReply().markConsumed();
        }
    }

    final static class BatchHelper {
        private final FeedbackState state;
        
        private final XNetReply feedback;
        
        private List<XNetListener>  reportList = new LinkedList<>();
        
        public BatchHelper(FeedbackState state) {
            this.state = state;
            this.feedback = state.getReply();
        }
        
        void process() {
            for (int i = 1; i < feedback.getNumDataElements(); i += 2) {
                Helper proc = new Helper(state, i);
                if (!proc.findFeedbacksToIgnore()) {
                    XNetTurnout tnt;
                    if (proc.shouldReportFeecback(true)) {
                        tnt = state.getQueue().getTurnout(proc.getOddAddress());
                        if (tnt != null) {
                            reportList.add(tnt);
                        }
                    }
                    if (proc.shouldReportFeecback(false)) {
                        tnt = state.getQueue().getTurnout(proc.getOddAddress() + 1);
                        if (tnt != null) {
                            reportList.add(tnt);
                        }
                    }
                }
            }
        }
        
        public List<XNetListener> getReportedList() {
            return reportList;
        }
    }
    /**
     * Helper class that builds up a state that should not
     * mess the Queue object between messages.
     */
    static final class Helper {
        private final XActionQueue.FeedbackState state;

        /**
         * The feedback reply
         */
        private final XNetReply feedback;
        
        /**
         * Odd (first) turnout address in the feedback info.
         */
        private final int oddAddr;
        
        /**
         * Reported state of the odd (first) turnout.
         */
        private final int oddState;
        
        /**
         * Reported state of the even (second) turnout.
         */
        private final int evenState;
        
        /**
         * True, if the odd state should be ignored.
         */
        private boolean oddIgnored;

        /**
         * True, if the even state should be ignored.
         */
        private boolean evenIgnored;
        
        /**
         * Should report and process odd state.
         */
        private boolean reportOdd;

        /**
         * Should report and process even state.
         */
        private boolean reportEven;
        
        public Helper(XActionQueue.FeedbackState state) {
            this(state, 1);
        }

        public Helper(FeedbackState state, int start) {
            this.state = state;
            this.feedback = state.getReply();

            oddAddr = feedback.getTurnoutMsgAddr(start);
            oddState = feedback.getTurnoutStatus(start, 1);
            evenState = feedback.getTurnoutStatus(start, 0);
        }
        
        /**
         * Finds a transmitted command, that can be confirmed
         * by this feedback.
         * @return accessory command
         */
        XAction.Accessory findTransmittedToConfirm(boolean first) {
            if (!feedback.isFeedbackMessage()) {
                return null;
            }
            List<XNetMessage> tr = state.getTransmitted();
            for (XNetMessage m : tr) {
                XAction cmd = m.getAction();
                if (!(cmd instanceof XAction.Accessory)) {
                    continue;
                }
                XAction.Accessory ac = (XAction.Accessory)cmd;
                if (first && !state.getQueue().acceptsFeedback(cmd, feedback)) {
                    continue;
                }
                if (ac.getLayoutId() == oddAddr) {
                    if (ac.getCommandedState() == oddState &&
                        state.getQueue().acceptsFeedback(ac, feedback)) {
                        if (ac.getPairedKnownState() == evenState) {
                            // the reported state is the same as it was at the time command was sent
                            // should not be reported anywhere as this feedback is just confirmation.
                            evenIgnored = true;
                        }
                        feedback.setResponseTo(ac.getCurrentMessage());
                        oddIgnored = true;
                        return ac;
                    }
                }
                if (ac.getLayoutId() == oddAddr + 1) {
                    if (ac.getCommandedState() == evenState && 
                        state.getQueue().acceptsFeedback(ac, feedback)) {
                        if (ac.getPairedKnownState() == oddState) {
                            oddIgnored = true;
                        }
                        feedback.setResponseTo(ac.getCurrentMessage());
                        evenIgnored = true;
                        return ac;
                    }
                }
            }
            return null;
        }
        
        private int getAccessoryState(int a) {
            return state.getQueue().getAccessoryState(a);
        }
        
        /**
         * Determines if parts can be ignored.
         * @return true, if whole feedback item can be ignored.
         */
        boolean findFeedbacksToIgnore() {
            for (XNetMessage m : state.getQueued()) {
                XAction cmd = m.getAction();
                if (!(cmd instanceof XAction.Accessory)) {
                    continue;
                }
                XAction.Accessory ac = (XAction.Accessory)cmd;
                if (ac.acceptsLayoutId(oddAddr)) {
                    // the reported state is the future commanded one.
                    // do not consider this an ACK, but do process the feedback.
                    // the other feedback must be the currently known state of the paired turnout
                    int os = getAccessoryState(oddAddr + 1);
                    if ((os == XNetTurnout.UNKNOWN) || os == evenState) {
                        if (ac.getCommandedState() == oddState) {
                            reportEven = true;
                        } else {
                            evenIgnored = true;
                        }
                        break;
                    }
                } 
                if (ac.acceptsLayoutId(oddAddr + 1)) {
                    int os = getAccessoryState(oddAddr);
                    if ((os == XNetTurnout.UNKNOWN) || os == evenState) {
                        if  (ac.getCommandedState() == evenState) {
                            reportOdd = true;
                        } else {
                            oddIgnored = true;
                        }
                        break;
                    }
                }
            }
            return oddIgnored && evenIgnored;
        }
        
        int getOddAddress() {
            return oddAddr;
        }
        
        int getEvenAddress() {
            return oddAddr + 1;
        }
        
        boolean shouldReportFeecback(boolean odd) {
            if (odd) {
                return reportOdd && !oddIgnored;
            } else {
                return reportEven && !evenIgnored;
            }
        }
    }
}
