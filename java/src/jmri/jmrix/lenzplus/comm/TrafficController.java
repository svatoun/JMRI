package jmri.jmrix.lenzplus.comm;

import jmri.jmrix.lenz.XNetTrafficController;
import org.openide.util.Lookup;

/**
 * A reduced interface to {@link XNetTrafficController} necessary for
 * sending messages by individual {@link CommandHandler}s.
 * 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
public interface TrafficController extends Lookup.Provider {
}
