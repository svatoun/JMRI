package jmri.jmrix.openlcb;

import java.util.TimerTask;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.OverridingMethodsMustInvokeSuper;

import jmri.NamedBean;
import jmri.Sensor;
import jmri.implementation.AbstractSensor;
import jmri.jmrix.can.CanSystemConnectionMemo;

import org.openlcb.EventID;
import org.openlcb.OlcbInterface;
import org.openlcb.implementations.BitProducerConsumer;
import org.openlcb.implementations.EventTable;
import org.openlcb.implementations.VersionedValueListener;

/**
 * Extend jmri.AbstractSensor for OpenLCB controls.
 *
 * @author Bob Jacobsen Copyright (C) 2008, 2010, 2011
 */
public final class OlcbSensor extends AbstractSensor {

    static final int ON_TIME = 500; // time that sensor is active after being tripped

    OlcbAddress addrActive;    // go to active state
    OlcbAddress addrInactive;  // go to inactive state
    private final OlcbInterface iface;
    private final CanSystemConnectionMemo memo;

    VersionedValueListener<Boolean> sensorListener;
    BitProducerConsumer pc;
    EventTable.EventTableEntryHolder activeEventTableEntryHolder = null;
    EventTable.EventTableEntryHolder inactiveEventTableEntryHolder = null;
    private static final boolean DEFAULT_IS_AUTHORITATIVE = true;
    private static final boolean DEFAULT_LISTEN = true;
    private static final int PC_DEFAULT_FLAGS = BitProducerConsumer.DEFAULT_FLAGS &
            (~BitProducerConsumer.LISTEN_INVALID_STATE);

    private TimerTask timerTask;

    public OlcbSensor(String prefix, String address, CanSystemConnectionMemo memo) {
        super(prefix + "S" + address);
        this.memo = memo;
        if (memo != null) { // greatly simplify testing
            this.iface = memo.get(OlcbInterface.class);
        } else {
            this.iface = null;
        }
        init(address);
    }

    /**
     * Common initialization for both constructors.
     * <p>
     *
     */
    private void init(String address) {
        // build local addresses
        OlcbAddress a = new OlcbAddress(address, memo);
        OlcbAddress[] v = a.split(memo);
        if (v == null) {
            log.error("Did not find usable system name: {}", address);
            return;
        }
        switch (v.length) {
            case 1:
                // momentary sensor
                addrActive = v[0];
                addrInactive = null;
                break;
            case 2:
                addrActive = v[0];
                addrInactive = v[1];
                break;
            default:
                log.error("Can't parse OpenLCB Sensor system name: {}", address);
        }

    }

    /**
     * Helper function that will be invoked after construction once the properties have been
     * loaded. Used specifically for preventing double initialization when loading sensors from
     * XML.
     */
    void finishLoad() {
        int flags = PC_DEFAULT_FLAGS;
        flags = OlcbUtils.overridePCFlagsFromProperties(this, flags);
        log.debug("Sensor Flags: default {} overridden {} listen bit {}", PC_DEFAULT_FLAGS, flags,
                    BitProducerConsumer.LISTEN_EVENT_IDENTIFIED);
        disposePc();
        if (addrInactive == null) {
            pc = new BitProducerConsumer(iface, addrActive.toEventID(), BitProducerConsumer.nullEvent, flags);

            sensorListener = new VersionedValueListener<Boolean>(pc.getValue()) {
                @Override
                public void update(Boolean value) {
                    setOwnState(value ? Sensor.ACTIVE : Sensor.INACTIVE);
                    if (value) {
                        setTimeout();
                    }
                }
            };
        } else {
            pc = new BitProducerConsumer(iface, addrActive.toEventID(),
                    addrInactive.toEventID(), flags);
            sensorListener = new VersionedValueListener<Boolean>(pc.getValue()) {
                @Override
                public void update(Boolean value) {
                    setOwnState(value ? Sensor.ACTIVE : Sensor.INACTIVE);
                }
            };
        }
        activeEventTableEntryHolder = iface.getEventTable().addEvent(addrActive.toEventID(), getEventName(true));
        if (addrInactive != null) {
            inactiveEventTableEntryHolder = iface.getEventTable().addEvent(addrInactive.toEventID(), getEventName(false));
        }
    }

    /**
     * Computes the display name of a given event to be entered into the Event Table.
     * @param isActive true for sensor active, false for inactive.
     * @return user-visible string to represent this event.
     */
    public String getEventName(boolean isActive) {
        String name = getUserName();
        if (name == null) name = mSystemName;
        String msgName = isActive ? "SensorActiveEventName": "SensorInactiveEventName";
        return Bundle.getMessage(msgName, name);
    }

    public EventID getEventID(boolean isActive) {
        if (isActive) return addrActive.toEventID();
        else {
            if (addrInactive != null) {
                return addrInactive.toEventID();
            } else {
                return null;
            }
        }
    }

    @Override
    @CheckReturnValue
    @Nonnull
    public String getRecommendedToolTip() {
        // Some events are not pairs
        if (addrInactive == null) {
            return addrActive.toDottedString();
        }
        return addrActive.toDottedString()+";"+addrInactive.toDottedString();
    }

    /**
     * Updates event table entries when the user name changes.
     * @param s new user name
     * @throws NamedBean.BadUserNameException see {@link NamedBean}
     */
    @Override
    @OverridingMethodsMustInvokeSuper
    public void setUserName(String s) throws NamedBean.BadUserNameException {
        super.setUserName(s);
        if (activeEventTableEntryHolder != null) {
            activeEventTableEntryHolder.getEntry().updateDescription(getEventName(true));
        }
        if (inactiveEventTableEntryHolder != null) {
            inactiveEventTableEntryHolder.getEntry().updateDescription(getEventName(false));
        }
    }

    /**
     * Request an update on status by sending an OpenLCB message.
     */
    @Override
    public void requestUpdateFromLayout() {
        if (pc != null) {
            pc.resetToDefault();
            pc.sendQuery();
        }
    }

    /**
     * User request to set the state, which means that we broadcast that to all
     * listeners by putting it out on CBUS. In turn, the code in this class
     * should use setOwnState to handle internal sets and bean notifies.
     *
     */
    @Override
    public void setKnownState(int s) {
        if (s == Sensor.ACTIVE) {
            sensorListener.setFromOwnerWithForceNotify(true);
            if (addrInactive == null) {
                setTimeout();
            }
        } else if (s == Sensor.INACTIVE) {
            sensorListener.setFromOwnerWithForceNotify(false);
        } else if (s == Sensor.UNKNOWN) {
            if (pc != null) {
                pc.resetToDefault();
            }
        }
        setOwnState(s);
    }

    /**
     * Have sensor return to inactive after delay, used if no inactive event was
     * specified
     */
    void setTimeout() {
        timerTask = new java.util.TimerTask() {
            @Override
            public void run() {
                timerTask = null;
                jmri.util.ThreadingUtil.runOnGUI(() -> setKnownState(Sensor.INACTIVE));
            }
        };
        jmri.util.TimerUtil.schedule(timerTask, ON_TIME);
    }

    /**
     * Changes how the sensor reacts to inquire state events. With authoritative == false the
     * state will always be reported as UNKNOWN to the layout when queried.
     *
     * @param authoritative whether we should respond true state or unknown to the layout event
     *                      state inquiries.
     */
    public void setAuthoritative(boolean authoritative) {
        boolean recreate = (authoritative != isAuthoritative()) && (pc != null);
        setProperty(OlcbUtils.PROPERTY_IS_AUTHORITATIVE, authoritative);
        if (recreate) {
            finishLoad();
        }
    }

    /**
     * @return whether this producer/consumer is enabled to return state to the layout upon queries.
     */
    public boolean isAuthoritative() {
        Boolean value = (Boolean) getProperty(OlcbUtils.PROPERTY_IS_AUTHORITATIVE);
        if (value != null) {
            return value;
        }
        return DEFAULT_IS_AUTHORITATIVE;
    }

    @Override
    public void setProperty(@Nonnull String key, Object value) {
        Object old = getProperty(key);
        super.setProperty(key, value);
        if (value.equals(old)) return;
        if (pc == null) return;
        finishLoad();
    }

    /**
     * @return whether this producer/consumer is always listening to state declaration messages.
     */
    public boolean isListeningToStateMessages() {
        Boolean value = (Boolean) getProperty(OlcbUtils.PROPERTY_LISTEN);
        if (value != null) {
            return value;
        }
        return DEFAULT_LISTEN;
    }

    /**
     * Changes how the sensor reacts to state declaration messages. With listen == true state
     * declarations will update local state at all times. With listen == false state declarations
     * will update local state only if local state is unknown.
     *
     * @param listen whether we should always listen to state declaration messages.
     */
    public void setListeningToStateMessages(boolean listen) {
        boolean recreate = (listen != isListeningToStateMessages()) && (pc != null);
        setProperty(OlcbUtils.PROPERTY_LISTEN, listen);
        if (recreate) {
            finishLoad();
        }
    }

    /*
     * since the events that drive a sensor can be whichever state a user
     * wants, the order of the event pair determines what is the 'active' state
     */
    @Override
    public boolean canInvert() {
        return false;
    }

    @Override
    public void dispose() {
        disposePc();
        if (timerTask!=null) timerTask.cancel();
        super.dispose();
    }

    private void disposePc() {
        if (sensorListener != null) {
            sensorListener.release();
            sensorListener = null;
        }
        if (pc != null) {
            pc.release();
            pc = null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * Sorts by decoded EventID(s)
     */
    @CheckReturnValue
    @Override
    public int compareSystemNameSuffix(@Nonnull String suffix1, @Nonnull String suffix2, @Nonnull jmri.NamedBean n) {
        return OlcbAddress.compareSystemNameSuffix(suffix1, suffix2, memo);
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OlcbSensor.class);

}
