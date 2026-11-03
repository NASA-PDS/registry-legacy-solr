package gov.nasa.pds.harvest.search.context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Reads and writes the context products JSON file:
 * {"Product_Context":[{"lid","lidvid","title","name":[],"type":[],"alias":[]}]}.
 */
public final class ContextProductsFile {
  static final String ROOT = "Product_Context";

  private ContextProductsFile() {}

  /**
   * Writes the products. The file is written to a temporary file first, so an interrupted write
   * never replaces a good copy.
   *
   * @param file The file to write.
   * @param products The products.
   * @throws IOException If the file cannot be written.
   */
  public static void write(File file, Collection<ContextProduct> products) throws IOException {
    JsonArray entries = new JsonArray();
    for (ContextProduct product : products) {
      JsonObject entry = new JsonObject();
      entry.addProperty("lid", product.getLid());
      entry.addProperty("lidvid", product.getLidvid());
      entry.addProperty("title", product.getTitle());
      entry.add("name", toArray(product.getNames()));
      entry.add("type", toArray(product.getTypes()));
      entry.add("alias", toArray(product.getAliases()));
      entries.add(entry);
    }
    JsonObject root = new JsonObject();
    root.add(ROOT, entries);

    File parent = file.getAbsoluteFile().getParentFile();
    Files.createDirectories(parent.toPath());
    File temporary = new File(parent, file.getName() + ".tmp");
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    Files.write(temporary.toPath(), gson.toJson(root).getBytes(StandardCharsets.UTF_8));
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Reads the products.
   *
   * @param file The file to read.
   * @return The products.
   * @throws ContextProductsException If the file cannot be read or is not a context products file.
   */
  public static List<ContextProduct> read(File file) throws ContextProductsException {
    try {
      String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      JsonElement root = JsonParser.parseString(content);
      if (!root.isJsonObject() || !root.getAsJsonObject().has(ROOT)
          || !root.getAsJsonObject().get(ROOT).isJsonArray()) {
        throw new ContextProductsException(
            file + " is not a context products file: it has no '" + ROOT + "' array");
      }
      List<ContextProduct> products = new ArrayList<ContextProduct>();
      for (JsonElement element : root.getAsJsonObject().getAsJsonArray(ROOT)) {
        JsonObject entry = element.getAsJsonObject();
        products.add(new ContextProduct(entry.get("lidvid").getAsString(),
            entry.has("title") ? entry.get("title").getAsString() : "", toList(entry, "name"),
            toList(entry, "type"), toList(entry, "alias")));
      }
      return products;
    } catch (IOException | JsonParseException | IllegalStateException | NullPointerException e) {
      throw new ContextProductsException("Could not read the context products file " + file
          + ": " + e.getMessage(), e);
    }
  }

  private static JsonArray toArray(List<String> values) {
    JsonArray array = new JsonArray();
    for (String value : values) {
      array.add(value);
    }
    return array;
  }

  private static List<String> toList(JsonObject entry, String key) {
    List<String> values = new ArrayList<String>();
    if (entry.has(key) && entry.get(key).isJsonArray()) {
      for (JsonElement element : entry.getAsJsonArray(key)) {
        values.add(element.getAsString());
      }
    }
    return values;
  }
}
