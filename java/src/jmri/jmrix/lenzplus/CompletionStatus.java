package jmri.jmrix.lenzplus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Command completion status. Provides more detailed information to {@link XNetPlusResponseListener#completed}
 * callback.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public class CompletionStatus {
    private final XNetPlusMessage command;
    private List<XNetPlusReply> replies;
    private XNetPlusReply concurrentReply;
    private boolean success;

    public CompletionStatus(XNetPlusMessage command) {
        this.command = command;
    }
    
    public void addReply(XNetPlusReply item) {
        if (replies == null) {
            replies = Collections.singletonList(item);
            return;
        } else if (replies.size() == 1) {
            List<XNetPlusReply> r = new ArrayList<>(3);
            r.add(replies.get(0));
            this.replies = r;
        }
        replies.add(item);
    }
    
    public CompletionStatus success() {
        this.success = true;
        return this;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public boolean isTimeout() {
        return replies == null;
    }
    
    public XNetPlusMessage getCommand() {
        return command;
    }

    public XNetPlusReply getConcurrentReply() {
        return concurrentReply;
    }

    public void setConcurrentReply(XNetPlusReply concurrentReply) {
        this.concurrentReply = concurrentReply;
    }
    
    public XNetPlusReply getReply() {
        if (replies == null) {
            return null;
        }
        XNetPlusReply candidate = null;
        for (int i = replies.size() -1; i > 0; i--) {
            XNetPlusReply r= replies.get(i);
            if (r.isRetransmittableErrorMsg()) {
                continue;
            } else if (r.isUnsupportedError()) {
                return r;
            }
            if (r.isOkMessage()) {
                candidate = r;
            } else {
                return r;
            }
        }
        return candidate != null ? candidate : replies.get(0);
    }

    public List<XNetPlusReply> getAllReplies() {
        if (replies == null) {
            return Collections.emptyList();
        } else {
            return replies;
        }
    }
    
    public String toString() {
        String s = command.toString() + 
                ", success: " + success + 
                ", timeout: " + isTimeout() +
                ", concurrent: " + getConcurrentReply();
        if (replies != null) {
            s += ", replies: " + Arrays.asList(replies).toString();
        }
        return s;
    }
}
