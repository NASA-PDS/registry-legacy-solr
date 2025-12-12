# Registry Legacy Solr

This package software repo contains all the necessary components to support the PDS Legacy Solr Registry, which drives the [PDS Keyword Search]([url](https://pds.nasa.gov/datasearch/keyword-search/)) and [Tool Registry]([url](https://pds.nasa.gov/tools/tool-registry/)), among other parts of the system.

## Prerequisites

- **OpenJDK 17 or higher** - Required for building and running all components
- **Maven 3.x** - For building the project
- **Docker** - For Solr deployment (optional for local development)

## Harvest Solr
The Harvest Tool provides functionality for capturing and indexing product metadata. The tool will run locally alongside a data archive, crawl the local data repository in order to discover products and generate Solr-readable documents that will be passed onto Registry Manager for loading in the PDS Solr Registry. The Tool additionally stores the entire product label contents into another index within the Solr Registry

## Registry Manager Solr
The Registry Manager is responsible for managing and interacting with the PDS Solr Registry, including:
* creating the Solr Registry using a Docker container
* taking the harvested metadata that has been converted into Solr-readable documents and uploading it to the Solr Registry.

## Catalog Solr
Similar to the Harvest Tool, it leverages the [pds3-product-tools library](https://github.com/NASA-PDS/pds3-product-tools) to read PDS3 catalog objects, and create Solr Documents to write to Solr.

# Documentation
The documentation for the latest release, including release notes, installation and operation of the software are online at https://nasa-pds-incubator.github.io/registry-legacy-solr/.

If you would like to get the latest documentation, including any updates since the last release, you can execute the "mvn site:run" command and view the documentation locally at http://localhost:8080.

# Build

**Note:** Ensure you have OpenJDK 17 or higher installed and `JAVA_HOME` set correctly before building.

The software can be compiled and built with the "mvn compile" command but in order
to create the JAR file, you must execute the "mvn compile jar:jar" command. 

In order to create a complete distribution package, execute the 
following commands: 

```
% mvn site
% mvn install
```

# Operational Release

Create a new tag `release/x.x.x` and the [stable deployment](https://github.com/NASA-PDS/registry-legacy-solr/actions/workflows/stable-cicd.yaml) will execute and tag all of the software.


# Contribute

## Adding New Attributes to Search

1. Update the [Harvest Global Policy](https://github.com/NASA-PDS/registry-legacy-solr/blob/main/harvest-legacy/src/main/resources/policy/global-policy.xml) for any Products that you would like to parse these attributes from.
2. Update the [Master Config file](https://github.com/NASA-PDS/registry-legacy-solr/blob/main/harvest-legacy/src/main/resources/conf/harvest/examples/harvest-policy-master.xml) for any of the remaining products that you would like to parse these attributes from.
3. Update the [Search Core config files](https://github.com/NASA-PDS/registry-legacy-solr/tree/main/harvest-legacy/src/main/resources/conf/search/defaults/pds/pds4) to map what was parse by harvest into the Solr fields.
4. Update the [Solr schema](https://github.com/NASA-PDS/registry-legacy-solr/blob/main/registry-mgr-legacy/src/main/resources/collections/data/managed-schema.xml) to include these new search fields.
5. NOTE: This will require a redeployment and reindex of Solr to support the new schema.
