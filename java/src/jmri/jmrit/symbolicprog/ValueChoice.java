/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import javax.swing.Icon;

/**
 * Represents a value with a description to display in the UI.
 * The choice can have display name, description icon and help context.
 * @author sdedic
 */
public interface ValueChoice<E> {
    /**
     * Text to be displayed. May contain HTML formatting.
     * @return 
     */
    String getDisplayName();

    /**
     * Help URI.
     * @return 
     */
    String getHelpURI();

    /**
     * Icon that represents the choice.
     * @return 
     */
    Icon getIcon();

    /**
     * More descriptive text, which will be displayed on-demand. For example 
     * in tooltips. May contain HTML formatting.
     * @return 
     */
    String getDescription();

    /**
     * The actual value.
     * @return 
     */
    E getValue();
    
    
    static final ValueAccessor<ValueChoice<Object>, Object> ACCESSOR = new Accessor<Object>();
    
    public static final class Accessor<E> implements ValueAccessor<ValueChoice<E>, E> {
        public boolean accept(Object item) {
            return item instanceof ValueChoice;
        }
        
        @Override
        public Icon getIcon(ValueChoice item) {
            return item.getIcon();
        }

        @Override
        public String getDescription(ValueChoice item) {
            return item.getDescription();
        }

        @Override
        public E getValue(ValueChoice<E> item) {
            return item.getValue();
        }

        @Override
        public String getDisplayName(ValueChoice<E> item) {
            return item.getDisplayName();
        }
    }
}
