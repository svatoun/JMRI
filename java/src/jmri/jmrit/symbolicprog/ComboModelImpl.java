/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import java.util.HashMap;
import java.util.Map;
import javax.swing.DefaultComboBoxModel;

/**
 *
 * @author sdedic
 */
public class ComboModelImpl<E> extends DefaultComboBoxModel<E> implements DescriptiveComboModel<E>{
    private Map<Object, String>     descriptions = new HashMap<>();

    @Override
    public void addDescription(E item, String description) {
        descriptions.put(item, description);
    }

    @Override
    public void removeDescription(E item) {
        descriptions.remove(item);
    }

    @Override
    public String getDescription(E item) {
        return descriptions.get(item);
    }

    @Override
    public void removeElement(Object anObject) {
        removeDescription((E)anObject);
        super.removeElement(anObject);
    }
}
