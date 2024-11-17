package gov.nasa.pds.citool.ingestor;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;
import gov.nasa.pds.citool.file.FileObject;
import gov.nasa.pds.citool.registry.model.FileInfo;
import gov.nasa.pds.citool.registry.model.Metadata;
import gov.nasa.pds.citool.registry.model.RegistryObject;
import gov.nasa.pds.citool.registry.model.Slots;
import gov.nasa.pds.citool.util.References;
import gov.nasa.pds.citool.util.Utility;
import gov.nasa.pds.tools.label.AttributeStatement;
import gov.nasa.pds.tools.label.ObjectStatement;


public class ProductFactory 
{

  private static Logger log = Logger.getLogger(ProductFactory.class.getName());

	/**
	 * Create an extrinsic object
	 * 
	 * @param catObj    a catalog object
	 * @return an extrinsic object
	 *  
	 */
    public static RegistryObject createProduct(CatalogObject catObj, CatalogVolumeIngester ingester)
		throws Exception 
	{
		RegistryObject product = new RegistryObject();
		String objType = catObj.getCatObjType();

		Slots slots = new Slots();
		
		Metadata md = catObj.getMetadata();
		for (String key: md.getKeys()) 
		{
			String value = md.getMetadata(key).trim();
			List<String> values = new ArrayList<String>();
		
			if (objType.equalsIgnoreCase(Constants.MISSION_OBJ) && key.equals("MISSION_NAME")) 
			{
				value = Utility.collapse(value);
				product.setName(value);
				slots.put("name", product.getName());
				
				String tmpValue = value;
				tmpValue = Utility.replaceChars(value);
				String productLid = Constants.LID_PREFIX+"investigation:mission." + tmpValue;
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				slots.put("lid", productLid);
				
				product.setObjectType(Constants.MISSION_PROD);
				slots.put("objectType", product.getObjectType());
				
				slots.put(getKey("product_class"), Constants.MISSION_PROD);
			}
			else if (objType.equalsIgnoreCase(Constants.TARGET_OBJ) && key.equals("TARGET_NAME")) 
			{
				value = Utility.collapse(value);
				product.setName(value);
				slots.put("name", product.getName());
				
				String tmpValue = value;
				String targetType = md.getMetadata("TARGET_TYPE");	
				String productLid = Constants.LID_PREFIX+"target:" +targetType + "."+tmpValue;
				productLid = Utility.replaceChars(productLid);
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				slots.put("lid", productLid);
				
				product.setObjectType(Constants.TARGET_PROD);		
				slots.put("objectType", product.getObjectType());
				
				slots.put(getKey("product_class"), Constants.TARGET_PROD);
			}
			else if (objType.equalsIgnoreCase(Constants.INST_OBJ) && key.equals("INSTRUMENT_ID")) 
			{
				value = Utility.collapse(value);
				String instHostId = md.getMetadata("INSTRUMENT_HOST_ID");
				instHostId = Utility.collapse(instHostId);
				String productLid = Constants.LID_PREFIX+"instrument:instrument."+value+"." + instHostId;
				productLid = Utility.replaceChars(productLid);
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				slots.put("lid", productLid);
				
				product.setObjectType(Constants.INST_PROD);
				slots.put("objectType", product.getObjectType());
				
				product.setName(md.getMetadata("INSTRUMENT_NAME") + " for " + instHostId);
				slots.put("name", product.getName());
				
				slots.put(getKey("product_class"), Constants.INST_PROD);
			}
			else if (objType.equalsIgnoreCase(Constants.INSTHOST_OBJ) && key.equals("INSTRUMENT_HOST_ID")) 
			{	
				value = Utility.collapse(value);
				String instHostType = md.getMetadata("INSTRUMENT_HOST_TYPE");
    			
				if(instHostType != null)
    			{
    				String mainLid = Utility.collapse(instHostType + "." + value).toLowerCase();
    				mainLid = Utility.replaceChars(Constants.LID_PREFIX + "instrument_host:" + mainLid);
    				product.setLid(mainLid);
    				
    				String altLid = Utility.collapse("instrument_host." + value).toLowerCase();
    				altLid = Utility.replaceChars(Constants.LID_PREFIX + "instrument_host:" + altLid);
    				product.setAltLid(altLid);
    			}
    			else
    			{
    				String mainLid = Utility.collapse("instrument_host." + value).toLowerCase();
    				mainLid = Utility.replaceChars(Constants.LID_PREFIX + "instrument_host:" + mainLid);
    				product.setLid(mainLid);
    			}
    							
				slots.put("lid", product.getLid());
				
				product.setObjectType(Constants.INSTHOST_PROD);
				slots.put("objectType", product.getObjectType());
				
				product.setName(md.getMetadata("INSTRUMENT_HOST_NAME"));
				slots.put("name", product.getName());
				
				slots.put(getKey("product_class"), Constants.INSTHOST_PROD);
			}
			else if (objType.equalsIgnoreCase(Constants.DATASET_OBJ) && key.equals("DATA_SET_ID")) 
			{
				product.setName(md.getMetadata("DATA_SET_NAME"));	
				slots.put("name", product.getName());

				value = md.getMetadata(key);
				value = Utility.collapse(value);
				String tmpValue = value;
				tmpValue = Utility.replaceChars(value);
				String productLid = Constants.LID_PREFIX+"data_set:data_set." + tmpValue;
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				slots.put("lid", productLid);
				
				product.setObjectType(Constants.DS_PROD);
				slots.put("objectType", product.getObjectType());
				
				slots.put(getKey("product_class"), Constants.DS_PROD);
			}
			else if (objType.equalsIgnoreCase(Constants.VOLUME_OBJ) && key.equals("VOLUME_ID")) 
			{
				value = Utility.collapse(value);
				String volumeSetId = md.getMetadata("VOLUME_SET_ID");
				volumeSetId = Utility.collapse(volumeSetId);
				String productLid = Constants.LID_PREFIX+"volume:volume."+value+"__" + volumeSetId;
				productLid = Utility.replaceChars(productLid);
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				slots.put("lid", productLid);
				
				//volumeLid = productLid;
				product.setObjectType(Constants.VOLUME_PROD);
				slots.put("objectType", product.getObjectType());
				
				product.setName(value);
				slots.put("name", product.getName());
				
				slots.put(getKey("product_class"), Constants.VOLUME_PROD);
              } else if (objType.equalsIgnoreCase(Constants.HK_OBJ)
                  && key.equals("CURATING_NODE_ID")) {

                String dsId = md.getMetadata("DATA_SET_ID");
                String productLid = dsId + Constants.RESOURCE_LID_SUFFIX;
                productLid = Utility.collapse(productLid);
                productLid = Utility.replaceChars(productLid);
                productLid = Constants.LID_PREFIX + "resource:resource." + productLid.toLowerCase();

                product.setLid(productLid);
                slots.put("lid", productLid);

                product.setObjectType(Constants.RESOURCE_PROD);
                slots.put("objectType", product.getObjectType());

                slots.put(getKey("product_class"), Constants.RESOURCE_PROD);

                String node_id = md.getMetadata("CURATING_NODE_ID");
                node_id = Utility.collapse(node_id);
                slots.put("node_id", getNodeId(node_id.toLowerCase()));
                
                slots.put("resource_name", md.getAllMetadata("RESOURCE_NAME"));
                slots.put("resource_url", md.getAllMetadata("RESOURCE_LINK"));

              }
			
			if (objType.equalsIgnoreCase(Constants.DATASET_OBJ) && ingester.getArchiveStatus() !=null) 
			{
				if (key.equals("ARCHIVE_STATUS")) 
				{
				    // when ARCHIVE_STATUS are in the DataSet object and DataSet Release object, 
				    // DataSet release object take preference.
					value = ingester.getArchiveStatus();
				}
			}
			
			if (md.isMultiValued(key)) 
			{
				List<String> tmpValues = md.getAllMetadata(key);
				tmpValues = new ArrayList<String>(new HashSet<String>(tmpValues));
				for (String aVal : tmpValues) 
				{
					if (key.equals("REFERENCE_KEY_ID")) 
					{
						aVal = References.getInstance().get(aVal);
					}
					values.add(aVal);
				}
			} 
			else 
			{
				if (key.equals("REFERENCE_KEY_ID")) 
				{
					value = References.getInstance().get(value);
				}
				values.add(value);
			}

			if (getKey(key) != null) 
			{
				slots.put(getKey(key), values);
			}

			// need to add this one for alternate_id for MISSION object
			if (key.equals("MISSION_ALIAS_NAME")) 
			{
				slots.put("alternate_id", values);
			}
		}
		
		if (objType.equalsIgnoreCase(Constants.DATASET_OBJ) && ingester.getArchiveStatus() != null) {
			if (!md.containsKey("ARCHIVE_STATUS")) 
			{
		    	slots.put(getKey("ARCHIVE_STATUS"), ingester.getArchiveStatus());
		    }
		}

		
		slots.put("modification_date", catObj.getFileObject().getCreationDateTime());
		
		/*
		slots.add(new Slot("modification_version_id", Arrays.asList(new String[] {"1.0"})));
		slots.add(new Slot("information_model_version", Arrays.asList(new String[] {"1.8.0.0"})));
		*/
		
		product.setSlots(slots);

		// MD5 Hash
		FileObject fileObj = catObj.getFileObject();
		if(fileObj == null)
		{
			System.out.println("WARNING: " + product.getLid() + " doesn't have a file.");
			product.setMd5Hash("");
		}
		else
		{
			String md5 = catObj.getFileObject().getChecksum();
			product.setMd5Hash(md5);
		}
		
		return product;
	}

	
	/**
	 * Create file info
	 */
	public static FileInfo createFile(CatalogObject catObj)
		throws Exception
	{		
		FileObject fileObject = catObj.getFileObject();
		String objType = catObj.getCatObjType();
		
		// Do not store following objects
		if (objType.equalsIgnoreCase("PERSONNEL")
				|| objType.equalsIgnoreCase("REFERENCE")
				|| objType.equalsIgnoreCase("SOFTWARE")
				|| objType.equalsIgnoreCase("DATA_SET_HOUSEKEEPING")
				|| objType.equalsIgnoreCase("DATA_SET_MAP_PROJECTION")
				|| objType.equalsIgnoreCase("DATA_SET_RELEASE")) 
		{
			return null;
		}
				
		FileInfo fi = new FileInfo();
		fi.lid = catObj.getExtrinsicObject().getLid();
		fi.path = Paths.get(fileObject.getLocation(), fileObject.getName()).toString();
		fi.md5 = fileObject.getChecksum();

        return fi;
	}

	
	/**
	 * Create an extrinsic object
	 * 
	 * @param catObj    a catalog object
	 * @return an extrinsic object
	 *  
	 */
	public static RegistryObject createResrcProduct(ObjectStatement resrcObj, CatalogObject catObj) 
		throws Exception 
	{
		RegistryObject product = new RegistryObject();
		Slots slots = new Slots();
		String productLid = null;
		Metadata md = catObj.getMetadata();
		
		slots.put(getKey("LABEL_REVISION_NOTE"), md.getMetadata("LABEL_REVISION_NOTE"));
		
		List<AttributeStatement> objAttr = resrcObj.getAttributes();
		for (AttributeStatement attrSmt : objAttr) {
			String key = attrSmt.getElementIdentifier().toString();
			String value = attrSmt.getValue().toString();
			List<String> values = new ArrayList<String>();

			if (key.equals("RESOURCE_ID")) {
				// need to use RESOURCE_NAME for the name
				product.setName(resrcObj.getAttribute("RESOURCE_NAME").getValue().toString());
				String dsId = md.getMetadata("DATA_SET_ID");
				productLid = Constants.LID_PREFIX+"resource:resource."+dsId + "__" + value;
				productLid = Utility.replaceChars(productLid);
				productLid = productLid.toLowerCase();
				product.setLid(productLid);
				product.setObjectType(Constants.CONTEXT_PROD);		
			}	
				
			if (attrSmt.getValue() instanceof Set) {
				List<String> valueList = CatalogObject.getValueList(attrSmt.getValue());
				values = valueList;
			}
			else {
				values.add(value);
			}

			if (key.equals("DESCRIPTION")) {
				slots.put("resource_description", values);
			}
			else {
				if (getKey(key) != null)
					slots.put(getKey(key), values);
			}
		}
		
		List<String> tmpVals = new ArrayList<String>();
		tmpVals.add(catObj.getFileObject().getCreationDateTime());
		slots.put("modification_date", tmpVals);
		slots.put("modification_version_id", "1.0");
		slots.put("data_class", "Resource");
		slots.put("resource_type", "Information.Science_Portal");
		
		product.setSlots(slots);	

		return product;
	}

	
    private static String getKey(String key) 
    {
		if (key.equalsIgnoreCase("PDS_VERSION_ID") ||
			key.equalsIgnoreCase("RECORD_TYPE"))
			return null;
		
		for (Entry<String, String> entry: Constants.pds3ToPds4Map.entrySet()) {
			if (key.equalsIgnoreCase(entry.getKey())) 
				return entry.getValue(); 
		}
		if (key.endsWith("_DESC"))
			return key.toLowerCase()+"ription";
		else 
			return key.toLowerCase();
	}

    private static String getNodeId(String value) {
      if (Constants.nodeValueToIdMap.containsKey(value)) {
        return Constants.nodeValueToIdMap.get(value);
      }
      return value;
    }

}
