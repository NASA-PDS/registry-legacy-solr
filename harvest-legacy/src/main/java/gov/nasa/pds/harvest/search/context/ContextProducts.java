package gov.nasa.pds.harvest.search.context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import gov.nasa.pds.harvest.search.logging.ToolsLogRecord;

/**
 * The registered context products, keyed by lid. Built once at the start of a harvest run.
 */
public final class ContextProducts {
  public static final String CACHE_FILE_NAME = "context_products.json";

  private static final Logger log = Logger.getLogger(ContextProducts.class.getName());
  private static final int MAX_UNRESOLVED_LISTED = 20;

  private static volatile ContextProducts instance;

  private final Map<String, ContextProduct> byLid = new HashMap<String, ContextProduct>();
  private final AtomicInteger resolved = new AtomicInteger();
  private final Set<String> unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Keeps the newest version of each context product. */
  ContextProducts(Collection<ContextProduct> products) {
    for (ContextProduct product : products) {
      ContextProduct existing = byLid.get(product.getLid());
      if (existing == null || product.isNewerThan(existing)) {
        byLid.put(product.getLid(), product);
      }
    }
  }

  /**
   * Loads the context products for this harvest run.
   *
   * <p>With an explicit file, that file is used and nothing is fetched. Otherwise the products are
   * fetched from the PDS Registry API and saved to the cache file; if the API cannot be reached,
   * the cached copy from the last good run is used.
   *
   * @param explicitFile A context products file to use instead of the API, or null.
   * @param cacheFile Where the fetched products are saved between runs.
   * @param apiUrl The PDS Registry API base URL.
   * @throws ContextProductsException If there is no way to get the context products.
   */
  public static void init(File explicitFile, File cacheFile, String apiUrl)
      throws ContextProductsException {
    init(explicitFile, cacheFile, new RegistryApiClient(apiUrl));
  }

  static void init(File explicitFile, File cacheFile, RegistryApiClient client)
      throws ContextProductsException {
    String apiUrl = client.getBaseUrl();
    Collection<ContextProduct> products;

    if (explicitFile != null) {
      products = ContextProductsFile.read(explicitFile);
      info("Using " + products.size() + " context products from " + explicitFile);
    } else {
      try {
        products = client.fetchAll();
        info("Fetched " + products.size() + " context products from " + apiUrl);
        save(cacheFile, products);
      } catch (ContextProductsException e) {
        if (!cacheFile.isFile()) {
          throw new ContextProductsException("Could not fetch the context products from " + apiUrl
              + " (" + e.getMessage() + ") and there is no cached copy at " + cacheFile
              + ". Run again when the API is reachable, pass --context-products <file>, or pass"
              + " --no-context-products to use the values in the labels.", e);
        }
        products = ContextProductsFile.read(cacheFile);
        warn("Could not fetch the context products from " + apiUrl + " (" + e.getMessage()
            + "). Using the cached copy of " + products.size() + " context products from "
            + age(cacheFile) + " ago: " + cacheFile);
      }
    }

    instance = new ContextProducts(products);
  }

  /** @return The loaded context products, or null when the lookup is not in use. */
  public static ContextProducts getInstance() {
    return instance;
  }

  /** Clears the loaded context products. */
  public static void reset() {
    instance = null;
  }

  /**
   * Finds a context product by the reference in a label.
   *
   * @param ref A lid or lidvid.
   * @return The context product, or null if it is not registered.
   */
  public ContextProduct find(String ref) {
    String lid = ContextProduct.lidOf(ref);
    ContextProduct product = byLid.get(lid);
    if (product == null) {
      unresolved.add(lid);
    } else {
      resolved.incrementAndGet();
    }
    return product;
  }

  public int size() {
    return byLid.size();
  }

  /** @return A one-paragraph summary of how many references were and were not found. */
  public String summary() {
    StringBuilder text = new StringBuilder("Context products: ").append(byLid.size())
        .append(" loaded, ").append(resolved.get()).append(" references resolved, ")
        .append(unresolved.size()).append(" distinct references not found");
    if (!unresolved.isEmpty()) {
      Set<String> sorted = new TreeSet<String>(unresolved);
      int listed = 0;
      text.append(":");
      for (String lid : sorted) {
        if (listed++ == MAX_UNRESOLVED_LISTED) {
          text.append(" ...");
          break;
        }
        text.append(" ").append(lid);
      }
    }
    return text.toString();
  }

  private static void save(File cacheFile, Collection<ContextProduct> products) {
    try {
      ContextProductsFile.write(cacheFile, products);
    } catch (IOException e) {
      warn("Could not save the context products to " + cacheFile + ": " + e.getMessage());
    }
  }

  private static String age(File file) {
    try {
      Duration age = Duration.between(Files.getLastModifiedTime(file.toPath()).toInstant(),
          Instant.now());
      return age.toDays() > 0 ? age.toDays() + " day(s)" : age.toHours() + " hour(s)";
    } catch (IOException e) {
      return "an unknown time";
    }
  }

  private static void info(String message) {
    log.log(new ToolsLogRecord(Level.INFO, message));
  }

  private static void warn(String message) {
    log.log(new ToolsLogRecord(Level.WARNING, message));
  }
}
