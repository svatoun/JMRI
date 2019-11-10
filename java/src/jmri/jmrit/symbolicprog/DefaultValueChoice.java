/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jmri.jmrit.symbolicprog;

import javax.swing.Icon;

/**
 * @author sdedic
 */
public class DefaultValueChoice<E> implements ValueChoice<E> {
    private E      value;
    private String displayName;
    private String description;
    private Icon   icon;
    private String helpID;

    public DefaultValueChoice(E value) {
        this.value = value;
    }

    @Override
    public E getValue() {
        return value;
    }

    public void setValue(E value) {
        this.value = value;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setToolTip(String toolTip) {
        this.description = toolTip;
    }

    @Override
    public Icon getIcon() {
        return icon;
    }

    public void setIcon(Icon icon) {
        this.icon = icon;
    }

    @Override
    public String getHelpURI() {
        return helpID;
    }

    public void setHelpID(String helpID) {
        this.helpID = helpID;
    }
}
