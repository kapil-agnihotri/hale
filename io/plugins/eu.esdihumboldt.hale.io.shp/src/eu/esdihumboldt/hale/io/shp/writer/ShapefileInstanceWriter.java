package eu.esdihumboldt.hale.io.shp.writer;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.geotools.data.DefaultTransaction;
import org.geotools.data.Transaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import eu.esdihumboldt.hale.common.core.io.IOProviderConfigurationException;
import eu.esdihumboldt.hale.common.core.io.ProgressIndicator;
import eu.esdihumboldt.hale.common.core.io.report.IOReport;
import eu.esdihumboldt.hale.common.core.io.report.IOReporter;
import eu.esdihumboldt.hale.common.core.io.report.impl.IOMessageImpl;
import eu.esdihumboldt.hale.common.instance.geometry.GeometryFinder;
import eu.esdihumboldt.hale.common.instance.groovy.InstanceAccessor;
import eu.esdihumboldt.hale.common.instance.helper.DepthFirstInstanceTraverser;
import eu.esdihumboldt.hale.common.instance.helper.InstanceTraverser;
import eu.esdihumboldt.hale.common.instance.io.impl.AbstractGeoInstanceWriter;
import eu.esdihumboldt.hale.common.instance.model.Instance;
import eu.esdihumboldt.hale.common.instance.model.InstanceCollection;
import eu.esdihumboldt.hale.common.instance.model.ResourceIterator;
import eu.esdihumboldt.hale.common.schema.geometry.CRSDefinition;
import eu.esdihumboldt.hale.common.schema.geometry.GeometryProperty;
import eu.esdihumboldt.hale.common.schema.model.DefinitionUtil;
import eu.esdihumboldt.hale.common.schema.model.PropertyDefinition;
import eu.esdihumboldt.hale.common.schema.model.TypeDefinition;
import eu.esdihumboldt.hale.common.schema.model.constraint.type.AugmentedValueFlag;
import eu.esdihumboldt.hale.common.schema.model.constraint.type.Binding;
import eu.esdihumboldt.hale.common.schema.model.constraint.type.GeometryType;
import eu.esdihumboldt.hale.common.schema.model.constraint.type.HasValueFlag;
import eu.esdihumboldt.hale.io.shp.ShapefileConstants;

/**
 * Class to write features into shape files.
 * 
 * @author Kapil Agnihotri
 */
public class ShapefileInstanceWriter extends AbstractGeoInstanceWriter {

	/**
	 * The identifier of the writer as registered to the I/O provider extension.
	 */
	public static final String ID = "eu.esdihumboldt.hale.io.shp.instance.writer";

	@Override
	public boolean isPassthrough() {
		return false;
	}

	@Override
	public boolean isCancelable() {
		return true;
	}

	@Override
	protected IOReport execute(ProgressIndicator progress, IOReporter reporter)
			throws IOProviderConfigurationException, IOException {
		progress.begin("Generating Shapefile", ProgressIndicator.UNKNOWN);
		InstanceCollection instances = getInstances();
		try {
			URI location = getTarget().getLocation();
			writeInstances(instances, progress, reporter, location);
			reporter.setSuccess(true);
		} catch (Exception e) {
			reporter.error(new IOMessageImpl(e.getLocalizedMessage(), e));
			reporter.setSuccess(false);
			reporter.setSummary("Saving instances to Shapefile failed.");
		} finally {
			progress.end();
		}

		return reporter;
	}

	@Override
	protected String getDefaultTypeName() {
		return null;
	}

	/**
	 * Write instances to the Shapefiles. It is a 4 step process. <br>
	 * 1. create simpleFeatureType <br>
	 * 2. create Shapefile schema from the feature collection. <br>
	 * 3. create features <br>
	 * 4. write the feature data to the Shapefile.
	 * 
	 * @param instances instance to write to.
	 * @param progress the progress indicator.
	 * @param reporter the reporter.
	 * @param location file path URI.
	 * 
	 * @throws IOException exception in any.
	 * 
	 */

	protected void writeInstances(InstanceCollection instances, ProgressIndicator progress,
			IOReporter reporter, URI location) throws IOException {

		// in all the variables, outer Map is for tracking multiple schemas and
		// inner Map for multiple geometries.
		Map<String, Map<String, SimpleFeatureType>> schemaFtMap = createFeatureType(instances,
				progress, reporter);

		Map<String, Map<String, ShapefileDataStore>> schemaDataStoreMap = createSchema(location,
				schemaFtMap);

		Map<String, Map<String, List<SimpleFeature>>> schemaFeaturesMap = createFeatures(instances,
				progress, reporter, schemaFtMap);

		writeToFile(schemaDataStoreMap, schemaFtMap, schemaFeaturesMap);
	}

	/**
	 * Step 1. Method to create feature type for the shape file. This is the
	 * first step, which creates the schema for the shape file.
	 * 
	 * shape file restrictions: <br>
	 * a single geometry column named the_geom <br>
	 * - "the_geom" is always first, and used for geometry attribute name <br>
	 * - "the_geom" must be of type Point, MultiPoint, MuiltiLineString,
	 * MultiPolygon<br>
	 * - Attribute names are limited in length<br>
	 * - Not all data types are supported (example Timestamp represented as
	 * Date)<br>
	 * 
	 * @param instances the instance to write.
	 * @param progress the progress indicator.
	 * @param reporter the reporter.
	 * @return map of SimpleFeatureType type used as a template to describe the
	 *         file contents.
	 */
	private Map<String, Map<String, SimpleFeatureType>> createFeatureType(
			InstanceCollection instances, ProgressIndicator progress, IOReporter reporter) {
		// 1. create simpleFeatureType
		Map<String, Map<String, SimpleFeatureType>> schemaSftMap = new HashMap<String, Map<String, SimpleFeatureType>>();

		Map<String, Map<String, SimpleFeatureTypeBuilder>> schemaBuilderMap = new HashMap<String, Map<String, SimpleFeatureTypeBuilder>>();

		List<String> missingGeomsForSchemas = new ArrayList<String>();
		try (ResourceIterator<Instance> it = instances.iterator()) {
			while (it.hasNext() && !progress.isCanceled()) {
				Instance instance = it.next();
				TypeDefinition type = instance.getDefinition();

				String localPart = type.getName().getLocalPart();
				if (schemaBuilderMap.get(localPart) == null) {
					Map<String, SimpleFeatureTypeBuilder> geometryBuilderMap = new HashMap<String, SimpleFeatureTypeBuilder>();
					writeGeometrySchema(instance, localPart, geometryBuilderMap,
							missingGeomsForSchemas);

					// add rest of the properties to the
					// SimpleFeatureTypeBuilder.
					writePropertiesSchema(instance, type, geometryBuilderMap);
					schemaBuilderMap.put(localPart, geometryBuilderMap);
				}
				// else nothing to do as the schema definition is already
				// present.
			}
		}
		for (String localPart : missingGeomsForSchemas) {
			reporter.error(
					"Cannot create Shapefile for Schema: " + localPart + " as no Geometry found!!");
		}
		// create SimpleFeatureType from SimpleFeatureTypeBuilder.
		for (Entry<String, Map<String, SimpleFeatureTypeBuilder>> schemaEntry : schemaBuilderMap
				.entrySet()) {
			for (Entry<String, SimpleFeatureTypeBuilder> geometryEntry : schemaEntry.getValue()
					.entrySet()) {
				SimpleFeatureType buildFeatureType = geometryEntry.getValue().buildFeatureType();
				schemaSftMap
						.computeIfAbsent(schemaEntry.getKey(),
								k -> new HashMap<String, SimpleFeatureType>())
						.put(geometryEntry.getKey(), buildFeatureType);
			}
		}
		return schemaSftMap;
	}

	/**
	 * Method to write Geometry definition to the shape file schema.
	 * 
	 * @param instance instance.
	 * @param localPart local part of <code>QName</code> which tracks multiple
	 *            schemas.
	 * @param geometryBuilderMap SimpleFeatureType to build schema definition
	 *            for the shape file.
	 * @param missingGeomsForSchemas track all the schemas with missing
	 *            geometry, so that later they can be logged in the reporter and
	 *            prevent throwing exception in case of more than one schema to
	 *            export.
	 */
	private void writeGeometrySchema(Instance instance, String localPart,
			Map<String, SimpleFeatureTypeBuilder> geometryBuilderMap,
			List<String> missingGeomsForSchemas) {
		Geometry geom = null;

		List<GeometryProperty<?>> geoms = traverseInstanceForGeometries(instance);

		// add geometries to the shape SimpleFeatureTypeBuilder.
		if (geoms.size() > 1) {
			for (GeometryProperty<?> geoProp : geoms) {
				geom = geoProp.getGeometry();
				createSimpleFeatureTypeBuilderWithGeometry(localPart, geometryBuilderMap, geom,
						geoProp);
			}
		}
		else if (!geoms.isEmpty()) {
			geom = geoms.get(0).getGeometry();
			createSimpleFeatureTypeBuilderWithGeometry(localPart, geometryBuilderMap, geom,
					geoms.get(0));
		}
		else {
			missingGeomsForSchemas.add(localPart);
		}
	}

	/**
	 * Method to write schema definition for all the properties.
	 * 
	 * @param instance instance to write to.
	 * @param type type definition.
	 * @param geometryBuilderMap SimpleFeatureType to build schema definition
	 *            for the shape file.
	 */
	private void writePropertiesSchema(Instance instance, TypeDefinition type,
			Map<String, SimpleFeatureTypeBuilder> geometryBuilderMap) {
		Collection<? extends PropertyDefinition> allNonComplexProperties = getNonComplexProperties(
				type);
		for (PropertyDefinition prop : allNonComplexProperties) {
			Class<?> binding = prop.getPropertyType().getConstraint(Binding.class).getBinding();
			// ignore geometry and filename properties.
			if (!prop.getPropertyType().getConstraint(GeometryType.class).isGeometry()
					&& !prop.getName().getNamespaceURI()
							.equalsIgnoreCase(ShapefileConstants.SHAPEFILE_AUGMENT_NS)) {
				if (instance.getProperty(prop.getName()) != null) {
					Set<String> keySet = geometryBuilderMap.keySet();
					for (String key : keySet) {
						geometryBuilderMap.get(key).add(prop.getDisplayName(), binding);
					}
				}
			}
		}
	}

	/**
	 * Method to traverse instance to find geometries.
	 * 
	 * @param instance instance.
	 * @return list of geometries.
	 */
	private List<GeometryProperty<?>> traverseInstanceForGeometries(Instance instance) {
		// find geometries in the schema.
		InstanceTraverser traverser = new DepthFirstInstanceTraverser(true);
		GeometryFinder geoFind = new GeometryFinder(null);
		traverser.traverse(instance, geoFind);

		List<GeometryProperty<?>> geoms = geoFind.getGeometries();
		return geoms;
	}

	/**
	 * Method to retrieve all the properties.
	 * 
	 * @param type type definition.
	 * @return Collection of all the properties.
	 */
	private Collection<? extends PropertyDefinition> getNonComplexProperties(TypeDefinition type) {
		Collection<? extends PropertyDefinition> allNonComplexProperties = DefinitionUtil
				.getAllProperties(type).stream().filter(p -> {
					// filter out complex properties w/o HasValue or
					// AugmentedValue
					return p.getPropertyType().getConstraint(HasValueFlag.class).isEnabled() || p
							.getPropertyType().getConstraint(AugmentedValueFlag.class).isEnabled();
				}).collect(Collectors.toList());
		return allNonComplexProperties;
	}

	/**
	 * Convenience method to create SimpleFeatureTypeBuilder with geometry and
	 * target CRS information.
	 * 
	 * @param localPart local part of <code>QName</code> which tracks multiple
	 *            schemas.
	 * @param geometryBuilderMap simpleFeatureTypeBuilder which adds schema info
	 *            for the shape file.
	 * @param geom geometry.
	 * @param geoProp GeometryProperty.
	 */
	private void createSimpleFeatureTypeBuilderWithGeometry(String localPart,
			Map<String, SimpleFeatureTypeBuilder> geometryBuilderMap, Geometry geom,
			GeometryProperty<?> geoProp) {
		SimpleFeatureTypeBuilder sftBuilder = new SimpleFeatureTypeBuilder();
		sftBuilder.setName(localPart);
		sftBuilder.setNamespaceURI(ShapefileConstants.SHAPEFILE_NS);

		CRSDefinition targetCrs = getTargetCRS();
		if (targetCrs != null) {
			sftBuilder.setCRS(targetCrs.getCRS());
		}
		else {
			sftBuilder.setCRS(geoProp.getCRSDefinition().getCRS());
		}
		sftBuilder.add(ShapefileConstants.THE_GEOM, geom.getClass());
		geometryBuilderMap.put(geom.getGeometryType(), sftBuilder);
	}

	/**
	 * Step 2. method to create schema. This method will create filename as:<br>
	 * - filename_schemaName_geometryType.shp if multiple schema and geom.<br>
	 * - filename_schemaName.shp if multiple schemas.<br>
	 * - filename_geometryType.shp if multiple geometries.<br>
	 * - filename.shp single schema and geom.
	 * 
	 * @param location location to store the shape files.
	 * 
	 * @param schemaSftMap type is used as a template to describe the file
	 *            contents.
	 * @return shape file data store.
	 * @throws IOException exception if any.
	 */
	private Map<String, Map<String, ShapefileDataStore>> createSchema(URI location,
			Map<String, Map<String, SimpleFeatureType>> schemaSftMap) throws IOException {

		if (schemaSftMap.isEmpty()) {
			throw new IOException("Cannot export to the shape file as no Geometry found!!");
		}
		Map<String, Map<String, ShapefileDataStore>> schemaDataStoreMap = new HashMap<String, Map<String, ShapefileDataStore>>();

		// logic to create file name based on the multiple schemas and/or
		// multiple geometries.
		int numberOfSchemas = schemaSftMap.keySet().size();
		for (Entry<String, Map<String, SimpleFeatureType>> schemaEntry : schemaSftMap.entrySet()) {
			int numberOfGeometries = schemaEntry.getValue().keySet().size();
			for (Entry<String, SimpleFeatureType> geometryEntry : schemaEntry.getValue()
					.entrySet()) {

				Map<String, Serializable> params = new HashMap<String, Serializable>();
				File file = createFileWithFormattedName(location, numberOfSchemas, schemaEntry,
						numberOfGeometries, geometryEntry);
				params.put(ShapefileConstants.URL_STRING, file.toURI().toURL());
				// create schema.
				ShapefileDataStoreFactory dataStoreFactory = new ShapefileDataStoreFactory();
				ShapefileDataStore newDataStore;

				newDataStore = (ShapefileDataStore) dataStoreFactory.createNewDataStore(params);
				newDataStore.createSchema(geometryEntry.getValue());
				schemaDataStoreMap
						.computeIfAbsent(schemaEntry.getKey(),
								k -> new HashMap<String, ShapefileDataStore>())
						.put(geometryEntry.getKey(), newDataStore);
			}
		}
		return schemaDataStoreMap;
	}

	/**
	 * Method to create file name based on the number of schema and geom:<br>
	 * - filename_schemaName_geometryType.shp if multiple schema and geom.<br>
	 * - filename_schemaName.shp if multiple schemas.<br>
	 * - filename_geometryType.shp if multiple geometries.<br>
	 * - filename.shp single schema and geom.
	 * 
	 * @param location file location.
	 * @param numberOfSchemas number of schemas.
	 * @param schemaEntry current schema in process.
	 * @param numberOfGeometries number of geometries in the schema.
	 * @param geometryEntry current geometry entry in process.
	 * @return file with the formatted file name.
	 */
	private File createFileWithFormattedName(URI location, int numberOfSchemas,
			Entry<String, Map<String, SimpleFeatureType>> schemaEntry, int numberOfGeometries,
			Entry<String, SimpleFeatureType> geometryEntry) {
		String filenameWithType = location.getPath();
		String filePath = Paths.get(location).getParent().toString();
		String baseFilename = Paths.get(location).getFileName().toString();
		baseFilename = baseFilename.substring(0, baseFilename.lastIndexOf("."));
		if (numberOfSchemas > 1) {
			if (numberOfGeometries > 1) {
				filenameWithType = filePath + FileSystems.getDefault().getSeparator() + baseFilename
						+ ShapefileConstants.UNDERSCORE + schemaEntry.getKey()
						+ ShapefileConstants.UNDERSCORE + geometryEntry.getKey()
						+ ShapefileConstants.SHP_EXTENSION;
			}
			else {
				filenameWithType = filePath + FileSystems.getDefault().getSeparator() + baseFilename
						+ ShapefileConstants.UNDERSCORE + schemaEntry.getKey()
						+ ShapefileConstants.SHP_EXTENSION;
			}
		}
		else if (numberOfGeometries > 1) {
			filenameWithType = filePath + FileSystems.getDefault().getSeparator() + baseFilename
					+ ShapefileConstants.UNDERSCORE + geometryEntry.getKey()
					+ ShapefileConstants.SHP_EXTENSION;
		}
		File file;
		try {
			file = new File(filenameWithType);
		} catch (Exception e) {
			throw new IllegalArgumentException("Only files are supported as data source", e);
		}
		if (file.exists() && file.length() == 0L) {
			// convenience for overwriting to empty existing file.
			file.delete();
		}
		return file;
	}

	/**
	 * * Step 3. method to create features for the shape files and write them as
	 * per the schema definition.<br>
	 * Always the first entry should be "the_geom" then rest of the properties
	 * can be written.
	 * 
	 * @param instances instance to write.
	 * @param progress the progress indicator.
	 * @param reporter the reporter.
	 * @param schemaFtMap type is used as a template to describe the file
	 *            contents.
	 * @return list all the features to be added to the file bundled in map for
	 *         multiple schemas and multiple geometries.
	 */
	private Map<String, Map<String, List<SimpleFeature>>> createFeatures(
			InstanceCollection instances, ProgressIndicator progress, IOReporter reporter,
			Map<String, Map<String, SimpleFeatureType>> schemaFtMap) {
		// 3. create features

		Map<String, Map<String, List<SimpleFeature>>> schemaFeaturesMap = new HashMap<String, Map<String, List<SimpleFeature>>>();
		Map<String, Map<String, SimpleFeatureBuilder>> schemaFbMap = new HashMap<String, Map<String, SimpleFeatureBuilder>>();

		// initialize simple feature type builder for all the schemas and
		// geometries.
		for (Entry<String, Map<String, SimpleFeatureType>> schemaEntry : schemaFtMap.entrySet()) {
			for (Entry<String, SimpleFeatureType> geomEntry : schemaEntry.getValue().entrySet()) {
				schemaFbMap
						.computeIfAbsent(schemaEntry.getKey(),
								k -> new HashMap<String, SimpleFeatureBuilder>())
						.computeIfAbsent(geomEntry.getKey(),
								k1 -> new SimpleFeatureBuilder(geomEntry.getValue()));
			}
		}

		// write features to shape file schema.
		try (ResourceIterator<Instance> it = instances.iterator()) {
			while (it.hasNext() && !progress.isCanceled()) {

				Instance instance = it.next();
				TypeDefinition type = instance.getDefinition();
				String localPart = type.getName().getLocalPart();
				if (schemaFtMap.containsKey(localPart)) {
					writeGeometryInstanceData(reporter, schemaFbMap, instance, localPart);
					// add data for the rest of the properties.
					writePropertiesInstanceData(schemaFbMap, instance, type, localPart);

					// create list of simple features.
					Set<String> geometryKeys = schemaFbMap.get(localPart).keySet();
					for (String key : geometryKeys) {
						SimpleFeature feature = schemaFbMap.get(localPart).get(key)
								.buildFeature(null);
						schemaFeaturesMap
								.computeIfAbsent(localPart,
										k -> new HashMap<String, List<SimpleFeature>>())
								.computeIfAbsent(key, k1 -> new ArrayList<>()).add(feature);
					}
				}
				// else the schema was deleted as there wasn't any geometry in
				// it.
			}
		}
		return schemaFeaturesMap;
	}

	/**
	 * Method to write the geometry in the shape file schema.
	 * 
	 * @param reporter reporter.
	 * @param schemaFbMap map of feature builder to write the data to.
	 * @param instance instance
	 * @param localPart local part of <code>QName</code> which tracks multiple
	 *            schemas.
	 */
	private void writeGeometryInstanceData(IOReporter reporter,
			Map<String, Map<String, SimpleFeatureBuilder>> schemaFbMap, Instance instance,
			String localPart) {
		List<GeometryProperty<?>> geoms = traverseInstanceForGeometries(instance);

		if (geoms.size() > 1) {
			for (GeometryProperty<?> geoProp : geoms) {
				addGeometryData(reporter, schemaFbMap, localPart, geoms, geoProp.getGeometry());
			}
		}
		else if (!geoms.isEmpty()) {
			addGeometryData(reporter, schemaFbMap, localPart, geoms, geoms.get(0).getGeometry());
		}
	}

	/**
	 * Method to write all the property data in the shape file schema.
	 * 
	 * @param schemaFbMap map of feature builder to write the data to.
	 * @param instance instance.
	 * @param type type definition.
	 * @param localPart local part of <code>QName</code> which tracks multiple
	 *            schemas.
	 */
	private void writePropertiesInstanceData(
			Map<String, Map<String, SimpleFeatureBuilder>> schemaFbMap, Instance instance,
			TypeDefinition type, String localPart) {
		Collection<? extends PropertyDefinition> allNonComplexProperties = getNonComplexProperties(
				type);
		for (PropertyDefinition prop : allNonComplexProperties) {
			if (!prop.getPropertyType().getConstraint(GeometryType.class).isGeometry()
					&& !prop.getName().getNamespaceURI()
							.equalsIgnoreCase(ShapefileConstants.SHAPEFILE_AUGMENT_NS)) {
				Object value = new InstanceAccessor(instance)
						.findChildren(prop.getName().getLocalPart()).value();
				if (value != null) {
					Set<String> geometryKeys = schemaFbMap.get(localPart).keySet();
					for (String key : geometryKeys) {
						schemaFbMap.get(localPart).get(key).add(value);
					}
				}
			}
		}
	}

	/**
	 * Convenience method to convert geometry to the target CRS and add to the
	 * feature builder.
	 * 
	 * @param reporter the reporter
	 * @param schemaFbMap featureBuilder to add all the data in the shape file
	 *            schema.
	 * @param localPart local part of <code>QName</code> which tracks multiple
	 *            schemas.
	 * @param geoms list of GeometryProperties to extract CRS definition.
	 * @param geom geometry.
	 */
	private void addGeometryData(IOReporter reporter,
			Map<String, Map<String, SimpleFeatureBuilder>> schemaFbMap, String localPart,
			List<GeometryProperty<?>> geoms, Geometry geom) {
		if (getTargetCRS() != null) {
			geom = convertGeometry(geom, geoms.get(0).getCRSDefinition(), reporter).getFirst();
		}
		schemaFbMap.get(localPart).get(geom.getGeometryType()).add(geom);
	}

	/**
	 * Final step to write to the shape file using transaction.
	 * 
	 * @param schemaDataStoreMap data store for the shape file.
	 * @param schemaFtMap used as a template to describe the file contents.
	 * @param schemaFeaturesMap for each schema, each geom list of features to
	 *            be written to the shape file.
	 * @throws IOException if any.
	 */
	private void writeToFile(Map<String, Map<String, ShapefileDataStore>> schemaDataStoreMap,
			Map<String, Map<String, SimpleFeatureType>> schemaFtMap,
			Map<String, Map<String, List<SimpleFeature>>> schemaFeaturesMap) throws IOException {

		// extract each schema
		for (Entry<String, Map<String, ShapefileDataStore>> schemaEntry : schemaDataStoreMap
				.entrySet()) {
			String localPart = schemaEntry.getKey();
			// extract each geometry.
			for (Entry<String, ShapefileDataStore> geomEntry : schemaEntry.getValue().entrySet()) {
				Transaction transaction = new DefaultTransaction(
						ShapefileConstants.CREATE_CONSTANT);
				String typeName = geomEntry.getValue().getTypeNames()[0];

				SimpleFeatureSource geomSpecificFeatureSource = geomEntry.getValue()
						.getFeatureSource(typeName);
				if (geomSpecificFeatureSource instanceof SimpleFeatureStore) {
					SimpleFeatureStore geomSpecificFeatureStore = (SimpleFeatureStore) geomSpecificFeatureSource;

					// create collection to write to the shape file.
					SimpleFeatureCollection collection = new ListFeatureCollection(
							schemaFtMap.get(localPart).get(geomEntry.getKey()),
							schemaFeaturesMap.get(localPart).get(geomEntry.getKey()));
					geomSpecificFeatureStore.setTransaction(transaction);
					try {
						geomSpecificFeatureStore.addFeatures(collection);
						transaction.commit();
					} catch (IOException e) {
						transaction.rollback();
						throw e;
					} finally {
						transaction.close();
					}
				}
				else {
					// throw exception
					transaction.close();
					throw new IOException(typeName + " does not support read/write access");
				}
			}
		}
	}

}
