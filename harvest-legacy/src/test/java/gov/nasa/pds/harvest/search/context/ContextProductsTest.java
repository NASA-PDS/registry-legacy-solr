package gov.nasa.pds.harvest.search.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the Registry API client and the choice between fetching, the cache and an explicit file. */
class ContextProductsTest {
  private static final String PLANET = "urn:nasa:pds:context:target:planet.test";

  /** Context products in lidvid order, as the API returns them. The API reports "null" for empty values. */
  private static final List<String[]> ITEMS = Arrays.asList(
      new String[] {"urn:nasa:pds:context:instrument:acc.test::1.0", "Test Accelerometer",
          "\"pds:Instrument.pds:name\":[\"Test Accel\"],\"pds:Instrument.pds:type\":[\"null\"],"
              + "\"ctli:Type_List.ctli:type\":[\"Accelerometer\"],"
              + "\"pds:Alias.pds:alternate_title\":[\"ACC\",\"null\"],"
              + "\"pds:Alias.pds:alternate_id\":[\"null\"]"},
      new String[] {"urn:nasa:pds:context:instrument:old.test::1.0", "Old Spectrometer",
          "\"pds:Instrument.pds:name\":[\"Old Spec\"],\"pds:Instrument.pds:type\":[\"Spectrometer\"],"
              + "\"ctli:Type_List.ctli:type\":[\"null\"]"},
      new String[] {"urn:nasa:pds:context:investigation:mission.test::1.1", "Test Mission",
          "\"pds:Investigation.pds:name\":[\"TEST MISSION\"],"
              + "\"pds:Investigation.pds:type\":[\"Mission\"]"},
      new String[] {PLANET + "::1.0", "Old Test Planet",
          "\"pds:Target.pds:name\":[\"Old Test Planet\"],\"pds:Target.pds:type\":[\"Planet\"]"},
      new String[] {PLANET + "::1.2", "Test Planet",
          "\"pds:Target.pds:name\":[\"Test Planet\"],\"pds:Target.pds:type\":[\"Planet\"],"
              + "\"pds:Alias.pds:alternate_title\":[\"Red Dot\"],"
              + "\"pds:Alias.pds:alternate_id\":[\"PLANET-1\"]"});

  @TempDir
  Path directory;

  private HttpServer server;
  private String apiUrl;

  @BeforeEach
  void startServer() throws IOException {
    ContextProducts.reset();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/api/search/1/products", this::respond);
    server.start();
    apiUrl = "http://localhost:" + server.getAddress().getPort() + "/api";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    ContextProducts.reset();
  }

  /** Answers like the Registry API: items after search-after, at most limit of them. */
  private void respond(HttpExchange exchange) throws IOException {
    Map<String, String> query = new HashMap<String, String>();
    for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
      String[] keyValue = pair.split("=", 2);
      query.put(keyValue[0], URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
    }
    String after = query.get("search-after");
    int limit = Integer.parseInt(query.get("limit"));

    List<String> page = new ArrayList<String>();
    for (String[] item : ITEMS) {
      if (page.size() < limit && (after == null || item[0].compareTo(after) > 0)) {
        page.add("{\"id\":\"" + item[0] + "\",\"type\":\"Product_Context\",\"title\":\"" + item[1]
            + "\",\"properties\":{" + item[2] + "}}");
      }
    }
    byte[] body = ("{\"summary\":{\"hits\":" + ITEMS.size() + "},\"data\":[" + String.join(",", page)
        + "]}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private RegistryApiClient client() {
    return new RegistryApiClient(apiUrl, 2, 1);
  }

  private File cacheFile() {
    return directory.resolve("context_products.json").toFile();
  }

  @Test
  void fetchesEveryPageAndDropsEmptyValues() throws ContextProductsException {
    List<ContextProduct> products = client().fetchAll();

    assertEquals(5, products.size());
    ContextProduct accelerometer = products.get(0);
    assertEquals("Test Accelerometer", accelerometer.getTitle());
    assertEquals(Arrays.asList("Test Accel"), accelerometer.getNames());
    // The new Type_List type wins over the legacy instrument type, and "null" is dropped
    assertEquals(Arrays.asList("Accelerometer"), accelerometer.getTypes());
    assertEquals(Arrays.asList("ACC"), accelerometer.getAliases());
    // Without a Type_List type the legacy instrument type is used
    assertEquals(Arrays.asList("Spectrometer"), products.get(1).getTypes());
  }

  @Test
  void initFetchesSavesTheCacheAndKeepsTheNewestVersion() throws ContextProductsException {
    ContextProducts.init(null, cacheFile(), client());

    ContextProducts lookup = ContextProducts.getInstance();
    assertEquals(4, lookup.size());
    ContextProduct planet = lookup.find(PLANET);
    assertEquals("Test Planet", planet.getTitle());
    assertEquals(Arrays.asList("Red Dot", "PLANET-1"), planet.getAliases());
    assertTrue(cacheFile().isFile());
  }

  @Test
  void initFallsBackToTheCacheWhenTheApiCannotBeReached() throws ContextProductsException {
    ContextProducts.init(null, cacheFile(), client());
    ContextProducts.reset();
    server.stop(0);

    ContextProducts.init(null, cacheFile(), client());

    assertNotNull(ContextProducts.getInstance());
    assertEquals(4, ContextProducts.getInstance().size());
  }

  @Test
  void initFailsWithoutACacheWhenTheApiCannotBeReached() {
    server.stop(0);

    ContextProductsException failure = assertThrows(ContextProductsException.class,
        () -> ContextProducts.init(null, cacheFile(), client()));

    assertTrue(failure.getMessage().contains("--no-context-products"));
    assertTrue(failure.getMessage().contains("--context-products"));
    assertEquals(null, ContextProducts.getInstance());
  }

  @Test
  void anExplicitFileIsUsedWithoutCallingTheApi() throws Exception {
    server.stop(0);
    File file = directory.resolve("given.json").toFile();
    ContextProductsFile.write(file, Arrays.asList(new ContextProduct(PLANET + "::1.2",
        "Test Planet", Arrays.asList("Test Planet"), Arrays.asList("Planet"), Arrays.asList("Red Dot"))));

    ContextProducts.init(file, cacheFile(), client());

    assertEquals("Test Planet", ContextProducts.getInstance().find(PLANET).getTitle());
    assertFalse(cacheFile().exists());
  }

  @Test
  void theFileFormatRoundTrips() throws Exception {
    File file = directory.resolve("round-trip.json").toFile();
    ContextProduct written = new ContextProduct(PLANET + "::1.2", "Test Planet",
        Arrays.asList("Test Planet", "TP"), Arrays.asList("Planet"), Arrays.asList("Red Dot"));

    ContextProductsFile.write(file, Arrays.asList(written));
    ContextProduct read = ContextProductsFile.read(file).get(0);

    assertEquals(PLANET, read.getLid());
    assertEquals(written.getLidvid(), read.getLidvid());
    assertEquals(written.getTitle(), read.getTitle());
    assertEquals(written.getNames(), read.getNames());
    assertEquals(written.getTypes(), read.getTypes());
    assertEquals(written.getAliases(), read.getAliases());
    assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
        .contains("\"Product_Context\""));
  }

  @Test
  void rejectsAFileThatIsNotAContextProductsFile() throws IOException {
    File file = directory.resolve("wrong.json").toFile();
    Files.write(file.toPath(), "{\"data\":[]}".getBytes(StandardCharsets.UTF_8));

    assertThrows(ContextProductsException.class, () -> ContextProductsFile.read(file));
  }

  @Test
  void aMalformedApiUrlIsReportedAsAContextProductsFailure() {
    RegistryApiClient malformed = new RegistryApiClient("http://not a valid host/api", 2, 1);

    ContextProductsException failure = assertThrows(ContextProductsException.class,
        malformed::fetchAll);

    assertTrue(failure.getMessage().contains("not valid"));
  }

  @Test
  void aMalformedApiUrlFallsBackToTheCache() throws ContextProductsException {
    ContextProducts.init(null, cacheFile(), client());
    ContextProducts.reset();

    ContextProducts.init(null, cacheFile(), new RegistryApiClient("http://not a valid host/api", 2, 1));

    assertEquals(4, ContextProducts.getInstance().size());
  }

  @Test
  void comparesVersionsNumerically() {
    ContextProduct v2 = new ContextProduct("urn:x:y::1.2", "", null, null, null);
    ContextProduct v10 = new ContextProduct("urn:x:y::1.10", "", null, null, null);
    ContextProduct major = new ContextProduct("urn:x:y::2.0", "", null, null, null);

    assertTrue(v10.isNewerThan(v2));
    assertFalse(v2.isNewerThan(v10));
    assertTrue(major.isNewerThan(v10));
  }
}
