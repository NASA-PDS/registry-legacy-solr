# Test Case: JSON Extraction for List_Author and List_Editor

This test case verifies that the JSON extraction functionality correctly preserves the nested structure of author and editor information.

## Test Data

**File**: `bundle.xml`
**Product**: Pioneer Venus Multiprobe DLBI Bundle
**LID**: `urn:nasa:pds:pvmp_dlbi`

### Author/Editor Structure

The bundle.xml contains:
- **3 authors** in List_Author:
  - Young-In Won: 2 affiliations
  - Stephanie A. McLaughlin: 2 affiliations
  - Charles C. Counselman III: 1 affiliation

- **5 editors** in List_Editor:
  - Young-In Won: 2 affiliations
  - Stephanie A. McLaughlin: 2 affiliations
  - Lynn D. V. Neakrase: 2 affiliations
  - Nancy J. Chanover: 2 affiliations
  - David R. Williams: 2 affiliations

## Expected Output

When processed by harvest-solr, the generated Solr document should contain:

### citation_author_person_json (array of 3 JSON strings)

```json
[
  "{\"Person\":{\"display_full_name\":\"Young-In Won\",\"given_name\":\"Young-In\",\"family_name\":\"Won\",\"person_orcid\":\"https://orcid.org/0009-0003-0452-774X\",\"sequence_number\":1,\"Affiliation\":[{\"organization_name\":\"ADNET Systems, Inc.\"},{\"organization_name\":\"NASA Space Science Data Coordinated Archive\"}]}}",

  "{\"Person\":{\"display_full_name\":\"Stephanie A. McLaughlin\",\"given_name\":\"Stephanie A.\",\"family_name\":\"McLaughlin\",\"sequence_number\":2,\"Affiliation\":[{\"organization_name\":\"Telophase Corporation\"},{\"organization_name\":\"NASA Space Science Data Coordinated Archive\"}]}}",

  "{\"Person\":{\"display_full_name\":\"Charles C. Counselman III\",\"given_name\":\"Charles C. III\",\"family_name\":\"Counselman\",\"person_orcid\":\"https://orcid.org/0000-0001-6034-2377\",\"sequence_number\":3,\"Affiliation\":{\"organization_name\":\"Massachusetts Institute of Technology\"}}}"
]
```

### citation_editor_person_json (array of 5 JSON strings)

Similar structure for all 5 editors, each with their affiliations properly nested.

## What This Tests

1. **Nested structure preservation**: Each Person element with its child Affiliation elements is kept together
2. **Variable cardinality**: Authors with different numbers of affiliations (1 vs 2) are handled correctly
3. **JSON conversion**: XML elements are correctly converted to JSON using org.json library
4. **Array structure**: Multiple Person elements create an array of JSON objects

## How to Test

```bash
# Set up environment
export PDS4_SOLR_DOC_HOME=/path/to/output

# Run harvest
bin/harvest-solr \
  -c conf/harvest/examples/harvest-policy-master.xml \
  -o /path/to/output \
  -t src/test/resources/data/pds4/pvmp_dlbi/

# Examine the generated Solr document
cat /path/to/output/solr-docs/*.xml
```

## Verification

The generated Solr document XML should contain fields like:

```xml
<field name="citation_author_person_json">{"Person":{"display_full_name":"Young-In Won",...</field>
<field name="citation_author_person_json">{"Person":{"display_full_name":"Stephanie A. McLaughlin",...</field>
<field name="citation_author_person_json">{"Person":{"display_full_name":"Charles C. Counselman III",...</field>
```

Each JSON string should be parseable and contain the complete author/editor information with nested affiliations intact.

## Related Issue

This test case addresses GitHub issue #237: harvest-solr XML flattening does not preserve relationships between nested elements.
