// Copyright 2006-2010, by the California Institute of Technology.
// ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
// Any commercial use must be negotiated with the Office of Technology Transfer
// at the California Institute of Technology.
//
// This software is subject to U. S. export control laws and regulations
// (22 C.F.R. 120-130 and 15 C.F.R. 730-774). To the extent that the software
// is subject to U.S. export control laws and regulations, the recipient has
// the responsibility to obtain export licenses or other export authority as
// may be required before exporting such information to foreign countries or
// providing access to foreign nationals.
//
// $Id$
package gov.nasa.pds.harvest.crawler;

import gov.nasa.jpl.oodt.cas.crawl.ProductCrawler;
import gov.nasa.jpl.oodt.cas.crawl.action.CrawlerAction;
import gov.nasa.jpl.oodt.cas.crawl.action.CrawlerActionRepo;
import gov.nasa.jpl.oodt.cas.metadata.Metadata;
import gov.nasa.jpl.oodt.cas.metadata.exceptions.MetExtractionException;
import gov.nasa.pds.harvest.constants.Constants;
import gov.nasa.pds.harvest.crawler.actions.AssociationCheckerAction;
import gov.nasa.pds.harvest.crawler.actions.LogMissingReqMetadataAction;
import gov.nasa.pds.harvest.crawler.metadata.extractor.Pds4MetExtractorConfig;
import gov.nasa.pds.harvest.crawler.metadata.extractor.BundleMetExtractor;
import gov.nasa.pds.harvest.crawler.metadata.extractor.CollectionMetExtractor;
import gov.nasa.pds.harvest.crawler.metadata.extractor.Pds4MetExtractor;
import gov.nasa.pds.harvest.crawler.status.Status;
import gov.nasa.pds.harvest.ingest.RegistryIngester;
import gov.nasa.pds.harvest.logging.ToolsLevel;
import gov.nasa.pds.harvest.logging.ToolsLogRecord;
import gov.nasa.pds.harvest.util.XMLExtractor;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.sf.saxon.trans.XPathException;

import org.xml.sax.SAXParseException;

/**
 * Class that extends the Cas-Crawler to crawl a directory or
 * PDS inventory file and register products to the PDS Registry
 * Service.
 *
 * @author mcayanan
 *
 */
public class PDSProductCrawler extends ProductCrawler {
  /** Logger object. */
  private static Logger log = Logger.getLogger(
      PDSProductCrawler.class.getName());

  /** Holds the configuration to extract metadata. */
  private Pds4MetExtractorConfig metExtractorConfig;

  /** A list of crawler actions to perform while crawling. */
  private List<CrawlerAction> crawlerActions;

  /** Holds the product type of the file being processed. */
  private String objectType;

  /** Flag for crawler persistance. */
  protected boolean inPersistanceMode;

  /** A map of files that were touched during crawler persistance. */
  protected Map<File, Long> touchedFiles;

  /** The number of candidate products that were discovered during the crawl.
   */
  protected int numDiscoveredProducts;

  /** The number of bad files that were found during the crawl. */
  protected int numBadFiles;

  /** The number of files that were skipped during the crawl. */
  protected int numFilesSkipped;

  /**
   * Default constructor.
   *
   */
  public PDSProductCrawler() {
    this(null);
  }

  /**
   * Constructor.
   *
   * @param extractorConfig A configuration class that tells the crawler
   * what data product types to look for and what metadata to extract.
   */
  public PDSProductCrawler(Pds4MetExtractorConfig extractorConfig) {
    this.objectType = "";
    this.metExtractorConfig = extractorConfig;
    this.crawlerActions = new ArrayList<CrawlerAction>();
    inPersistanceMode = false;
    touchedFiles = new HashMap<File, Long>();
    numDiscoveredProducts = 0;
    numBadFiles = 0;
    numFilesSkipped = 0;

    String[] reqMetadata = {
        Constants.PRODUCT_VERSION,
        Constants.LOGICAL_ID,
        Constants.OBJECT_TYPE
        };
    setRequiredMetadata(Arrays.asList(reqMetadata));
    FILE_FILTER = new WildcardOSFilter("*");
    crawlerActions.add(new LogMissingReqMetadataAction(getRequiredMetadata()));
    crawlerActions.add(new AssociationCheckerAction());
  }

  /**
   * Get the MetExtractor configuration object.
   *
   * @return The PDSMetExtractorConfig object.
   */
  public Pds4MetExtractorConfig getMetExtractorConfig() {
    return metExtractorConfig;
  }

  public void setMetExtractorConfig(Pds4MetExtractorConfig config) {
    this.metExtractorConfig = config;
  }

  public void setInPersistanceMode(boolean value) {
    inPersistanceMode = value;
  }

  public int getNumDiscoveredProducts() {
    return numDiscoveredProducts;
  }

  public int getNumBadFiles() {
    return numBadFiles;
  }

  public int getNumFilesSkipped() {
    return numFilesSkipped;
  }

  public void clearCrawlStats() {
    numDiscoveredProducts = 0;
    numBadFiles = 0;
    numFilesSkipped = 0;
  }

  /**
   * Sets the registry location.
   *
   * @param url A url of the registry location.
   * @throws MalformedURLException If the given url is malformed.
   */
  public void setRegistryUrl(String url) throws MalformedURLException {
    setFilemgrUrl(url);
  }

  /**
   * Gets the registry location.
   *
   * @return A url of the registry location.
   */
  public String getRegistryUrl() {
    return getFilemgrUrl();
  }

  /**
   * Gets the registry ingester.
   *
   * @return A registry ingester object.
   */
  public RegistryIngester getRegistryIngester() {
    return (RegistryIngester) getIngester();
  }

  /**
   * Sets the file filter.
   *
   * @param filters A list of file filters.
   */
  public void setFileFilter(List<String> filters) {
    FILE_FILTER = new WildcardOSFilter(filters);
  }

  /**
   * Method not implemented at the moment.
   *
   * @param product The product file.
   * @param productMetadata The metadata associated with the product.
   */
  @Override
  protected void addKnownMetadata(File product, Metadata productMetadata) {
    //The parent class adds FILENAME, FILE_LOCATION, and PRODUCT_NAME
    //to the metadata. Not needed at the moment
  }

  /**
   * Crawls the given directory.
   *
   * @param dir The directory to crawl.
   */
  public void crawl(File dir) {
    //Load crawlerActions first before crawling
    CrawlerActionRepo repo = new CrawlerActionRepo();
    repo.loadActions(crawlerActions);
    setActionRepo(repo);
    super.crawl(dir);
  }

  /**
   * Adds a crawler action.
   *
   * @param action A crawler action.
   */
  public void addAction(CrawlerAction action) {
    this.crawlerActions.add(action);
  }

  /**
   * Adds a list of crawler actions.
   *
   * @param actions A list of crawler actions.
   */
  public void addActions(List<CrawlerAction> actions) {
    this.crawlerActions.addAll(actions);
  }

  /**
   * Gets a list of crawler actions defined for the crawler.
   *
   * @return A list of crawler actions that will be performed
   * during crawling.
   */
  public List<CrawlerAction> getActions() {
    return crawlerActions;
  }

  public void setProperties(String registryUrl, RegistryIngester ingester,
      List<CrawlerAction> actions) throws MalformedURLException {
    setRegistryUrl(registryUrl);
    setIngester(ingester);
    addActions(actions);
  }

  /**
   * Extracts metadata from the given product.
   *
   * @param product A PDS file.
   *
   * @return A Metadata object, which holds metadata from the product.
   *
   */
  @Override
  protected Metadata getMetadataForProduct(File product) {
    Pds4MetExtractor metExtractor = null;
    if (objectType.contains(Constants.BUNDLE)) {
      metExtractor = new BundleMetExtractor(metExtractorConfig);
    } else if (objectType.contains(Constants.COLLECTION)) {
      metExtractor = new CollectionMetExtractor(metExtractorConfig);
    } else {
      metExtractor = new Pds4MetExtractor(metExtractorConfig);
    }
    try {
      return metExtractor.extractMetadata(product);
    } catch (MetExtractionException m) {
      log.log(new ToolsLogRecord(ToolsLevel.SEVERE,
          "Error while gathering metadata: " + m.getMessage(), product));
      return new Metadata();
    }
  }

  /**
   * Determines whether the supplied file passes the necessary
   * pre-conditions for the file to be registered.
   *
   * @param product A file.
   *
   * @return true if the file passes.
   */
  @Override
  protected boolean passesPreconditions(File product) {
    if (inPersistanceMode) {
      if (touchedFiles.containsKey(product)) {
        long lastModified = touchedFiles.get(product);
        if (product.lastModified() == lastModified) {
          return false;
        } else {
          touchedFiles.put(product, product.lastModified());
        }
      } else {
        touchedFiles.put(product, product.lastModified());
      }
    }
    log.log(new ToolsLogRecord(ToolsLevel.INFO, "Begin processing.", product));
    boolean passFlag = true;
    objectType = "";
    XMLExtractor extractor = new XMLExtractor();
    try {
      extractor.parse(product);
    } catch (XPathException xe) {
      if (xe.getException() instanceof SAXParseException) {
        SAXParseException spe = (SAXParseException) xe.getException();
        log.log(new ToolsLogRecord(ToolsLevel.SEVERE, spe.getMessage(),
            product.toString(), spe.getLineNumber()));
      } else {
        log.log(new ToolsLogRecord(ToolsLevel.SEVERE, "Parse failure: "
            + xe.getMessage(), product));
      }
      ++numBadFiles;
      passFlag = false;
    }
    if (passFlag == false) {
      log.log(new ToolsLogRecord(ToolsLevel.NOTIFICATION, Status.BADFILE,
          product));
      ++numBadFiles;
      return false;
    } else  {
      try {
        objectType = extractor.getValueFromDoc(Constants.coreXpathsMap.get(
            Constants.OBJECT_TYPE));
        if ("".equals(objectType)) {
          log.log(new ToolsLogRecord(ToolsLevel.SKIP, "No "
              + Constants.OBJECT_TYPE + " element found.", product));
          ++numFilesSkipped;
          passFlag = false;
        } else if (metExtractorConfig.hasObjectType(objectType)) {
          log.log(new ToolsLogRecord(ToolsLevel.NOTIFICATION, Status.DISCOVERY,
              product));
           ++numDiscoveredProducts;
           passFlag = true;
        } else {
          log.log(new ToolsLogRecord(ToolsLevel.SKIP,
              "\'" + objectType + "\' is not an object type" +
              " found in the policy file.", product));
          ++numFilesSkipped;
          passFlag = false;
        }
      } catch (Exception e) {
        log.log(new ToolsLogRecord(ToolsLevel.SEVERE, "Problem getting '"
            + Constants.OBJECT_TYPE + "': " + e.getMessage(), product));
        log.log(new ToolsLogRecord(ToolsLevel.NOTIFICATION, Status.BADFILE,
            product));
        ++numBadFiles;
        return false;
      }
    }
    return passFlag;
  }
}