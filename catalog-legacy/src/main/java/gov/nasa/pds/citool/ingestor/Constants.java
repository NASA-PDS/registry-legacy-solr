package gov.nasa.pds.citool.ingestor;

import java.util.HashMap;
import java.util.Map;
/**
 * Class that holds constants used in ingest.
 *
 * @author hyunlee
 *
 */
public class Constants {
   
    public static final String LID_PREFIX = "urn:nasa:pds:context_pds3:";
    
    public static final String RESOURCE_LID_SUFFIX = "_resource_info";

    public static final String TARGET_PROD = "Product_Target_PDS3";
    
    public static final String MISSION_PROD = "Product_Mission_PDS3";
    
    public static final String INST_PROD = "Product_Instrument_PDS3";
    
    public static final String INSTHOST_PROD = "Product_Instrument_Host_PDS3";
    
    public static final String DS_PROD = "Product_Data_Set_PDS3";
    
    public static final String GUEST_PROD = "Product_PDS_Guest";
    
    public static final String AFFIL_PROD = "Product_PDS_Affiliate";

    // or Collection_Volume_Set_PDS3 ????
    public static final String VOLUME_PROD = "Product_Volume_PDS3";
    
    public static final String FILE_PROD = "Product_File_Repository";
    
    public static final String RESOURCE_PROD = "Product_Resource";
    
    public static final String CONTEXT_PROD = "Product_Context";
    
    public static final String MISSION_OBJ = "MISSION";
    
    public static final String DATASET_OBJ = "DATA_SET";
    
    public static final String INST_OBJ = "INSTRUMENT";
    
    public static final String INSTHOST_OBJ = "INSTRUMENT_HOST";
    
    public static final String TARGET_OBJ = "TARGET";
    
    public static final String RESOURCE_OBJ = "RESOURCE";
    
    public static final String HK_OBJ = "DATA_SET_HOUSEKEEPING";
    
    public static final String RELEASE_OBJ = "DATA_SET_RELEASE";
    
    public static final String VOLUME_OBJ = "VOLUME";
    
    public static final String HAS_MISSION = "investigation_ref";
    
    public static final String HAS_INST = "instrument_ref";
    
    public static final String HAS_INSTHOST = "instrument_host_ref";
    
    //??????
    public static final String HAS_DATASET = "data_set_ref";
    
    public static final String HAS_TARGET = "target_ref";
    
    public static final String HAS_RESOURCE = "resource_ref";

    public static final String HAS_FILE = "file_ref";
    
    public static final String HAS_NODE = "node_ref";

    public static final String PRODUCT_VERSION = "version_id";
    
   
    /** Mapping of PDS3 to PDS4 names */
    public static final Map<String, String> pds3ToPds4Map = new HashMap<String, String>();

      static {
        pds3ToPds4Map.put("VOLUMES",  "volume_size");
        pds3ToPds4Map.put("START_TIME", "data_set_start_date_time");
        pds3ToPds4Map.put("STOP_TIME", "data_set_stop_date_time");
        pds3ToPds4Map.put("CITATION_DESC", "data_set_citation_text");
        pds3ToPds4Map.put("REFERENCE_KEY_ID", "external_reference_description");
        pds3ToPds4Map.put("PRODUCER_FULL_NAME", "data_set_producer_full_name");
        pds3ToPds4Map.put("LABEL_REVISION_NOTE", "modification_description");
        pds3ToPds4Map.put("CONFIDENCE_LEVEL_NOTE", "data_set_confidence_level_note");
        pds3ToPds4Map.put("ARCHIVE_STATUS", "data_set_archive_status");
        pds3ToPds4Map.put("MEDIUM_TYPE", "volume_medium_type");
        pds3ToPds4Map.put("NSSDC_COLLECTION_ID", "data_set_nssdc_collection_id");
        pds3ToPds4Map.put("ABSTRACT_DESC", "data_set_abstract_description"); 
        pds3ToPds4Map.put("PUBLICATION_DATE", "volume_publication_date");
        pds3ToPds4Map.put("DESCRIPTION", "volume_description");
        pds3ToPds4Map.put("MISSION_ALIAS_NAME", "alternate_title");
        pds3ToPds4Map.put("RESOURCE_LINK", "resource_url");
      } 

      /** Mapping of PDS3 to PDS4 names */
      public static final Map<String, String> nodeValueToIdMap = new HashMap<String, String>();

      static {
        nodeValueToIdMap.put("atmos", "planetary atmospheres");
        nodeValueToIdMap.put("geoscience", "geosciences");
        nodeValueToIdMap.put("isas/jaxa", "jaxa");
        nodeValueToIdMap.put("naif", "navigation and ancillary information facility");
        nodeValueToIdMap.put("ppi", "planetary plasma interactions");
        nodeValueToIdMap.put("ppi-ucla", "planetary plasma interactions");
        nodeValueToIdMap.put("rings", "planetary rings");
        nodeValueToIdMap.put("sbn", "small bodies");
        nodeValueToIdMap.put("esa", "planetary science archive");
        nodeValueToIdMap.put("rs", "radio science");
        nodeValueToIdMap.put("rad", "radio science");
        nodeValueToIdMap.put("imaging-jpl", "imaging");
      }
}
