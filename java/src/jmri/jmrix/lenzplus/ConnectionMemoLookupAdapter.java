package jmri.jmrix.lenzplus;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import jmri.jmrix.SystemConnectionMemo;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;

/**
 * Adapts ConnectionMemo to Lookup interface. The implementation is simple, assumes fixed
 * set of services from the ConnectionMemo. 
 * @author svatopluk.dedic@gmail.com Copyright (c) 2020
 */
final class ConnectionMemoLookupAdapter extends AbstractLookup {
    private final SystemConnectionMemo memo;
    private final Set<Class<?>> classes = Collections.synchronizedSet(new HashSet<>());
    private final InstanceContent ic;
    
    public ConnectionMemoLookupAdapter(SystemConnectionMemo memo) {
        this(memo, new InstanceContent());
    }
    
    private ConnectionMemoLookupAdapter(SystemConnectionMemo memo, InstanceContent ic) {
        super(ic);
        this.memo = memo;
        this.ic = ic;
    }

    @Override
    protected void beforeLookup(Template<?> template) {
        Class<?> serviceClass = template.getType();
        if (!classes.add(serviceClass)) {
            return;
        }
        if (!memo.provides(serviceClass)) {
            return;
        }
        Object instance = memo.get(serviceClass);
        if (instance == null) {
            return;
        }
        ic.add(instance);
    }
}
