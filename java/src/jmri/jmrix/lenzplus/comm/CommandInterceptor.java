package jmri.jmrix.lenzplus.comm;

import java.util.function.Consumer;
import jmri.jmrix.lenzplus.comm.CommandState.Phase;

/**
 * Participates during outgoing command sending. The Processor is called before 
 * any command is physically sent out. It may generate an additional command into
 * the command queue, or augment the current command's handler.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface CommandInterceptor {
    /**
     * Processes the outgoing command. The CommandState and handler may be
     * changed. The returned value will determine the fate of the command:
     * <ul>
     * <li>{@link Phase#QUEUED}: the command will return back to the head of the queue,
     * so it is subject to scheduling, blocking, etc.
     * <li>{@link Phase#BLOCKED}: the command becomes blocked. It must be unblocked
     * externally in that case, using {#link CommadnService#unblock}.
     * <li>{@link Phase#EXPIRED}: the command expires. This will effectively break
     * the command's {@link CommandHandler} and causes it to expire too, including any
     * of its queued commands.
     * </ul>
     * Any other Phase values are illegal, will throw an exception that will be logged.
     * The exception will not prevent other Processors from operation.
     * 
     * @return 
     */
    public Phase interceptCommand(CommandService service, CommandState s);
}
