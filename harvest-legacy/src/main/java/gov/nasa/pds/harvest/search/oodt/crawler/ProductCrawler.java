/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.nasa.pds.harvest.search.oodt.crawler;

import java.io.File;
import java.io.FileFilter;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.common.annotations.VisibleForTesting;
import gov.nasa.pds.harvest.search.oodt.filemgr.Ingester;
import gov.nasa.pds.harvest.search.oodt.metadata.Metadata;


/**
 * An abstract base class for Product Crawling. This class provides methods to communicate with the
 * file manager and parse met files that show how to ingest a particular Product into the File
 * Manager.
 * 
 * @author mattmann (Chris Mattmann)
 * @author bfoster (Brian Foster)
 */
public abstract class ProductCrawler extends ProductCrawlerBean {

  /* our log stream */
  protected static Logger LOG = Logger.getLogger(ProductCrawler.class.getName());

  // filter to only find directories when doing a listFiles
  protected static FileFilter DIR_FILTER = new FileFilter() {
    public boolean accept(File file) {
      return file.isDirectory();
    }
  };

  // filter to only find product files, not met files
  protected static FileFilter FILE_FILTER = new FileFilter() {
    public boolean accept(File file) {
      return file.isFile();
    }
  };

  protected List<IngestStatus> ingestStatus = new Vector<IngestStatus>();
  protected CrawlerActionRepo actionRepo;
  protected Ingester ingester;

  public void crawl() {
    crawl(new File(getProductPath()));
  }

  public void crawl(File dirRoot) {
    // Reset ingest status.
    ingestStatus.clear();

    // Load actions.
    loadAndValidateActions();

    // Create Ingester.
    // setupIngester();

    // Verify valid crawl directory.
    if (dirRoot == null || !dirRoot.exists()) {
      throw new IllegalArgumentException("dir root is null or non existant!");
    }

    // Start crawling.
    Stack<File> stack = new Stack<File>();
    stack.push(dirRoot.isDirectory() ? dirRoot : dirRoot.getParentFile());
    while (!stack.isEmpty()) {
      File dir = (File) stack.pop();
      LOG.log(Level.FINE, "Crawling " + dir);

      File[] productFiles;
      productFiles = isCrawlForDirs() ? dir.listFiles(DIR_FILTER) : dir.listFiles(FILE_FILTER);

      if (productFiles != null) {
        for (File productFile : productFiles) {
          ingestStatus.add(handleFile(productFile));
        }
      }

      if (!isNoRecur()) {
        File[] subdirs = dir.listFiles(DIR_FILTER);
        if (subdirs != null) {
          for (File subdir : subdirs) {
            stack.push(subdir);
          }
        }
      }
    }
  }

  public IngestStatus handleFile(File product) {
    LOG.log(Level.FINE, "Handling file " + product);

    // Check preconditions.
    if (!passesPreconditions(product)) {
      LOG.log(Level.WARNING, "Failed to pass preconditions for ingest of product: ["
          + product.getAbsolutePath() + "]");
      return createIngestStatus(product, IngestStatus.Result.PRECONDS_FAILED,
          "Failed to pass preconditions");
    }

    // Generate Metadata for product.
    Metadata productMetadata = new Metadata();
    productMetadata.addMetadata(getGlobalMetadata());
    try {
      productMetadata.replaceMetadata(getMetadataForProduct(product));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to get metadata for product : " + e.getMessage(), e);
      performPostIngestOnFailActions(product, productMetadata);
      return createIngestStatus(product, IngestStatus.Result.FAILURE,
          "Failed to get metadata for product : " + e.getMessage());
    }

    // Run preIngest actions.
    if (!performPreIngestActions(product, productMetadata)) {
      performPostIngestOnFailActions(product, productMetadata);
      return createIngestStatus(product, IngestStatus.Result.FAILURE,
          "PreIngest actions failed to complete");
    }

    // Check if ingest has been turned off.
    if (isSkipIngest()) {
      LOG.log(Level.FINE, "Skipping ingest of product: [" + product.getAbsolutePath() + "]");
      return createIngestStatus(product, IngestStatus.Result.SKIPPED, "Crawler ingest turned OFF");
    }

    // Ingest product.
    boolean ingestSuccess = ingest(product, productMetadata);

    // On Successful Ingest.
    if (ingestSuccess) {
      LOG.log(Level.FINE, "Successful ingest of product: [" + product.getAbsolutePath() + "]");
      performPostIngestOnSuccessActions(product, productMetadata);
      return createIngestStatus(product, IngestStatus.Result.SUCCESS, "Ingest was successful");

      // On Failed Ingest.
    } else {
      LOG.log(Level.WARNING, "Failed to ingest product: [" + product.getAbsolutePath()
          + "]: performing postIngestFail actions");
      performPostIngestOnFailActions(product, productMetadata);
      return createIngestStatus(product, IngestStatus.Result.FAILURE, "Failed to ingest product");
    }
  }

  public List<IngestStatus> getIngestStatus() {
    return Collections.unmodifiableList(ingestStatus);
  }

  protected abstract boolean passesPreconditions(File product);

  protected abstract Metadata getMetadataForProduct(File product) throws Exception;

  @VisibleForTesting
  void loadAndValidateActions() {
    if (actionRepo == null && getApplicationContext() != null) {
      actionRepo = new CrawlerActionRepo();
      actionRepo.loadActionsFromBeanFactory(getApplicationContext(), getActionIds());
      validateActions();
    }
  }

  @VisibleForTesting
  void validateActions() {
    StringBuilder actionErrors = new StringBuilder("");
    for (CrawlerAction action : actionRepo.getActions()) {
      try {
        action.validate();
      } catch (Exception e) {
        actionErrors.append(" ").append(action.getId()).append(": ").append(e.getMessage())
            .append("\n");
      }
    }
    if (actionErrors.length() > 0) {
      throw new RuntimeException("Actions failed validation:\n" + actionErrors);
    }
  }

  @VisibleForTesting
  synchronized boolean containsRequiredMetadata(Metadata productMetadata) {
    for (String reqMetKey : getRequiredMetadata()) {
      if (!productMetadata.containsKey(reqMetKey)) {
        LOG.log(Level.WARNING, "Missing required metadata field " + reqMetKey);
        return false;
      }
    }
    return true;
  }

  @VisibleForTesting
  IngestStatus createIngestStatus(final File product, final IngestStatus.Result result,
      final String message) {
    return new IngestStatus() {
      public File getProduct() {
        return product;
      }

      public Result getResult() {
        return result;
      }

      public String getMessage() {
        return message;
      }
    };
  }

  @VisibleForTesting
  boolean ingest(File product, Metadata productMetdata) {
    try {
      String productId = ingester.ingest(new URL(getFilemgrUrl()), product, productMetdata);
      LOG.log(Level.FINE,
          "Successfully ingested product: [" + product + "]: product id: " + productId);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "ProductCrawler: Exception ingesting product: [" + product
          + "]: Message: " + e.getMessage() + ": attempting to continue crawling", e);
      return false;
    }
    return true;
  }

  @VisibleForTesting
  boolean performPreIngestActions(File product, Metadata productMetadata) {
    if (actionRepo != null) {
      return performProductCrawlerActions(actionRepo.getPreIngestActions(), product,
          productMetadata);
    } else {
      return true;
    }
  }

  @VisibleForTesting
  boolean performPostIngestOnSuccessActions(File product, Metadata productMetadata) {
    if (actionRepo != null) {
      return performProductCrawlerActions(actionRepo.getPostIngestOnSuccessActions(), product,
          productMetadata);
    } else {
      return true;
    }
  }

  @VisibleForTesting
  boolean performPostIngestOnFailActions(File product, Metadata productMetadata) {
    if (actionRepo != null) {
      return performProductCrawlerActions(actionRepo.getPostIngestOnFailActions(), product,
          productMetadata);
    } else {
      return true;
    }
  }

  @VisibleForTesting
  boolean performProductCrawlerActions(List<CrawlerAction> actions, File product,
      Metadata productMetadata) {
    boolean allSucceeded = true;
    for (CrawlerAction action : actions) {
      try {
        LOG.fine("Performing action (id = " + action.getId() + " : description = "
            + action.getDescription() + ")");
        if (!action.performAction(product, productMetadata)) {
          throw new Exception("Action (id = " + action.getId() + " : description = "
              + action.getDescription() + ") returned false");
        }
      } catch (Exception e) {
        allSucceeded = false;
        LOG.log(Level.WARNING, "Failed to perform crawler action : " + e.getMessage(), e);
      }
    }
    return allSucceeded;
  }

  public void setActionRepo(CrawlerActionRepo repo) {
    this.actionRepo = repo;
  }

  public void setIngester(Ingester ingester) {
    this.ingester = ingester;
  }
}

