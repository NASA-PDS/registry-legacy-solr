package gov.nasa.pds.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class LidQueryTest {

	private static final String BUNDLE = "urn:nasa:pds:bopps2014";
	private static final String COLLECTION = "urn:nasa:pds:bopps2014:biassub";
	private static final String PRODUCT = "urn:nasa:pds:bopps2014:biassub:jca1_1_050044214_n018_0070b_fit";

	@Test
	void recognizesBundleCollectionAndProductLids() {
		for (String lid : new String[] { BUNDLE, COLLECTION, PRODUCT }) {
			LidQuery query = LidQuery.parse(lid);
			assertNotNull(query, lid);
			assertEquals(lid, query.getLid());
			assertFalse(query.isPrefix());
		}
	}

	@Test
	void dropsTheVersionOfALidvid() {
		assertEquals(BUNDLE, LidQuery.parse(BUNDLE + "::1.0").getLid());
		assertEquals(COLLECTION, LidQuery.parse(COLLECTION + "::12.3.1").getLid());
		assertEquals(PRODUCT, LidQuery.parse(PRODUCT + "::1.0").getLid());
	}

	@Test
	void aTrailingWildcardAsksForEverythingBelow() {
		LidQuery query = LidQuery.parse(BUNDLE + "*");
		assertEquals(BUNDLE, query.getLid());
		assertTrue(query.isPrefix());
	}

	@Test
	void toleratesSpacesCaseAndTheWildcardTheParserMayAdd() {
		LidQuery query = LidQuery.parse("  URN:NASA:PDS:BOPPS2014  ");
		assertEquals(BUNDLE, query.getLid());
		assertFalse(query.isPrefix());
		assertEquals(BUNDLE, LidQuery.parse(BUNDLE + "**").getLid());
		assertEquals(BUNDLE, LidQuery.parse(BUNDLE + "::1.0*").getLid());
	}

	@Test
	void caseDoesNotDependOnTheDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			// In Turkish, "I".toLowerCase() is a dotless i that is not a LID character
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("ı", "I".toLowerCase(), "the Turkish locale should be in effect");
			assertEquals("urn:nasa:pds:insight_documents", LidQuery.parse("URN:NASA:PDS:INSIGHT_DOCUMENTS").getLid());
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void acceptsTheCharactersOfRealLids() {
		assertNotNull(LidQuery.parse("urn:nasa:pds:context:instrument:vg1.iris"));
		assertNotNull(LidQuery.parse("urn:esa:psa:context:instrument_host:spacecraft.mex"));
		assertNotNull(LidQuery.parse("urn:nasa:pds:p10-hvm-pa-crt-cal:data-merged-1hour"));
	}

	@Test
	void ordinaryQueriesAreNotLidQueries() {
		for (String text : new String[] { null, "", "   ", "mars", "bopps data", "urn", "urn:nasa", "urn:nasa:pds",
				"urn:nasa:pds:", "urn:nasa::pds:bopps2014", "urn:nasa:pds:bopps2014 data", "data urn:nasa:pds:bopps2014",
				"urn:nasa:pds:bopps2014:", "lid:urn:nasa:pds:bopps2014", "urn:nasa:pds:bop ps", "http://urn:nasa:pds:x" }) {
			assertNull(LidQuery.parse(text), String.valueOf(text));
		}
	}

	@Test
	void aVersionThatIsNotAVersionIsNotALidvid() {
		assertNull(LidQuery.parse(BUNDLE + "::"));
		assertNull(LidQuery.parse(BUNDLE + "::latest"));
		assertNull(LidQuery.parse(BUNDLE + "::1.0::2.0"));
		assertNull(LidQuery.parse(BUNDLE + "::1."));
		assertNull(LidQuery.parse(BUNDLE + "::.1"));
		assertNull(LidQuery.parse(BUNDLE + "::1..0"));
		assertNull(LidQuery.parse(BUNDLE + "::1.a"));
	}

	@Test
	void veryLongTextIsNeverALidQuery() {
		String longLid = BUNDLE + ":x".repeat(2000);
		assertNull(LidQuery.parse(longLid));
		assertNull(LidQuery.parse("a".repeat(100000)));
	}

	@Test
	void parentsAreListedNearestFirstDownToTheBundle() {
		assertEquals(Arrays.asList(COLLECTION, BUNDLE), LidQuery.parse(PRODUCT).getParentLids());
		assertEquals(Collections.singletonList(BUNDLE), LidQuery.parse(COLLECTION).getParentLids());
		assertTrue(LidQuery.parse(BUNDLE).getParentLids().isEmpty());
	}
}
