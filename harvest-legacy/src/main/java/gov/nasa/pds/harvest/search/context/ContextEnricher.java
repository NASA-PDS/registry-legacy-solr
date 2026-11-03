package gov.nasa.pds.harvest.search.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the names and types that a label states for its targets, investigations, instruments
 * and instrument hosts with those of the registered context products they reference, and records
 * every other known name in a *_name_alternate field.
 */
public final class ContextEnricher {

  /** The kinds of context product that harvest resolves. */
  enum Kind {
    TARGET("target", "Target"),
    INVESTIGATION("investigation", "Investigation"),
    INSTRUMENT("instrument", "Instrument"),
    INSTRUMENT_HOST("instrument_host", "Instrument_Host");

    final String refField;
    final String nameField;
    final String typeField;
    final String alternateField;
    final String dataProductType;

    Kind(String prefix, String dataProductType) {
      this.refField = prefix + "_ref";
      this.nameField = prefix + "_name";
      this.typeField = prefix + "_type";
      this.alternateField = prefix + "_name_alternate";
      this.dataProductType = dataProductType;
    }
  }

  private ContextEnricher() {}

  /**
   * Enriches the field values of one Solr document.
   *
   * <p>A product that references a registered context product gets that product's title as its
   * name, its type, and the other names it is known by. A context product is resolved by its own
   * lid, and only for its own kind, so an investigation does not collect the names of every
   * instrument it lists. A reference that is not registered leaves the label values alone.
   *
   * @param lookup The registered context products.
   * @param fieldMap The field values of the document; updated in place.
   * @param typeMap The field types of the document; updated for each field that is set.
   */
  public static void apply(ContextProducts lookup, Map<String, List<String>> fieldMap,
      Map<String, String> typeMap) {
    boolean isContextProduct = fieldMap.getOrDefault("product_class", Collections.<String>emptyList())
        .contains("Product_Context");
    String ownKind = first(fieldMap, "data_product_type");
    String ownLid = first(fieldMap, "lid");

    for (Kind kind : Kind.values()) {
      List<String> refs = new ArrayList<String>();
      if (isContextProduct) {
        if (ownLid != null && kind.dataProductType.equalsIgnoreCase(ownKind)) {
          refs.add(ownLid);
        }
      } else {
        refs.addAll(fieldMap.getOrDefault(kind.refField, Collections.<String>emptyList()));
      }
      enrich(lookup, kind, refs, fieldMap, typeMap);
    }
  }

  private static void enrich(ContextProducts lookup, Kind kind, List<String> refs,
      Map<String, List<String>> fieldMap, Map<String, String> typeMap) {
    Map<String, ContextProduct> resolved = new LinkedHashMap<String, ContextProduct>();
    boolean anyUnresolved = false;
    for (String ref : refs) {
      ContextProduct product = lookup.find(ref);
      if (product != null) {
        resolved.put(product.getLid(), product);
      } else {
        anyUnresolved = true;
      }
    }

    Map<String, String> titles = new LinkedHashMap<String, String>();
    Set<String> known = new HashSet<String>();
    Set<String> types = new LinkedHashSet<String>();
    for (ContextProduct product : resolved.values()) {
      String canonical = !product.getTitle().isEmpty() ? product.getTitle()
          : product.getNames().isEmpty() ? "" : product.getNames().get(0);
      if (!canonical.isEmpty()) {
        titles.putIfAbsent(normalize(canonical), canonical);
      }
      known.add(normalize(canonical));
      for (String name : product.getNames()) {
        known.add(normalize(name));
      }
      for (String alias : product.getAliases()) {
        known.add(normalize(alias));
      }
      types.addAll(product.getTypes());
    }
    if (titles.isEmpty()) {
      return;
    }

    // The label does not say which of its names belongs to which reference. When a reference is not
    // registered, a label name that no registered product accounts for may belong to it, so it stays
    // in the name field, together with the label's types.
    Map<String, String> names = new LinkedHashMap<String, String>(titles);
    List<String> labelNames = fieldMap.getOrDefault(kind.nameField, Collections.<String>emptyList());
    if (anyUnresolved) {
      for (String labelName : labelNames) {
        String key = normalize(labelName);
        if (!key.isEmpty() && !known.contains(key)) {
          names.putIfAbsent(key, labelName.trim());
        }
      }
      types.addAll(fieldMap.getOrDefault(kind.typeField, Collections.<String>emptyList()));
    }

    // Every other name the product is known by: the context product's name element and all of its
    // aliases, then the label's own spelling. The registered spelling comes first so that it wins
    // when the label differs only in case, spacing or underscores.
    Map<String, String> alternates = new LinkedHashMap<String, String>();
    List<String> candidates = new ArrayList<String>();
    for (ContextProduct product : resolved.values()) {
      candidates.addAll(product.getNames());
      candidates.addAll(product.getAliases());
    }
    candidates.addAll(labelNames);
    for (String candidate : candidates) {
      String key = normalize(candidate);
      if (!key.isEmpty() && !names.containsKey(key)) {
        alternates.putIfAbsent(key, candidate.trim());
      }
    }

    set(fieldMap, typeMap, kind.nameField, new ArrayList<String>(names.values()));
    if (!types.isEmpty()) {
      set(fieldMap, typeMap, kind.typeField, new ArrayList<String>(types));
    }
    if (!alternates.isEmpty()) {
      set(fieldMap, typeMap, kind.alternateField, new ArrayList<String>(alternates.values()));
    }
  }

  private static void set(Map<String, List<String>> fieldMap, Map<String, String> typeMap,
      String field, List<String> values) {
    fieldMap.put(field, values);
    // The document writer fails on a field that has no type
    typeMap.put(field, "string");
  }

  private static String first(Map<String, List<String>> fieldMap, String field) {
    List<String> values = fieldMap.get(field);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  /** Compares names ignoring case, runs of whitespace and underscores. */
  static String normalize(String name) {
    return name == null ? ""
        : name.replace('_', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
