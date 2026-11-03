package gov.nasa.pds.harvest.search.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A registered PDS4 context product (target, instrument, instrument host or investigation): the
 * title, names, types and aliases that harvest uses in place of the values typed in a label.
 */
public class ContextProduct {
  private final String lid;
  private final String lidvid;
  private final String title;
  private final List<String> names;
  private final List<String> types;
  private final List<String> aliases;

  public ContextProduct(String lidvid, String title, List<String> names, List<String> types,
      List<String> aliases) {
    this.lidvid = lidvid;
    this.lid = lidOf(lidvid);
    this.title = title == null ? "" : title.trim();
    this.names = copy(names);
    this.types = copy(types);
    this.aliases = copy(aliases);
  }

  /**
   * Strips the version from a reference.
   *
   * @param ref A lid or a lidvid.
   * @return The lid.
   */
  public static String lidOf(String ref) {
    int separator = ref.indexOf("::");
    return (separator < 0 ? ref : ref.substring(0, separator)).trim();
  }

  private static List<String> copy(List<String> values) {
    return values == null ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<String>(values));
  }

  public String getLid() {
    return lid;
  }

  public String getLidvid() {
    return lidvid;
  }

  /** The canonical name used for the *_name search fields. */
  public String getTitle() {
    return title;
  }

  /** The name element of the context product (for example Target/name). */
  public List<String> getNames() {
    return names;
  }

  public List<String> getTypes() {
    return types;
  }

  /** All alternate_title and alternate_id values. */
  public List<String> getAliases() {
    return aliases;
  }

  /**
   * Compares versions numerically, as major.minor.
   *
   * @param other Another version of the same product.
   * @return true if this product's version is higher than the other one's.
   */
  public boolean isNewerThan(ContextProduct other) {
    int[] mine = versionOf(lidvid);
    int[] theirs = versionOf(other.lidvid);
    return mine[0] != theirs[0] ? mine[0] > theirs[0] : mine[1] > theirs[1];
  }

  private static int[] versionOf(String lidvid) {
    int separator = lidvid.indexOf("::");
    int[] version = {0, 0};
    if (separator < 0) {
      return version;
    }
    String[] parts = lidvid.substring(separator + 2).split("\\.");
    try {
      version[0] = Integer.parseInt(parts[0].trim());
      if (parts.length > 1) {
        version[1] = Integer.parseInt(parts[1].trim());
      }
    } catch (NumberFormatException e) {
      // An unparseable version sorts as 0.0, so any parseable version wins
    }
    return version;
  }
}
