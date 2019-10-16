/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import javax.swing.MutableComboBoxModel;

/**
 *
 * @author sdedic
 */
public interface DescriptiveComboModel<E> {
    public void addDescription(E item, String description);
    public void removeDescription(E item);
    public String getDescription(E item);
}
