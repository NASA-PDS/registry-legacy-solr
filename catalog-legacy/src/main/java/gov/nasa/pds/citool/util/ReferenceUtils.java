package gov.nasa.pds.citool.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import gov.nasa.pds.citool.ingestor.CatalogObject;
import gov.nasa.pds.citool.ingestor.CatalogVolumeIngester;
import gov.nasa.pds.citool.ingestor.Constants;
import gov.nasa.pds.citool.registry.model.Metadata;
import gov.nasa.pds.tools.label.AttributeStatement;

public class ReferenceUtils
{
	private static Logger log = Logger.getLogger(ReferenceUtils.class.getName());
	
	private static void handleMissionRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		Metadata md = catObj.getMetadata();
		
		String lidValue = null;
		List<String> values = null;
		
		lidValue = pdsLbl.get("MISSION_NAME").getValue().toString();
		lidValue = Utility.collapse(lidValue);
		lidValue = Utility.replaceChars(lidValue);
		lidValue = lidValue.toLowerCase();
		
		if (refs.get(Constants.HAS_MISSION)!=null) 
		{
			values = refs.get(Constants.HAS_MISSION);
		}
		else 
		{
			values = new ArrayList<String>();
		}
		
		values.add(Constants.LID_PREFIX+"investigation:mission."+lidValue);
		refs.put(Constants.HAS_MISSION, values);

		String key = "INSTRUMENT_HOST_ID";
		if (refs.get(Constants.HAS_INSTHOST)!=null) 
		{
			values = refs.get(Constants.HAS_INSTHOST);
		}
		else 
		{
			values = new ArrayList<String>();
		}
		
		if (md.containsKey(key)) 
		{
			if (md.isMultiValued(key)) 
			{
				List<String> tmpValues = md.getAllMetadata(key);
				for (String aVal: tmpValues) {
					lidValue = aVal;
					String instHostType = md.getMetadata("INSTRUMENT_HOST_TYPE");
	    			if (instHostType!=null)
	    				lidValue = instHostType + "." + lidValue; 
	    			else 
	    				lidValue = "instrument_host." + lidValue; 						
					lidValue = Utility.collapse(lidValue);
					lidValue = Utility.replaceChars(lidValue);
					lidValue = lidValue.toLowerCase();
					if (!Utility.valueExists(Constants.LID_PREFIX+"instrument_host:"+lidValue, values))
						values.add(Constants.LID_PREFIX+"instrument_host:"+lidValue);
				}
			}
			else 
			{
				lidValue = md.getMetadata(key);
				String instHostType = md.getMetadata("INSTRUMENT_HOST_TYPE");
    			if (instHostType!=null)
    				lidValue = instHostType + "." + lidValue; 
    			else 
    				lidValue = "instrument_host." + lidValue; 
				lidValue = Utility.collapse(lidValue);
				lidValue = Utility.replaceChars(lidValue);
				lidValue = lidValue.toLowerCase();
				if (!Utility.valueExists(Constants.LID_PREFIX+"instrument_host:"+lidValue, values))
					values.add(Constants.LID_PREFIX+"instrument_host:"+lidValue);          
			}
			
			refs.put(Constants.HAS_INSTHOST, values);
		}
	}

	
	private static void handleDataSetRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		Metadata md = catObj.getMetadata();
		
		String lidValue = null;
		List<String> values = null;
		
		lidValue = pdsLbl.get("DATA_SET_ID").getValue().toString();
		lidValue = Utility.collapse(lidValue);
		lidValue = Utility.replaceChars(lidValue);   			
		lidValue = lidValue.toLowerCase();
		
		if (refs.get(Constants.HAS_DATASET)!=null) {
			values = refs.get(Constants.HAS_DATASET);
		}
		else {
			values = new ArrayList<String>();
		}
		
		if (!Utility.valueExists(Constants.LID_PREFIX+"data_set:data_set."+lidValue, values)) {
			values.add(Constants.LID_PREFIX+"data_set:data_set."+lidValue);
		}
		
		refs.put(Constants.HAS_DATASET, values);
		
		String key = "CURATING_NODE_ID";
		if (md.containsKey(key)) {
			if (refs.get(Constants.HAS_NODE)!=null) {
				values = refs.get(Constants.HAS_NODE);
			}
			else {
				values = new ArrayList<String>();
			}
			if (md.isMultiValued(key)) {
				List<String> tmpValues = md.getAllMetadata(key);
				for (String aVal: tmpValues) {
					lidValue = aVal;
					lidValue = Utility.collapse(lidValue);
					lidValue = lidValue.toLowerCase();
					if (!Utility.valueExists(Constants.LID_PREFIX+"node:node."+lidValue, values))
						values.add(Constants.LID_PREFIX+"node:node."+lidValue);
				}
			}
			else {
				lidValue = md.getMetadata(key);
				lidValue = Utility.collapse(lidValue);
				lidValue = lidValue.toLowerCase();
				if (!Utility.valueExists(Constants.LID_PREFIX+"node:node."+lidValue, values))
					values.add(Constants.LID_PREFIX+"node:node."+lidValue);              
			}
			refs.put(Constants.HAS_NODE, values);      
		}
		
		key = "INSTRUMENT_HOST_ID";
		if (refs.get(Constants.HAS_INSTHOST)!=null) {
			values = refs.get(Constants.HAS_INSTHOST);
		}
		else {
			values = new ArrayList<String>();
		}
		if (md.containsKey(key)) {
			if (md.isMultiValued(key)) {
				List<String> tmpValues = md.getAllMetadata(key);
				// remove duplicate value from the tmpValues list
				tmpValues = new ArrayList<String>(new HashSet<String>(tmpValues));
				for (String aVal: tmpValues) {
					lidValue = aVal;
					String instHostType = md.getMetadata("INSTRUMENT_HOST_TYPE");
	    			if (instHostType!=null)
	    				lidValue = instHostType + "." + lidValue; 
	    			else 
	    				lidValue = "instrument_host." + lidValue; 
					lidValue = Utility.collapse(lidValue);
					lidValue = Utility.replaceChars(lidValue);
					lidValue = lidValue.toLowerCase();
					if (!Utility.valueExists(Constants.LID_PREFIX+"instrument_host:"+lidValue, values))
						values.add(Constants.LID_PREFIX+"instrument_host:"+lidValue);
				}
			}
			else {
				lidValue = md.getMetadata(key);
				String instHostType = md.getMetadata("INSTRUMENT_HOST_TYPE");
    			if (instHostType!=null)
    				lidValue = instHostType + "." + lidValue; 
    			else 
    				lidValue = "instrument_host." + lidValue; 
				lidValue = Utility.collapse(lidValue);
				lidValue = Utility.replaceChars(lidValue);
				lidValue = lidValue.toLowerCase();
				if (!Utility.valueExists(Constants.LID_PREFIX+"instrument_host:"+lidValue, values))
					values.add(Constants.LID_PREFIX+"instrument_host:"+lidValue);
			}
			refs.put(Constants.HAS_INSTHOST, values);  				
		}   			

	}
	
	
	private static void handleInstrumentRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		
		String lidValue = null;
		List<String> values = null;

		lidValue = pdsLbl.get("INSTRUMENT_ID").getValue().toString();
		String hostId = pdsLbl.get("INSTRUMENT_HOST_ID").getValue().toString();

		if (refs.get(Constants.HAS_INST) != null) 
		{
			values = refs.get(Constants.HAS_INST);
		}
		else 
		{
			values = new ArrayList<String>();
		}
		
		lidValue += "." + hostId;
		lidValue = Utility.collapse(lidValue);
		lidValue = Utility.replaceChars(lidValue);
		lidValue = lidValue.toLowerCase();
		values.add(Constants.LID_PREFIX+"instrument:instrument."+lidValue);

		refs.put(Constants.HAS_INST, values);
	}

	
	private static void handleInstrumentHostRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		
		String lidValue = null;
		List<String> values = null;
		
		lidValue = pdsLbl.get("INSTRUMENT_HOST_ID").getValue().toString();
		String instHostType = pdsLbl.get("INSTRUMENT_HOST_TYPE").getValue().toString();
		
		if(instHostType!=null)
			lidValue = instHostType + "." + lidValue; 
		else 
			lidValue = "instrument_host." + lidValue;
		
		lidValue = Utility.collapse(lidValue);
		lidValue = Utility.replaceChars(lidValue);
		lidValue = lidValue.toLowerCase();
		
		if (refs.get(Constants.HAS_INSTHOST)!=null) {
			values = refs.get(Constants.HAS_INSTHOST);
		}
		else {
			values = new ArrayList<String>();
		}
		
		if (!Utility.valueExists(Constants.LID_PREFIX+"instrument_host:"+lidValue, values))
			values.add(Constants.LID_PREFIX+"instrument_host:"+lidValue);

		refs.put(Constants.HAS_INSTHOST, values);
	}
	
	
	private static void handleTargetRefs(Map<String, List<String>> refs, CatalogObject catObj, 
			List<CatalogObject> catObjs)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		
		String lidValue = null;
		List<String> values = null;
				
		lidValue = pdsLbl.get("TARGET_NAME").getValue().toString();
		String targetType = getTargetType(lidValue, catObjs);
		lidValue = targetType + "." + lidValue;
		lidValue = Utility.collapse(lidValue);
		lidValue = Utility.replaceChars(lidValue);
		lidValue = lidValue.toLowerCase();
		if (refs.get(Constants.HAS_TARGET)!=null) 
		{
			values = refs.get(Constants.HAS_TARGET);
		}
		else 
		{
			values = new ArrayList<String>();
		}
		 			
		if (!Utility.valueExists(Constants.LID_PREFIX+"target:" + lidValue, values)) 
		{
			values.add(Constants.LID_PREFIX+"target:" + lidValue);
		}
		
		refs.put(Constants.HAS_TARGET, values);		
	}

	
	private static void handleResourceRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		Metadata md = catObj.getMetadata();
		
		String lidValue = null;
		List<String> values = null;
		
		if (refs.get(Constants.HAS_RESOURCE)!=null) 
		{
			values = refs.get(Constants.HAS_RESOURCE);
		}
		else 
		{
			values = new ArrayList<String>();
		}
		
        lidValue = pdsLbl.get("DATA_SET_ID").getValue().toString() + Constants.RESOURCE_LID_SUFFIX;
        lidValue = Utility.collapse(lidValue);
        lidValue = Utility.replaceChars(lidValue);
        lidValue = lidValue.toLowerCase();
        if (!Utility.valueExists(Constants.LID_PREFIX + "resource:resource." + lidValue, values))
          values.add(Constants.LID_PREFIX + "resource:resource." + lidValue);

        refs.put(Constants.HAS_RESOURCE, values);
	}

	
	private static void handleVolumeRefs(Map<String, List<String>> refs, CatalogObject catObj)
	{
		Map<String, AttributeStatement> pdsLbl = catObj.getPdsLabelMap();
		Metadata md = catObj.getMetadata();
		
		String lidValue = null;
		List<String> values = null;
	
		lidValue = pdsLbl.get("VOLUME_ID").getValue().toString();
		//ingester.setStorageProductName(lidValue);
		
		String key = "DATA_SET_ID";  
		key = Utility.replaceChars(key);
		if (refs.get(Constants.HAS_DATASET)!=null)
			values = refs.get(Constants.HAS_DATASET);
		else
			values = new ArrayList<String>();
		
		// PDS-278 issue
		String dsCollKey = "DATA_SET_COLL_OR_DATA_SET_ID";
		if (md.containsKey(key)) {
			if (md.containsKey(dsCollKey)) {
				System.err.println("\nBoth DATA_SET_ID and DATA_SET_COLL_OR_DATA_SET_ID are present in a volume descriptor catalog file. " +
						"Remove one of them and rerun the Catalog Tool.\n");
				System.exit(1);
			}	
		}
		else {
			if (md.containsKey(dsCollKey)) {
				key = dsCollKey;
			}
			else {
				System.err.println("\nNeither DATA_SET_ID nor DATA_SET_COLL_OR_DATA_SET_ID are present in a volume descriptor catalog file. " +
						"Make sure to include one of them in a volume descriptor catalog file.\n");
				System.exit(1);
			}
		}
		
		// DATA_SET_ID or DATA_SET_COLL_OR_DATA_SET_ID, if both appears, throw an exception, don't ingest...
		// if one of them appear, use it.
		if (md.isMultiValued(key)) {
			List<String> tmpValues = md.getAllMetadata(key);
			for (String aVal: tmpValues) {
				lidValue = aVal;
				lidValue = Utility.collapse(lidValue);
				lidValue = Utility.replaceChars(lidValue);
				lidValue = lidValue.toLowerCase();
				// shouldn't add if the value is already exists in the list
				if (!Utility.valueExists(Constants.LID_PREFIX+"data_set:data_set."+lidValue, values)) {
					values.add(Constants.LID_PREFIX+"data_set:data_set."+lidValue);
				}
			}
		}
		else {
			lidValue = md.getMetadata(key);
			lidValue = Utility.collapse(lidValue);
			lidValue = Utility.replaceChars(lidValue);
			lidValue = lidValue.toLowerCase();
			if (!Utility.valueExists(Constants.LID_PREFIX+"data_set:data_set."+lidValue, values)) {
				values.add(Constants.LID_PREFIX+"data_set:data_set."+lidValue);         
			}
		} 

		refs.put(Constants.HAS_DATASET, values);
	}
	
	
    /**
     * Generates reference class object for association information
     */
    public static Map<String, List<String>> populateReferenceEntries(CatalogVolumeIngester catIngester)
    {
    	Map<String, List<String>> refs = new HashMap<String, List<String>>();
    	
    	for (CatalogObject tmpCatObj: catIngester.getCatalogObjects()) 
    	{
    		String catObjType = tmpCatObj.getCatObjType();
    	
    		if(catObjType.equalsIgnoreCase(Constants.MISSION_OBJ)) 
    		{
    			handleMissionRefs(refs, tmpCatObj);
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.DATASET_OBJ)) 
    		{
    			handleDataSetRefs(refs, tmpCatObj);
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.INST_OBJ)) 
    		{
    			handleInstrumentRefs(refs, tmpCatObj);
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.INSTHOST_OBJ)) 
    		{
    			handleInstrumentHostRefs(refs, tmpCatObj);
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.TARGET_OBJ)) 
    		{
    			catIngester.setTargetAvailable(true);
    			handleTargetRefs(refs, tmpCatObj, catIngester.getCatalogObjects());
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.RESOURCE_OBJ) || 
    				 catObjType.equalsIgnoreCase(Constants.HK_OBJ)) 
    		{	
    			handleResourceRefs(refs, tmpCatObj);
    		}
    		else if (catObjType.equalsIgnoreCase(Constants.VOLUME_OBJ)) 
    		{
    			handleVolumeRefs(refs, tmpCatObj);
    		}
    	}
    	
    	return refs;
    }

    
    private static String getTargetType(String targetName, List<CatalogObject> catObjs)
    {
    	Map<String, String> targetInfos = getTargetInfos(targetName, catObjs);
    	String targetType = "target";
    	
    	if(targetInfos != null) 
    	{
			targetType = targetInfos.get(targetName);
			if(targetType == null) 
			{
				targetType = "target";
			}
			else 
			{
				if (targetType.equalsIgnoreCase("n/a"))
					targetType = "unk";
				if (targetType.toLowerCase().startsWith("unk")) 
					targetType = "unk";
			}
    	}

    	return targetType;
    }


    public static Map<String, String> getTargetInfos(String targetName, List<CatalogObject> catObjs) 
    {
    	for (CatalogObject catObj: catObjs) 
    	{
    		if (catObj.getCatObjType().equalsIgnoreCase(Constants.TARGET_OBJ)) 
    		{
    			if (catObj.getMetadata().getMetadata("TARGET_NAME").equalsIgnoreCase(targetName)) 
    			{  				
    				return catObj.getTargetInfos();
    			}
    		}
    	}
    	
    	return null;
    }

    
    // only retrieve the list from the same catalog object
	public static List<String> getRefValues(String version, String associationType, 
			Map<String, List<String>> allRefs, CatalogObject catObj) 
	{	
		List<String> values = new ArrayList<String>();
	
		if (associationType==Constants.HAS_MISSION) 
		{
			return getRefs("MISSION_NAME", associationType, allRefs, catObj);
		}
		else if (associationType==Constants.HAS_INSTHOST) 
		{
			return getRefs("INSTRUMENT_HOST_ID", associationType, allRefs, catObj);
		}
		else if (associationType==Constants.HAS_INST) 
		{
			return getRefs("INSTRUMENT_ID", associationType, allRefs, catObj);
		}
		else if (associationType==Constants.HAS_TARGET) 
		{
			return getRefs("TARGET_NAME", associationType, allRefs, catObj);
		}
		else if (associationType==Constants.HAS_DATASET) 
		{
			return getRefs("DATA_SET_ID", associationType, allRefs, catObj);
		}
		
		return values;
	}

	
	private static List<String> getRefs(String key, String associationType, Map<String, List<String>> allRefs, CatalogObject catObj) 
	{
		List<String> values = new ArrayList<String>();
		Metadata md = catObj.getMetadata();
		
		if (md.isMultiValued(key)) 
		{
			List<String> tmpValues = md.getAllMetadata(key);			
			// remove duplicate value from the tmpValues list
			tmpValues = new ArrayList<String>(new HashSet<String>(tmpValues));
				
			for (String valueToMatch: tmpValues) 
			{
				valueToMatch = Utility.collapse(valueToMatch);
				valueToMatch = Utility.replaceChars(valueToMatch);
				values.addAll(getRefsList(key, valueToMatch, allRefs, associationType, catObj));
			}
		} 
		else 
		{
			String valueToMatch = md.getMetadata(key);
			
			if(valueToMatch == null)
			{
				log.warning(catObj.getCatObjType() + ": "+ key + " = NULL");
			}
			else
			{
				valueToMatch = Utility.collapse(valueToMatch);
				valueToMatch = Utility.replaceChars(valueToMatch);
				values = getRefsList(key, valueToMatch, allRefs, associationType, catObj);
			}			
		}
		
		return values;
	}
	
	private static List<String> getRefsList(String key, String valueToMatch, Map<String, List<String>> allRefs, 
			String associationType, CatalogObject catObj) 
	{
		List<String> values = new ArrayList<String>();
		String valueToCompare = "";
		for (String aValue: allRefs.get(associationType)) 
		{		
			if (key.equalsIgnoreCase("TARGET_NAME")) 
			{
				valueToCompare = aValue.substring(aValue.lastIndexOf(".") + 1);
				
				if (valueToCompare.equalsIgnoreCase(valueToMatch.toLowerCase())) 
				{
					if (!Utility.valueExists(aValue, values)) {
						values.add(aValue); 
					}
				}						
			}
			else 
			{				
				boolean okToAdd = false;
				if (associationType.equalsIgnoreCase("instrument_host_ref"))
					okToAdd = aValue.endsWith(valueToMatch.toLowerCase());
				else 
					okToAdd = aValue.contains(valueToMatch.toLowerCase());
				
				if (okToAdd)
				{
					values.add(aValue);
				}
			}
		}
		
		return values;
	}

	
	public static List<String> getRefValues(String version, String associationType,  Map<String, List<String>> allRefs) 
	{	
		List<String> values = new ArrayList<String>();
	
		for (String aValue: allRefs.get(associationType)) 
		{
			if (!Utility.valueExists(aValue, values)) 
			{
				values.add(aValue);
			}
		}
		
		return values;
	}

}
