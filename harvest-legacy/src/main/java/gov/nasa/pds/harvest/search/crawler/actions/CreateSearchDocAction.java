package gov.nasa.pds.harvest.search.crawler.actions;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.xml.bind.JAXBException;
import gov.nasa.pds.harvest.search.constants.Constants;
import gov.nasa.pds.harvest.search.doc.SearchDocGenerator;
import gov.nasa.pds.harvest.search.doc.SearchDocState;
import gov.nasa.pds.harvest.search.logging.ToolsLevel;
import gov.nasa.pds.harvest.search.logging.ToolsLogRecord;
import gov.nasa.pds.harvest.search.oodt.crawler.CrawlerAction;
import gov.nasa.pds.harvest.search.oodt.filemgr.CrawlerActionPhases;
import gov.nasa.pds.harvest.search.oodt.filemgr.exceptions.CrawlerActionException;
import gov.nasa.pds.harvest.search.oodt.metadata.Metadata;
import gov.nasa.pds.harvest.search.util.Utility;
import gov.nasa.pds.registry.model.ExtrinsicObject;
import gov.nasa.pds.registry.model.Slot;
import gov.nasa.pds.search.core.exception.SearchCoreException;
import gov.nasa.pds.search.core.exception.SearchCoreFatalException;

/**
 * Class that generates the Solr docs.
 * 
 * @author mcayanan
 *
 */
public class CreateSearchDocAction extends CrawlerAction {

  /** The crawler action identifier. */
  private final static String ID = "CreateSearchDocAction";

  /** The crawler action description. */
  private final static String DESCRIPTION = "Creates Search Documents "
    + "needed by the Search Service.";
  
  private SearchDocGenerator generator;
  
  private boolean cacheCollection;
  
  private SearchDocState searchDocState;
  
  public CreateSearchDocAction(File configDir, File outputDir, SearchDocState searchDocState)
          throws SearchCoreException, SearchCoreFatalException {
    this.generator = new SearchDocGenerator(configDir, outputDir);
    String[] phases = {CrawlerActionPhases.POST_INGEST_SUCCESS.getName()};
    setPhases(Arrays.asList(phases));
    setId(ID);
    setDescription(DESCRIPTION);
    this.cacheCollection = false;
    this.searchDocState = searchDocState;
  }
  
  @Override
  public boolean performAction(File product, Metadata productMetadata)
      throws CrawlerActionException {
    try {
      ExtrinsicObject extrinsic = createProduct(productMetadata, product);
      this.generator.generate(extrinsic, productMetadata, this.searchDocState);
      String lidvid = extrinsic.getLid() + "::" + extrinsic.getSlot(
          Constants.PRODUCT_VERSION).getValues().get(0);
      LOG.log(new ToolsLogRecord(ToolsLevel.SUCCESS, 
          "Successfully generated document file for " + lidvid + ".", product));
      if (cacheCollection && "Product_Collection".equalsIgnoreCase(
          productMetadata.getMetadata(Constants.PRODUCT_CLASS))) {
        Constants.collectionMap.put(extrinsic.getLid(), extrinsic);
      }
    } catch (Exception e) {
       LOG.log(new ToolsLogRecord(ToolsLevel.SEVERE, 
           "Exception generating document: " + e.getMessage(), product));
      return false;
    }
    return true;
  }

  /**
   * Create the Product object.
   *
   * @param metadata A class representation of the metdata.
   *
   * @return A Product object.
   */
  private ExtrinsicObject createProduct(Metadata metadata, File prodFile) {
    ExtrinsicObject product = new ExtrinsicObject();
    Set<Slot> slots = new HashSet<Slot>();
    List<String> keys = metadata.getAllKeys();
    for (String key : keys) {
      if (key.equals(Constants.REFERENCES)
          || key.equals(Constants.INCLUDE_PATHS) 
          || key.equals(Constants.SLOT_METADATA)
          || key.equals("file_ref")) {
        continue;
      }
      if (key.equals(Constants.LOGICAL_ID)) {
        product.setLid(metadata.getMetadata(Constants.LOGICAL_ID));
      } else if (key.equals(Constants.PRODUCT_VERSION)) {
        slots.add(new Slot(Constants.PRODUCT_VERSION,
            Arrays.asList(new String[]{
                metadata.getMetadata(Constants.PRODUCT_VERSION)}
            )));
      } else if (key.equals(Constants.OBJECT_TYPE)) {
        product.setObjectType(metadata.getMetadata(
             Constants.OBJECT_TYPE));
      } else if (key.equals(Constants.TITLE)) {
        product.setName(metadata.getMetadata(Constants.TITLE));
      } else if (key.startsWith(Constants.SLOT_METADATA)) {
        slots.add(new Slot(key.split("/")[1], metadata.getAllMetadata(key)));
      } else if (key.startsWith("file_ref")) {
        slots.add(new Slot(key.replace("/", "_"), metadata.getAllMetadata(key)));
      } else {
        LOG.log(new ToolsLogRecord(ToolsLevel.WARNING,
            "Creating unexpected slot: " + key, prodFile));
        List<String> values = new ArrayList<String>();
        if (metadata.isMultiValued(key)) {
          values.addAll(metadata.getAllMetadata(key));
        } else {
          values.add(metadata.getMetadata(key));
        }
        slots.add(new Slot(key, values));
      }
      product.setSlots(slots);
    }
    if (LOG.getParent().getHandlers()[0].getLevel().intValue()
        <= ToolsLevel.DEBUG.intValue()) {
      try {
      LOG.log(new ToolsLogRecord(ToolsLevel.DEBUG,
        "Extrinsic object contents: \n" + Utility.toXML(product)));
      } catch (JAXBException je) {
        LOG.log(new ToolsLogRecord(ToolsLevel.SEVERE, je.getMessage()));
      }
    }
    return product;
  }
  
  public void setCacheCollection(boolean flag) {
    
  }
  
  
}
