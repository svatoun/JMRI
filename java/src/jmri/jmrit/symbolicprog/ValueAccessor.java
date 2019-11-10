/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import javax.swing.Icon;

/**
 *
 * @author sdedic
 */
public interface ValueAccessor<E, V> {
    public boolean accept(Object item);
    public Icon getIcon(E item);
    public String getDescription(E item);
    public V getValue(E item);
    public String getDisplayName(E item);
}
