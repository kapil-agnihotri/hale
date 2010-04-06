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
package eu.esdihumboldt.hale.rcp.wizards.functions.core;


import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.wizard.Wizard;

import eu.esdihumboldt.cst.align.ICell;
import eu.esdihumboldt.cst.align.ext.IParameter;
import eu.esdihumboldt.cst.corefunctions.ConcatenationOfAttributesFunction;
import eu.esdihumboldt.cst.corefunctions.OrdinatesToPointFunction;
import eu.esdihumboldt.goml.oml.ext.Parameter;
import eu.esdihumboldt.goml.oml.ext.Transformation;
import eu.esdihumboldt.goml.omwg.ComposedProperty;
import eu.esdihumboldt.goml.rdf.Resource;
import eu.esdihumboldt.hale.rcp.wizards.functions.AbstractSingleComposedCellWizard;
import eu.esdihumboldt.hale.rcp.wizards.functions.AlignmentInfo;
import eu.esdihumboldt.hale.rcp.wizards.functions.core.geometric.OrdinatesPointWizardPage;


/**
 * TODO Typedescription
 * @author Stefan Gessner
 * @partner 01 / Fraunhofer Institute for Computer Graphics Research
 * @version $Id$ 
 */
public class ConcatenationOfAttributesWizard extends AbstractSingleComposedCellWizard {

	private ConcatenationOfAttributesWizardPage mainPage;
	
	public ConcatenationOfAttributesWizard(AlignmentInfo selection) {
		super(selection);
	}

	@Override
	protected void init() {
		this.mainPage = new ConcatenationOfAttributesWizardPage("Concatenation Of Attributes Function");
		mainPage.setDescription("Concats two or more strings into one string.") ;
		super.setWindowTitle("Concatenation Of Attributes Function"); 
	}

	@Override
	public boolean performFinish() {
		
		ICell cell = super.getResultCell();
		
		Transformation t = new Transformation();
		t.setService(new Resource(ConcatenationOfAttributesFunction.class.getName()));
		List<IParameter> parameters = new ArrayList<IParameter>();
		parameters.add(new Parameter(
				ConcatenationOfAttributesFunction.SEPERATOR, 
				this.mainPage.getSeperatorText().getText()));
		
		String temp = "";
		for(String line : mainPage.getListViewer().getList().getItems() ){
			temp = temp+";"+line;
		}
		parameters.add(new Parameter(
				ConcatenationOfAttributesFunction.CONCATENATION, 
				temp
				));
		t.setParameters(parameters);
		((ComposedProperty)cell.getEntity1()).setTransformation(t);
		
		return true;
	}
	
	/**
	 * @see Wizard#addPages()
	 */
	@Override
	public void addPages() {
		super.addPages();
		
		addPage(mainPage);
	}

}
