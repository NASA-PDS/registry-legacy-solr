package gov.nasa.pds.harvest.search.context;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Fetches every registered context product, with the names, types and aliases harvest needs, from
 * the PDS Registry API.
 */
public class RegistryApiClient {
  public static final String DEFAULT_API_URL = "https://pds.mcp.nasa.gov/api";

  private static final String PRODUCTS_PATH = "/search/1/products";
  private static final String QUERY = "product_class eq \"Product_Context\"";
  private static final int DEFAULT_PAGE_SIZE = 1000;
  private static final long DEFAULT_RETRY_PAUSE_MILLIS = 1000L;
  private static final int ATTEMPTS = 3;
  private static final String[] NAME_KEYS = {"pds:Target.pds:name", "pds:Instrument.pds:name",
      "pds:Instrument_Host.pds:name", "pds:Investigation.pds:name"};
  private static final String[] TYPE_KEYS = {"pds:Target.pds:type", "pds:Instrument_Host.pds:type",
      "pds:Investigation.pds:type"};
  private static final String INSTRUMENT_TYPE_KEY = "ctli:Type_List.ctli:type";
  private static final String LEGACY_INSTRUMENT_TYPE_KEY = "pds:Instrument.pds:type";
  private static final String[] ALIAS_KEYS = {"pds:Alias.pds:alternate_title",
      "pds:Alias.pds:alternate_id"};
  private static final String FIELDS = "lidvid,title," + String.join(",", NAME_KEYS) + ","
      + String.join(",", TYPE_KEYS) + "," + INSTRUMENT_TYPE_KEY + "," + LEGACY_INSTRUMENT_TYPE_KEY
      + "," + String.join(",", ALIAS_KEYS);

  private final String baseUrl;
  private final int pageSize;
  private final long retryPauseMillis;
  private final HttpClient http;

  /**
   * @param baseUrl The API base URL, for example https://pds.mcp.nasa.gov/api.
   */
  public RegistryApiClient(String baseUrl) {
    this(baseUrl, DEFAULT_PAGE_SIZE, DEFAULT_RETRY_PAUSE_MILLIS);
  }

  /** Lets tests use small pages and short pauses. */
  RegistryApiClient(String baseUrl, int pageSize, long retryPauseMillis) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.pageSize = pageSize;
    this.retryPauseMillis = retryPauseMillis;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30)).build();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Fetches all context products, one page at a time.
   *
   * @return The context products.
   * @throws ContextProductsException If the API cannot be reached or returns something unexpected.
   */
  public List<ContextProduct> fetchAll() throws ContextProductsException {
    List<ContextProduct> products = new ArrayList<ContextProduct>();
    String searchAfter = null;
    long hits = -1;

    while (true) {
      JsonObject page = get(pageUrl(searchAfter));
      JsonArray data = page.has("data") && page.get("data").isJsonArray()
          ? page.getAsJsonArray("data") : new JsonArray();
      if (hits < 0 && page.has("summary")) {
        hits = page.getAsJsonObject("summary").get("hits").getAsLong();
      }
      if (data.size() == 0) {
        break;
      }
      String last = null;
      for (JsonElement element : data) {
        ContextProduct product = parse(element.getAsJsonObject());
        products.add(product);
        last = product.getLidvid();
      }
      if (data.size() < pageSize || (hits >= 0 && products.size() >= hits)
          || last.equals(searchAfter)) {
        break;
      }
      searchAfter = last;
    }

    if (products.isEmpty()) {
      throw new ContextProductsException(
          "The PDS Registry API at " + baseUrl + " returned no context products");
    }
    return products;
  }

  private String pageUrl(String searchAfter) {
    StringBuilder url = new StringBuilder(baseUrl).append(PRODUCTS_PATH);
    url.append("?q=").append(encode(QUERY));
    url.append("&fields=").append(encode(FIELDS));
    url.append("&sort=lidvid&limit=").append(pageSize);
    if (searchAfter != null) {
      url.append("&search-after=").append(encode(searchAfter));
    }
    return url.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private JsonObject get(String url) throws ContextProductsException {
    ContextProductsException failure = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return JsonParser.parseString(response.body()).getAsJsonObject();
        }
        failure = new ContextProductsException(
            "The PDS Registry API returned HTTP " + response.statusCode() + " for " + baseUrl);
        if (response.statusCode() < 500) {
          throw failure;
        }
      } catch (IllegalArgumentException e) {
        // A URL that cannot be parsed will not work on a retry either
        throw new ContextProductsException("The PDS Registry API URL is not valid: " + baseUrl, e);
      } catch (IOException | JsonParseException | IllegalStateException e) {
        failure = new ContextProductsException("Could not get a response from the PDS Registry API at "
            + baseUrl + ": " + e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ContextProductsException("Interrupted while calling the PDS Registry API", e);
      }
      pause(attempt);
    }
    throw failure;
  }

  private void pause(int attempt) throws ContextProductsException {
    try {
      Thread.sleep(retryPauseMillis * attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ContextProductsException("Interrupted while calling the PDS Registry API", e);
    }
  }

  private static ContextProduct parse(JsonObject item) {
    JsonObject properties = item.has("properties") ? item.getAsJsonObject("properties")
        : new JsonObject();
    String lidvid = item.has("id") ? item.get("id").getAsString()
        : values(properties, "lidvid").get(0);
    String title = item.has("title") && !item.get("title").isJsonNull()
        ? item.get("title").getAsString() : "";

    List<String> names = values(properties, NAME_KEYS);
    List<String> types = values(properties, TYPE_KEYS);
    List<String> instrumentTypes = values(properties, INSTRUMENT_TYPE_KEY);
    types.addAll(instrumentTypes.isEmpty() ? values(properties, LEGACY_INSTRUMENT_TYPE_KEY)
        : instrumentTypes);
    return new ContextProduct(lidvid, title, names, types, values(properties, ALIAS_KEYS));
  }

  /** Collects the values of the given keys. The API returns the string "null" for empty values. */
  private static List<String> values(JsonObject properties, String... keys) {
    List<String> values = new ArrayList<String>();
    for (String key : keys) {
      if (properties.has(key) && properties.get(key).isJsonArray()) {
        for (JsonElement element : properties.getAsJsonArray(key)) {
          String value = element.isJsonNull() ? "" : element.getAsString().trim();
          if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
            values.add(value);
          }
        }
      }
    }
    return values;
  }
}
