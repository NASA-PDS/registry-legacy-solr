package gov.nasa.pds.harvest.search;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import gov.nasa.pds.harvest.search.crawler.CollectionCrawler;
import gov.nasa.pds.harvest.search.crawler.PDSProductCrawler;
import gov.nasa.pds.harvest.search.crawler.actions.CreateAccessUrlsAction;
import gov.nasa.pds.harvest.search.crawler.actions.CreateSearchDocAction;
import gov.nasa.pds.harvest.search.crawler.actions.FileObjectRegistrationAction;
import gov.nasa.pds.harvest.search.crawler.metadata.extractor.Pds4MetExtractorConfig;
import gov.nasa.pds.harvest.search.doc.SearchDocState;
import gov.nasa.pds.harvest.search.file.ChecksumManifest;
import gov.nasa.pds.harvest.search.ingest.SearchIngester;
import gov.nasa.pds.harvest.search.oodt.crawler.CrawlerAction;
import gov.nasa.pds.harvest.search.oodt.filemgr.exceptions.ConnectionException;
import gov.nasa.pds.harvest.search.oodt.filemgr.exceptions.CrawlerActionException;
import gov.nasa.pds.harvest.search.policy.Manifest;
import gov.nasa.pds.harvest.search.policy.Policy;
import gov.nasa.pds.search.core.exception.SearchCoreException;
import gov.nasa.pds.search.core.exception.SearchCoreFatalException;

/**
 * Front end class to the Harvest tool.
 *
 * @author mcayanan
 *
 */
public class HarvesterSearch {

  /**
   * The port number to use for the daemon if running Harvest in continuous mode.
   */
  private int daemonPort;

  /**
   * The wait interval in seconds in between crawls when running Harvest in continuous mode.
   */
  private int waitInterval;

  /** CrawlerAction that performs file object registration. */
  private FileObjectRegistrationAction fileObjectRegistrationAction;

  private File configDir;
  private File outputDir;

  /** An ingester for the PDS Registry/Search Service. */
  private SearchIngester ingester;
  private String searchUrl;
  private SearchDocState searchDocState;

  /**
   * Constructor.
   *
   * @param searchUrl The search service location.
   * @param configDir Top level directory to the Search Core configuration files.
   * @param outputDir Directory location of the generated Solr documents.
   * @param resources JSON file used as a lookup table for populating resources.
   *
   *
   */
  public HarvesterSearch(String searchUrl, File configDir, File outputDir, File resources)
      throws Exception {
    this.daemonPort = -1;
    this.waitInterval = -1;
    this.fileObjectRegistrationAction = new FileObjectRegistrationAction();
    this.configDir = configDir;
    this.outputDir = outputDir;
    this.ingester = new SearchIngester();
    this.searchUrl = searchUrl;
    this.searchDocState = new SearchDocState();
  }

  /**
   * Sets the daemon port.
   *
   * @param port The port number to use.
   */
  public void setDaemonPort(int port) {
    this.daemonPort = port;
  }

  /**
   * Sets the wait interval in seconds in between crawls.
   *
   * @param interval The wait interval in seconds.
   */
  public void setWaitInterval(int interval) {
    this.waitInterval = interval;
  }

  /**
   * Get the default crawler actions.
   *
   * @return A list of default crawler actions.
   * @throws ConnectionException
   * @throws MalformedURLException
   * @throws SearchCoreFatalException
   * @throws SearchCoreException
   */
  private List<CrawlerAction> getDefaultCrawlerActions(Policy policy, PDSProductCrawler crawler)
      throws MalformedURLException, ConnectionException, SearchCoreException,
      SearchCoreFatalException, CrawlerActionException {
    List<CrawlerAction> ca = new ArrayList<CrawlerAction>();
    List<CrawlerAction> fileObjectRegistrationActions = new ArrayList<CrawlerAction>();
    ca.add(fileObjectRegistrationAction);
    // if (policy.getStorageIngestion() != null) {
    // // I don't think we need to support this anymore, but leaving
    // // the code in for now in case it turns out we do
    //
    // CrawlerAction fmAction =
    // new StorageIngestAction(new URL(policy.getStorageIngestion().getServerUrl()));
    // ca.add(fmAction);
    // fileObjectRegistrationActions.add(fmAction);
    // throw new CrawlerActionException("Unsupported Crawler Action");
    // }
    if ((policy.getAccessUrls().isRegisterFileUrls() != false)
        || (!policy.getAccessUrls().getAccessUrl().isEmpty())) {
      CreateAccessUrlsAction cauAction =
          new CreateAccessUrlsAction(policy.getAccessUrls().getAccessUrl());
      cauAction.setRegisterFileUrls(policy.getAccessUrls().isRegisterFileUrls());
      ca.add(cauAction);
      fileObjectRegistrationActions.add(cauAction);
    }
    fileObjectRegistrationAction.setActions(fileObjectRegistrationActions);
    // Set the flag to generate checksums
    fileObjectRegistrationAction.setGenerateChecksums(policy.getChecksums().isGenerate());
    fileObjectRegistrationAction.setFileTypes(policy.getFileTypes());

    CreateSearchDocAction createSearchDoc =
        new CreateSearchDocAction(configDir, outputDir, this.searchDocState);
    if (crawler instanceof CollectionCrawler) {
      createSearchDoc.setCacheCollection(true);
    }
    ca.add(createSearchDoc);
    return ca;
  }

  /**
   * Harvest the products specified in the given policy.
   *
   * @param policy An object representation of the configuration file that specifies what to
   *        harvest.
   *
   * @throws ConnectionException
   * @throws IOException
   * @throws SearchCoreFatalException
   * @throws SearchCoreException
   * @throws CrawlerActionException
   */
  public void harvest(Policy policy) throws IOException, ConnectionException, SearchCoreException,
      SearchCoreFatalException, CrawlerActionException {
    boolean doCrawlerPersistance = false;
    Map<File, String> checksums = new HashMap<File, String>();
    if (policy.getChecksums().getManifest() != null) {
      Manifest manifest = policy.getChecksums().getManifest();
      ChecksumManifest cm = new ChecksumManifest(manifest.getBasePath());
      checksums.putAll(cm.read(new File(manifest.getValue())));
      fileObjectRegistrationAction.setChecksumManifest(checksums);
    }
    if (waitInterval != -1 && daemonPort != -1) {
      doCrawlerPersistance = true;
    }
    Pds4MetExtractorConfig pds4MetExtractorConfig = new Pds4MetExtractorConfig(
        policy.getCandidates().getProductMetadata(), policy.getReferences());
    List<PDSProductCrawler> crawlers = new ArrayList<PDSProductCrawler>();

    // Crawl collections
    for (String collection : policy.getCollections().getFile()) {
      CollectionCrawler cc = new CollectionCrawler(pds4MetExtractorConfig);
      cc.setProductPath(collection);

      crawlers.add(cc);
    }
    // Crawl directories
    for (String directory : policy.getDirectories().getPath()) {
      PDSProductCrawler pc = new PDSProductCrawler(pds4MetExtractorConfig);
      pc.setProductPath(directory);
      if (policy.getDirectories().getFileFilter() != null) {
        pc.setFileFilter(policy.getDirectories().getFileFilter());
      }
      if (policy.getDirectories().getDirectoryFilter() != null) {
        pc.setDirectoryFilter(policy.getDirectories().getDirectoryFilter());
      }
      crawlers.add(pc);
    }

    // Perform crawl while looping through the crawler list if
    // crawler persistance is disabled.
    for (PDSProductCrawler crawler : crawlers) {
      crawler.addActions(getDefaultCrawlerActions(policy, crawler));
      crawler.setSearchUrl(searchUrl);
      crawler.setIngester(ingester);
      crawler.setCounter(this.searchDocState);
      crawler.crawl();
    }

  }
}
