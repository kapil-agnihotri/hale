/*
 * HUMBOLDT: A Framework for Data Harmonisation and Service Integration.
 * EU Integrated Project #030962                 01.10.2006 - 30.09.2010
 * 
 * For more information on the project, please refer to the this web site:
 * http://www.esdi-humboldt.eu
 * 
 * LICENSE: For information on the license under which this program is 
 * available, please refer to http:/www.esdi-humboldt.eu/license.html#core
 * (c) the HUMBOLDT Consortium, 2007 to 2010.
 */
package com.onespatial.jrc.tns.oml_to_rif.model.rif.filter.terminal;

/**
 * Class that represents a leaf node in a predicate tree.
 * 
 * @author Simon Payne (Simon.Payne@1spatial.com) / 1Spatial Group Ltd.
 * @author Richard Sunderland (Richard.Sunderland@1spatial.com) / 1Spatial Group Ltd.
 */
public class LeafNode
{

    private String propertyName;
    private LiteralValue literalValue;

    /**
     * @return String
     */
    public String getPropertyName()
    {
        return propertyName;
    }

    /**
     * @param propertyName
     *            String
     */
    public void setPropertyName(String propertyName)
    {
        this.propertyName = propertyName;

    }

    /**
     * @return String
     */
    public LiteralValue getLiteralValue()
    {
        return literalValue;
    }

    /**
     * @param value
     *            String
     */
    public void setLiteralValue(LiteralValue value)
    {
        literalValue = value;
    }

    /**
     * @return boolean
     */
    public boolean isNumeric()
    {
        return literalValue.getValueClass() == Long.class
                || literalValue.getValueClass() == Double.class;
    }

}
