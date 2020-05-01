package jmri.jmrix.lenz;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import jmri.CommandStation;
import jmri.GlobalProgrammerManager;
import jmri.InstanceManager;
import jmri.PowerManager;
import jmri.ThrottleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An experimental alternative to XNetInitializationManager. It initializes the
 * system in two phases: {@link #defineServices()} defines {@link Supplier}s for
 * individual services to be registered. Since many services do actions in their 
 * constructors, like publishing themselves (yuck!) into system registries/managers,
 * the can't be instantiated unless needed.
 * <p>
 * A subclass can override {@link #defineServices} and do post-processing: replace
 * add or delete a service.
 * <p>
 * All services are then registered, if they are (still) defined by {@link #registerServices}.
 * <p>
 * This design allows to inherit this Manager and customize what's necessary. If the
 * Manager did not run actions in its constructor, it could be possible to just 
 * parametrize the instance using {@link #register} - no inheritance necessary.
 * <p>
 * This code allows reduction of LI* variant from 140 lines to 20, and reuse in
 * LenzPlus package.
 * 
 */
public class XNetInitializationManagerLazy extends AbstractXNetInitializationManager {
    // cannot initialize + final directly, is used by overridable init() from
    // the superclass constructor.
    private Map<Class<?>, Supplier<?>>  factories;
    
    private float csSoftwareVersion;
    private int csType;
    
    public XNetInitializationManagerLazy(XNetSystemConnectionMemo memo) {
        super(memo);
    }
    
    protected <T> void register(Class<T> clazz, Supplier<T> supplier) {
        factories.put(clazz, supplier);
    }
    
    protected <T> void delete(Class<T> clazz) {
        factories.remove(clazz);
    }
    
    protected <T> Optional<T> optService(Class<T> clazz) {
        Supplier<T> s = (Supplier<T>)factories.get(clazz);
        if (s == null) {
            return Optional.empty();
        } else {
            return Optional.ofNullable(s.get());
        }
    }
    
    public float getCsSoftwareVersion() {
        return csSoftwareVersion;
    }

    public int getCsType() {
        return csType;
    }
    
    protected XNetProgrammerManager createProgrammerManager() {
        return new XNetProgrammerManager(new XNetProgrammer(systemMemo.getXNetTrafficController()), systemMemo);
    }

    /**
     * Registers individual services, if they are defined.
     * The following services are registered:
     * <ul>
     * <li>{@link PowerManager}
     * <li>{@link ThrottleManager}
     * <li>{@link XNetProgrammerManager}
     * <li>{@link CommandStation}
     * <li>{@link XNetConsistManager}
     * <li>{@link XNetTurnoutManager}
     * <li>{@link XNetLightManager}
     * <li>{@link XNetSensorManager}
     * <ul>
     * If a service has no register {@link Supplier}, it will be skipped.
     */
    protected void registerServices() {
        if ((getCsSoftwareVersion() >= 0) && (getCsSoftwareVersion() < 3.0)) {
            return;
        }
        optService(PowerManager.class).ifPresent((m) -> jmri.InstanceManager.store(m, PowerManager.class));
        optService(ThrottleManager.class).ifPresent(InstanceManager::setThrottleManager);
        optService(XNetProgrammerManager.class).ifPresent((m) -> {
            systemMemo.setProgrammerManager(m);
            if (systemMemo.getProgrammerManager().isAddressedModePossible()) {
                jmri.InstanceManager.store(systemMemo.getProgrammerManager(), jmri.AddressedProgrammerManager.class);
            }
            if (systemMemo.getProgrammerManager().isGlobalProgrammerAvailable()) {
                jmri.InstanceManager.store(systemMemo.getProgrammerManager(), GlobalProgrammerManager.class);
            }
        });
        /* the "raw" Command Station only works on systems that support
         Ops Mode Programming */
        optService(CommandStation.class).ifPresent((c) -> {
            systemMemo.setCommandStation(c);
            jmri.InstanceManager.store(c, jmri.CommandStation.class);
        });
        optService(XNetConsistManager.class).ifPresent(systemMemo::setConsistManager);
        optService(XNetTurnoutManager.class).ifPresent((t) -> {
            systemMemo.setTurnoutManager(t);
            jmri.InstanceManager.setTurnoutManager(t);
        });
        optService(XNetLightManager.class).ifPresent((l) -> {
            systemMemo.setLightManager(l);
            jmri.InstanceManager.setLightManager(l);
        });
        optService(XNetSensorManager.class).ifPresent((s) -> {
            systemMemo.setSensorManager(s);
            jmri.InstanceManager.setSensorManager(s);
        });
    }
    
    protected void defineFullServices() {
        register(XNetProgrammerManager.class, this::createProgrammerManager);
        register(PowerManager.class, systemMemo::getPowerManager);
        register(ThrottleManager.class, systemMemo::getThrottleManager);
        register(CommandStation.class, systemMemo.getXNetTrafficController()::getCommandStation);
        register(XNetSensorManager.class, () -> new XNetSensorManager(systemMemo));
        defineCompactServices();
    }
    
    protected void defineCompactServices() {
        register(XNetTurnoutManager.class, () -> new XNetTurnoutManager(systemMemo));
        register(XNetLightManager.class, () -> new XNetLightManager(systemMemo));
        register(XNetConsistManager.class, () -> new XNetConsistManager(systemMemo));
    }
    
    /**
     * Defines services. This method only defines services using {@link #register} and
     * {@link #delete}. Overriden versions may redefine or delete registered services.
     * The services will be then registered using {@link #registerServices()}
     */
    protected void defineServices() {
        float CSSoftwareVersion = getCsSoftwareVersion();
        int CSType = getCsType();
        
        if (CSSoftwareVersion < 0) {
            log.warn("Command Station disconnected, or powered down assuming LZ100/LZV100 V3.x");
            defineFullServices();
            return;
        }
        if (CSSoftwareVersion < 3.0) {
            log.error("Command Station does not support XpressNet Version 3 Command Set");
            return;
        } 
        boolean noConsist = false;
        switch (CSType) {
            case 0x00: log.debug("Command Station is LZ100/LZV100"); break;
            case 0x01: log.debug("Command Station is LH200"); return;
            case 0x02: log.debug("Command Station is Compact/Commander/Other");
                defineCompactServices(); 
                return;
            case 0x04: log.debug("Command Station is LokMaus II");
                noConsist = true;
                break;
            case 0x10: log.debug("Command Station is multiMaus");
                noConsist = true;
                break;
            default:
                log.debug("Command Station is Unknown type");
                break;
        }
        defineFullServices();
        if (noConsist) {
            delete(XNetConsistManager.class);
        }
    }

    @Override
    protected final void init() {
        if (factories == null) {
            factories = new HashMap<>();
        }
        if (log.isDebugEnabled()) {
            log.debug("Init called");
        }
        csSoftwareVersion = systemMemo.getXNetTrafficController()
                .getCommandStation()
                .getCommandStationSoftwareVersion();
        csType = systemMemo.getXNetTrafficController()
                .getCommandStation()
                .getCommandStationType();
        
        defineServices();
        
        registerServices();
        
        if (log.isDebugEnabled()) {
            log.debug("XpressNet Initialization Complete");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(XNetInitializationManagerLazy.class);

}
