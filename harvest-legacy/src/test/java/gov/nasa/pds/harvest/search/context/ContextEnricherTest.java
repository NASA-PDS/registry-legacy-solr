package gov.nasa.pds.harvest.search.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextEnricherTest {
  private static final String MOON = "urn:nasa:pds:context:target:satellite.earth.moon";
  private static final String DUST = "urn:nasa:pds:context:target:dust.dust";
  private static final String MISSION = "urn:nasa:pds:context:investigation:mission.test";
  private static final String UNKNOWN = "urn:nasa:pds:context:target:planet.unknown";

  private final ContextProducts lookup = new ContextProducts(Arrays.asList(
      new ContextProduct(MOON + "::1.2", "Moon", list("Moon"), list("Satellite"),
          list("Earth I (Moon)", "NAIF ID 301")),
      new ContextProduct(DUST + "::1.0", "Dust", list("Dust"), list("Dust"), list()),
      new ContextProduct(MISSION + "::1.1", "Test Mission", list("TEST MISSION"), list("Mission"),
          list("TM"))));

  private final Map<String, List<String>> fields = new HashMap<String, List<String>>();
  private final Map<String, String> types = new HashMap<String, String>();

  private static List<String> list(String... values) {
    return Arrays.asList(values);
  }

  @Test
  void replacesLabelValuesWithContextProductValues() {
    fields.put("target_ref", list(MOON));
    fields.put("target_name", list("Earth's Moon"));
    fields.put("target_type", list("Natural Satellite"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Moon"), fields.get("target_name"));
    assertEquals(list("Satellite"), fields.get("target_type"));
    // The aliases come first, then the label spelling. The context name equals the title.
    assertEquals(list("Earth I (Moon)", "NAIF ID 301", "Earth's Moon"),
        fields.get("target_name_alternate"));
    assertEquals("string", types.get("target_name"));
    assertEquals("string", types.get("target_type"));
    assertEquals("string", types.get("target_name_alternate"));
  }

  @Test
  void doesNotRepeatALabelNameThatMatchesTheTitle() {
    fields.put("target_ref", list(MOON));
    fields.put("target_name", list("MOON"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Moon"), fields.get("target_name"));
    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void ignoresCaseWhitespaceAndUnderscoresWhenComparingNames() {
    fields.put("target_ref", list(MOON));
    fields.put("target_name", list("  moon  ", "Naif_ID  301"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void resolvesLidvidReferences() {
    fields.put("target_ref", list(MOON + "::1.2"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Moon"), fields.get("target_name"));
  }

  @Test
  void leavesUnresolvedReferencesAlone() {
    fields.put("target_ref", list(UNKNOWN));
    fields.put("target_name", list("Mars"));
    fields.put("target_type", list("Planet"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Mars"), fields.get("target_name"));
    assertEquals(list("Planet"), fields.get("target_type"));
    assertFalse(fields.containsKey("target_name_alternate"));
    assertTrue(types.isEmpty());
    assertTrue(lookup.summary().contains(UNKNOWN));
  }

  @Test
  void leavesDocumentsWithoutReferencesAlone() {
    fields.put("target_name", list("Mars"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Mars"), fields.get("target_name"));
    assertFalse(fields.containsKey("target_name_alternate"));
  }

  @Test
  void keepsTheLabelTypeWhenTheContextProductHasNone() {
    ContextProducts withoutTypes = new ContextProducts(Arrays.asList(
        new ContextProduct(MOON + "::1.2", "Moon", list("Moon"), list(), list())));
    fields.put("target_ref", list(MOON));
    fields.put("target_type", list("Satellite"));

    ContextEnricher.apply(withoutTypes, fields, types);

    assertEquals(list("Satellite"), fields.get("target_type"));
    assertFalse(types.containsKey("target_type"));
  }

  @Test
  void combinesSeveralReferencesOfOneKind() {
    fields.put("target_ref", list(MOON, DUST));
    fields.put("target_name", list("Moon", "Dust"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Moon", "Dust"), fields.get("target_name"));
    assertEquals(list("Satellite", "Dust"), fields.get("target_type"));
    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void keepsTheLabelNameOfAnUnregisteredTargetNextToARegisteredOne() {
    fields.put("target_ref", list(MOON, UNKNOWN));
    fields.put("target_name", list("Moon", "Mystery Object"));
    fields.put("target_type", list("Natural Satellite", "Asteroid"));

    ContextEnricher.apply(lookup, fields, types);

    // The name no registered product accounts for stays a name, as do the label's types
    assertEquals(list("Moon", "Mystery Object"), fields.get("target_name"));
    assertEquals(list("Satellite", "Natural Satellite", "Asteroid"), fields.get("target_type"));
    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void replacesTheLabelNamesOfRegisteredProductsEvenWhenAnotherReferenceIsUnregistered() {
    fields.put("target_ref", list(MOON, UNKNOWN));
    fields.put("target_name", list("EARTH I (MOON)", "Mystery Object"));

    ContextEnricher.apply(lookup, fields, types);

    // The label's spelling of the Moon is an alias, so only the unaccounted name is kept
    assertEquals(list("Moon", "Mystery Object"), fields.get("target_name"));
    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void usesTheFirstNameWhenTheTitleIsEmpty() {
    ContextProducts withoutTitle = new ContextProducts(Arrays.asList(
        new ContextProduct(MOON + "::1.2", "", list("Moon"), list("Satellite"), list())));
    fields.put("target_ref", list(MOON));

    ContextEnricher.apply(withoutTitle, fields, types);

    assertEquals(list("Moon"), fields.get("target_name"));
  }

  @Test
  void enrichesEachKindFromItsOwnReferences() {
    fields.put("investigation_ref", list(MISSION));
    fields.put("investigation_name", list("TEST MISSION PHASE 1"));
    fields.put("target_ref", list(MOON));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Test Mission"), fields.get("investigation_name"));
    assertEquals(list("Mission"), fields.get("investigation_type"));
    assertEquals(list("TM", "TEST MISSION PHASE 1"), fields.get("investigation_name_alternate"));
    assertEquals(list("Moon"), fields.get("target_name"));
    assertFalse(fields.containsKey("instrument_name"));
  }

  @Test
  void resolvesAContextProductByItsOwnLid() {
    fields.put("product_class", list("Product_Context"));
    fields.put("data_product_type", list("Target"));
    fields.put("lid", list(MOON));
    fields.put("target_name", list("Moon"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Moon"), fields.get("target_name"));
    assertEquals(list("Satellite"), fields.get("target_type"));
    assertEquals(list("Earth I (Moon)", "NAIF ID 301"), fields.get("target_name_alternate"));
  }

  @Test
  void aContextProductDoesNotCollectTheNamesItReferences() {
    fields.put("product_class", list("Product_Context"));
    fields.put("data_product_type", list("Investigation"));
    fields.put("lid", list(MISSION));
    fields.put("target_ref", list(MOON, DUST));
    fields.put("instrument_ref", list("urn:nasa:pds:context:instrument:acc.test"));

    ContextEnricher.apply(lookup, fields, types);

    assertEquals(list("Test Mission"), fields.get("investigation_name"));
    assertEquals(list("TM"), fields.get("investigation_name_alternate"));
    assertFalse(fields.containsKey("target_name"));
    assertFalse(fields.containsKey("instrument_name"));
  }

  @Test
  void normalizeIgnoresCaseWhitespaceAndUnderscores() {
    assertEquals("dust particle", ContextEnricher.normalize("  Dust_Particle "));
    assertEquals("", ContextEnricher.normalize(null));
  }
}
