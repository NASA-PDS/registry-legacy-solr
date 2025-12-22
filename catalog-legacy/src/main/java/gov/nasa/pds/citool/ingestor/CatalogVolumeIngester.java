package gov.nasa.pds.citool.ingestor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import gov.nasa.pds.citool.registry.client.RegistryClient;
import gov.nasa.pds.citool.registry.client.RegistryClientManager;
import gov.nasa.pds.citool.registry.model.FileInfo;
import gov.nasa.pds.citool.registry.model.Metadata;
import gov.nasa.pds.citool.registry.model.RegistryObject;
import gov.nasa.pds.citool.registry.model.Slots;
import gov.nasa.pds.citool.search.DocGenerator;
import gov.nasa.pds.citool.search.DocGeneratorException;
import gov.nasa.pds.citool.util.ReferenceUtils;
import gov.nasa.pds.citool.util.Utility;
import gov.nasa.pds.tools.LabelParserException;
import gov.nasa.pds.tools.constants.Constants.ProblemType;


public class CatalogVolumeIngester 
{	
  public static int fileObjCount = 0;
  public static int registryCount = 0;
  public static int solrDocCount = 0;
  public static int failCount = 0;
	
	private String archiveStatus = null;
	private String volumeId; 

	private List<CatalogObject> catObjs;
    public boolean targetAvailable = false;
	
	private Logger log;
	
	/**
	 * Constructor
	 * @param registryURL The URL to the registry service
	 * 
	 */
	public CatalogVolumeIngester() 
	{
		catObjs = new ArrayList<CatalogObject>();
		log = Logger.getLogger(this.getClass().getName());
	}
	
	
	public void addCatalogObject(CatalogObject catObj)
	{
		catObjs.add(catObj);
	}
	
	public List<CatalogObject> getCatalogObjects()
	{
		return catObjs;
	}
		
    public boolean labelExists(String filename) 
    {
    	for (CatalogObject aCatObj: catObjs) 
    	{
    		String tmpFilename = aCatObj.getFilename();
            tmpFilename = tmpFilename.substring(tmpFilename.lastIndexOf(File.separator) + 1);
            
            if (tmpFilename.equalsIgnoreCase(filename)) 
            {
            	return true;
            }
    	}
    	
    	return false;
    }

    
    public void setTargetAvailable(boolean b)
    {
    	targetAvailable = b;
    }

	
	public String getArchiveStatus()
	{
		return archiveStatus;
	}
	
	public void setArchiveStatus(String status) {
		this.archiveStatus = status;
	}
	
    public String getVolumeId()
    {
    	return this.volumeId;
    }

	public void setVolumeId(String id)
	{
		this.volumeId = id;
	}
	
	/**
	 * Method to ingest given catalog object to the registry service
	 * It calls ingestExtrinsicObject() for the product registry.
	 * Then, it calls ingestFileObject() for the corresponding file object registry.
	 * 
	 * @param catObj a Catalog Object instance
	 * 
	 */
	public void ingest(CatalogObject catObj) 
	{
		// initialize a FileObject for given CatalogObject
		catObj.setFileObject();
		createRegistryObject(catObj);

		// Ingest a file to the registry service
		String fileObjGuid = ingestFile(catObj);
		if (fileObjGuid != null) fileObjCount++;
	}
	
	
	private String ingestFile(CatalogObject catObj) 
	{	
		try 
		{
			FileInfo file = ProductFactory.createFile(catObj);
			if(file == null) return null;

			RegistryClient client = RegistryClientManager.getRegistryClient();
			if(client.publishFile(file)) 
			{
				LabelParserException lp = new LabelParserException(catObj.getLabel().getLabelURI(), null, null,
						"ingest.text.recordAdded", ProblemType.SUCCEED,
						"Successfully ingested a file object. GUID - " + file.lid);
				catObj.getLabel().addProblem(lp);
				return file.lid;
			}
			else
			{
				LabelParserException lp = new LabelParserException(catObj.getLabel().getLabelURI(), null, null,
						"ingest.warning.skipFile", ProblemType.SUCCEED,
						"File object already exists in the registry. Won't ingest this file object.");
				catObj.getLabel().addProblem(lp);
				return null;
			}
		} 
		catch(Exception ex) 
		{
			ex.printStackTrace();
			LabelParserException lp = new LabelParserException(catObj.getLabel().getLabelURI(), null, null, 
        			"ingest.error.failExecution", ProblemType.EXECUTE_FAIL, "ingestFileObject");
        	catObj.getLabel().addProblem(lp);
        	return null;
		}
	}

	
	/**
	 * Ingest an extrinsic object to the registry service
	 * 
	 * @param catObj  a catalog object
	 * @return the guid of the registered extrinsic object 
	 */
	public String createRegistryObject(CatalogObject catObj) 
	{
		try 
		{
			// Don't ingest if the catalog object is PERSONNEL or REFERENCE
			if (catObj.getCatObjType().equalsIgnoreCase("PERSONNEL")
					|| catObj.getCatObjType().equalsIgnoreCase("REFERENCE")
					|| catObj.getCatObjType().equalsIgnoreCase("SOFTWARE")
					|| catObj.getCatObjType().equalsIgnoreCase("DATA_SET_MAP_PROJECTION")
	                || catObj.getCatObjType().equalsIgnoreCase("DATA_SET_RELEASE")) 
			{
				// TODO: need to add warning problemtype instead of using INVALID_LABEL
				LabelParserException lp = new LabelParserException(catObj.getLabel().getLabelURI(), null, null,
                        "ingest.warning.skipFile",
                        ProblemType.INVALID_LABEL_WARNING, "This file is not required to ingest into the registry.");			
				catObj.getLabel().addProblem(lp);
				return null;
			}
			
			// New product
			RegistryObject product = ProductFactory.createProduct(catObj, this);
            catObj.setVersion(99.0f);
            product.setVersionName("99.0");

			catObj.setExtrinsicObject(product);
			return product.getLid();
  		} 
		catch (Exception ex) 
		{
			LabelParserException lp = new LabelParserException(catObj.getLabel().getLabelURI(), null, null, 
        			"ingest.error.failExecution", ProblemType.EXECUTE_FAIL, "ingestExtrinsicObject");
        	catObj.getLabel().addProblem(lp);
        	failCount++;
        	
        	log.log(Level.SEVERE, "", ex);
  		}
		
		return null;
	}
	

	/**
	 * Add reference information as slot values 
	 * then, update the registered product
	 * 
	 * @param catObj a CatalogObject of the registered extrinsic object
	 * @param refs Hashmap that holds reference information
	 */
	public void setProductReferences(List<CatalogObject> catObjs, CatalogObject catObj, Map<String, List<String>> refs) 
	{
		Slots slots = null;
		
		// DATA_SET_HOUSEKEEPING should be null
		if (catObj.getExtrinsicObject() == null)
		{
			return;
		}
		
		RegistryObject product = catObj.getExtrinsicObject();
		
		if(catObj.getExtrinsicObject().getSlots() == null)
		{
			slots = new Slots();
		}
		else
		{
			slots = catObj.getExtrinsicObject().getSlots();
		}

		String catObjType = catObj.getCatObjType();   		
		String version = String.valueOf(catObj.getVersion());
		
		// currently, there is only one version for the TARGET object. 
		// will get a version from the extrinsic object...it may slow down the processing
		// TODO: check for getRefValues() return 0 size or bigger..add when its size>0
		if (catObjType.equalsIgnoreCase(Constants.MISSION_OBJ)) 
		{ 	
			if (refs.get(Constants.HAS_INSTHOST) != null)
				slots.put(Constants.HAS_INSTHOST, ReferenceUtils.getRefValues(version, Constants.HAS_INSTHOST, refs));
			if (refs.get(Constants.HAS_INST)!=null)
				slots.put(Constants.HAS_INST, ReferenceUtils.getRefValues(version, Constants.HAS_INST, refs));
			if (targetAvailable)
			{
				if(refs.get(Constants.HAS_TARGET) != null) 
				{
				    slots.put(Constants.HAS_TARGET, ReferenceUtils.getRefValues("1.0", Constants.HAS_TARGET, refs, catObj));
				}
			}
		}
		else if (catObjType.equalsIgnoreCase(Constants.INSTHOST_OBJ)) 
		{
			if (refs.get(Constants.HAS_MISSION)!=null)
				slots.put(Constants.HAS_MISSION, ReferenceUtils.getRefValues(version, Constants.HAS_MISSION, refs));
			if (refs.get(Constants.HAS_INST)!=null)
				slots.put(Constants.HAS_INST, ReferenceUtils.getRefValues(version, Constants.HAS_INST, refs));
			if (targetAvailable) 
			{
				if (refs.get(Constants.HAS_TARGET)!=null)
					slots.put(Constants.HAS_TARGET, ReferenceUtils.getRefValues("1.0", Constants.HAS_TARGET, refs));
			}
		}
		else if (catObjType.equalsIgnoreCase(Constants.INST_OBJ))
		{
			if(refs.get(Constants.HAS_INSTHOST)!=null)
			{
				slots.put(Constants.HAS_INSTHOST, ReferenceUtils.getRefValues(version, Constants.HAS_INSTHOST, refs, catObj));
			}
			
			if(refs.get(Constants.HAS_DATASET) != null)
			{
				// need to find proper dataset catalog object with given INSTRUMENT_ID
				Map<String, List<String>> dsRefs = getDSRefs(catObjs, "INSTRUMENT_ID", catObj.getMetadata().getMetadata("INSTRUMENT_ID"));
				slots.put(Constants.HAS_DATASET, ReferenceUtils.getRefValues(version, Constants.HAS_DATASET, dsRefs));
			}
		}
		else if (catObjType.equalsIgnoreCase(Constants.DATASET_OBJ)) 
		{
			// need to add only available instrument in the data set catalog file
			if (refs.get(Constants.HAS_MISSION)!=null)
				slots.put(Constants.HAS_MISSION, ReferenceUtils.getRefValues(version, Constants.HAS_MISSION, refs, catObj));
			if (targetAvailable) {
				if (refs.get(Constants.HAS_TARGET)!=null) {
					slots.put(Constants.HAS_TARGET, ReferenceUtils.getRefValues("1.0", Constants.HAS_TARGET, refs, catObj));
				}
			}
			if (refs.get(Constants.HAS_INSTHOST)!=null) {
				slots.put(Constants.HAS_INSTHOST, ReferenceUtils.getRefValues(version, Constants.HAS_INSTHOST, refs, catObj));
			}
			if (refs.get(Constants.HAS_INST)!=null) {
				slots.put(Constants.HAS_INST, ReferenceUtils.getRefValues(version, Constants.HAS_INST, refs, catObj));
			}
						
			// how to get this version properly for each resource?????
			// should only add same dataset id
			if(refs.get(Constants.HAS_RESOURCE) != null) 
			{
				Map<String, List<String>> resrcRefs = new HashMap<String, List<String>>();
				List<String> values = new ArrayList<String>();
				for (String aValue: refs.get(Constants.HAS_RESOURCE)) 
				{
					String tmpLid = aValue;
					String tmpDsid = catObj.getMetadata().getMetadata("DATA_SET_ID");
					tmpDsid = Utility.replaceChars(tmpDsid);

					if (tmpLid.contains(tmpDsid.toLowerCase()))
						values.add(aValue);
				}
			    
				if(values.size() > 0) 
				{
			    	resrcRefs.put(Constants.HAS_RESOURCE, values);
			    	slots.put(Constants.HAS_RESOURCE, ReferenceUtils.getRefValues(version, Constants.HAS_RESOURCE, resrcRefs));	
			    }
			}
			else
			{
				String dsId = catObj.getMetadata().getMetadata("DATA_SET_ID");
				dsId = Utility.replaceChars(dsId);				
				try
				{
					List<String> values = RegistryClientManager.getRegistryClient().getResourceIds(dsId);
					slots.put(Constants.HAS_RESOURCE, values);
				}
				catch(Exception ex)
				{
					log.log(Level.WARNING, "Could not get resource references for data set " + dsId, ex);
				}
			}
			
			if (refs.get(Constants.HAS_NODE)!=null) {
				slots.put(Constants.HAS_NODE, ReferenceUtils.getRefValues(version, Constants.HAS_NODE, refs));
			}
		}
		else if (catObjType.equalsIgnoreCase(Constants.TARGET_OBJ)) {
			if (refs.get(Constants.HAS_MISSION)!=null)
				slots.put(Constants.HAS_MISSION, ReferenceUtils.getRefValues(version, Constants.HAS_MISSION, refs));
			if (refs.get(Constants.HAS_INSTHOST)!=null)
				slots.put(Constants.HAS_INSTHOST, ReferenceUtils.getRefValues(version, Constants.HAS_INSTHOST, refs));
			if (refs.get(Constants.HAS_INST)!=null)
				slots.put(Constants.HAS_INST, ReferenceUtils.getRefValues(version, Constants.HAS_INST, refs));			
		}
		else if (catObjType.equalsIgnoreCase(Constants.VOLUME_OBJ)) {
			if (refs.get(Constants.HAS_DATASET)!=null)
				slots.put(Constants.HAS_DATASET, ReferenceUtils.getRefValues(version, Constants.HAS_DATASET, refs));
		}
		
		slots.put("version_id", String.valueOf(catObj.getVersion()));
		
		product.setSlots(slots);
	}

	
	private Map<String, List<String>> getDSRefs(List<CatalogObject> catObjs, String keyToLook, String valueToFind) 
	{
		Map<String, List<String>> dsRefs = new HashMap<String, List<String>>();
		List<String> values = new ArrayList<String>();
		
		for (CatalogObject catObj: catObjs) 
		{
			if (catObj.getCatObjType().equalsIgnoreCase(Constants.DATASET_OBJ)) 
			{
				Metadata md = catObj.getMetadata();				
				if (md.isMultiValued(keyToLook))
				{
					List<String> tmpValues = md.getAllMetadata(keyToLook);
					for (String instId: tmpValues)
					{
						if (instId == null) {
							log.warning("Found null value in multi-valued " + keyToLook +
							           " for DATA_SET catalog object: " + catObj.getFilename());
							continue;
						}

						if (instId.equalsIgnoreCase(valueToFind))
						{
							String dsId = md.getMetadata("DATA_SET_ID");
							dsId = Utility.replaceChars(dsId);
			    			dsId = dsId.toLowerCase();
			    			String dsLid = Constants.LID_PREFIX+"data_set:data_set." + dsId;

			    			if (!Utility.valueExists(dsLid, values))
			    			{
								values.add(dsLid);
			    			}
						}
					}
				}
				else 
				{
					String instId = md.getMetadata(keyToLook);
					if (instId == null) {
						log.warning("Missing " + keyToLook + " field in DATA_SET catalog object: " +
						           catObj.getFilename());
					} else if (instId.equalsIgnoreCase(valueToFind)) 
					{
						String dsId = md.getMetadata("DATA_SET_ID");
						dsId = Utility.replaceChars(dsId);
		    			dsId = dsId.toLowerCase();
		    			String dsLid = Constants.LID_PREFIX+"data_set:data_set." + dsId;
		    			if (!Utility.valueExists(dsLid, values)) 
		    			{
							values.add(dsLid);
		    			}
					}
				}
			}	
		}
		
		dsRefs.put(Constants.HAS_DATASET, values);
		
		return dsRefs;
	}
		
	
	public void publishObject(CatalogObject obj)
	{
      if (obj.getExtrinsicObject() == null) {
        return;
      }
		
		try {
          DocGenerator.getInstance().addDoc(obj);
          solrDocCount++;
        } catch (DocGeneratorException e) {
          LabelParserException lp = new LabelParserException(obj.getLabel().getLabelURI(), null,
              null, "ingest.warning.skipFile", ProblemType.INVALID_LABEL_WARNING, e.getMessage());
          obj.getLabel().addProblem(lp);
        } catch (IOException ex) {
          log.log(Level.SEVERE, "Unexpected error trying to generate Solr Doc.", ex);
        }
	}
}
