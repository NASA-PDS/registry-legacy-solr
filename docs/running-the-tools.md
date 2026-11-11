# Running the Registry Legacy Solr Tools

This guide covers how to install and operate the tools in this repository:

| Tool | Command | Purpose |
|---|---|---|
| Registry Manager | `registry_legacy_installer_docker.sh` | Installs, backs up, upgrades and restores the Solr registry running in Docker |
| Harvest | `harvest-solr` | Crawls PDS4 labels and generates Solr documents |
| Catalog | `catalog-solr` | Reads PDS3 catalog objects and generates Solr documents |
| Registry Manager | `registry-mgr-solr` | Loads Solr documents into the registry, and deletes them |

## How the pieces fit together

```
PDS4 labels  --harvest-solr-->  Solr XML documents  --+
                                                      +--registry-mgr-solr-->  Solr "data" collection
PDS3 catalog --catalog-solr-->  Solr XML documents  --+
```

The installer creates a Solr instance (SolrCloud, in a Docker container) with two collections: `registry` and `data`. Harvest and Catalog never talk to Solr when generating documents. They write XML files to disk, and `registry-mgr-solr` loads those files. The generated files can be loaded into any number of Solr instances, and loaded again after a reinstall, without crawling the data again.

## Prerequisites

- OpenJDK 17 or higher, with `JAVA_HOME` set (or `java` on the `PATH`)
- Docker, to run Solr
- Maven 3.x, only to build from source

## Getting the tools

Download the `-bin.tar.gz` or `-bin.zip` of each tool from the [releases page](https://github.com/NASA-PDS/registry-legacy-solr/releases), or build from source:

```bash
mvn install -DskipTests
```

Unpack each distribution. Each one is self-contained, with `bin/`, `lib/` and configuration directories:

```bash
tar -xzf harvest-legacy/target/harvest-legacy-*-bin.tar.gz
tar -xzf catalog-legacy/target/catalog-legacy-*-bin.tar.gz
tar -xzf registry-mgr-legacy/target/registry-mgr-legacy-*-bin.tar.gz
```

The examples below use these variables for the unpacked directories. Set them to match your environment:

```bash
HARVEST_SOLR_HOME="$HOME/tools/harvest-legacy"
CATALOG_SOLR_HOME="$HOME/tools/catalog-legacy"
REGISTRY_MGR_SOLR_HOME="$HOME/tools/registry-mgr-legacy"
```

### Installing a release with the install script

`scripts/install-release.sh` downloads the three tarballs of a GitHub release, unpacks them, repoints the symlinks, and installs or upgrades the registry. It is driven by environment variables:

```bash
curl -fLO https://raw.githubusercontent.com/NASA-PDS/registry-legacy-solr/main/scripts/install-release.sh
chmod +x install-release.sh

export INSTALL_DIR="$HOME/applications"
export DATA_HOME="$HOME/registry-data"
./install-release.sh latest
```

Give a release number such as `5.5.5` instead of `latest` to install a specific release. The releases are listed on the [releases page](https://github.com/NASA-PDS/registry-legacy-solr/releases); the tarballs are named `<tool>-<version>-bin.tar.gz` under `releases/download/v<version>/`.

The script:

1. Checks its inputs before it changes anything: the release number, `DATA_HOME`, the tools it needs (including `pgrep`, and `curl` and `docker` when it installs or upgrades the registry), the yes/no variables (they accept only `yes` or `no`), and, for `RELOAD_ALL`, that the document directories exist and hold documents.
2. Refuses to go on while a harvest, a catalog run or a document load is running, because a Solr restart in the middle of a crawl makes the harvest skip products (`FORCE=yes` overrides). It checks at the start, before it creates anything, and again right before it restarts Solr.
3. Downloads `catalog-legacy`, `harvest-legacy` and `registry-mgr-legacy` for the release into `INSTALL_DIR`, unpacks them, and points the symlinks `catalog-legacy`, `harvest-legacy` and `registry-manager-legacy` at them. The previous versions stay on disk, and the commands that go back to them are printed.
4. Upgrades the registry with the new registry manager (`upgrade`, which takes a backup first), or installs it when there is no registry container. It then checks that Solr answers and how many documents it holds.
5. Reloads the registry with `reload_all.sh` of the new Harvest when `RELOAD_ALL` is `prod` or `staging`, which loads the historical PDS3 documents, removes the legacy context products, then loads the PDS4 and PDS3 documents, in that order. Without `RELOAD_ALL` the script prints the `reload_all.sh` commands to run next, because documents loaded before the upgrade do not have the values of new schema fields.
6. Runs smoke queries against the `select`, `all`, `keyword`, `search` and `archive-filter` handlers of the `data` collection: a match-all query on each, and a keyword search for `mars`. Each query must answer, and when the registry held documents before the upgrade, the match-all queries must still find documents. The script prints the result of each query and exits with an error when one fails, after printing the summary of what was installed.

| Variable | Description |
|---|---|
| `REGISTRY_LEGACY_VERSION` | The release, such as `5.5.5`, or `latest` for the newest one (or give it as the first argument) |
| `INSTALL_DIR` | Directory for the unpacked releases and the symlinks (default: the current directory) |
| `DATA_HOME` | The Solr volume, as for the installer (required unless `UPGRADE_REGISTRY=no`) |
| `BACKUP_HOME` | Where the upgrade's backup goes (default `$DATA_HOME/solr-backups`) |
| `UPGRADE_REGISTRY` | `no` to install only the tools (default `yes`) |
| `SKIP_BACKUP` | `yes` to upgrade without the backup (default `no`) |
| `RELOAD_ALL` | `prod` or `staging` to run [`reload_all.sh`](#reload-everything) of the new Harvest after the upgrade, so new schema fields are filled; `prod` loads the documents generated on staging, `staging` harvests and catalogs first (default `no`). It needs `PDS4_SOLR_DOC_HOME`, `PDS3_SOLR_DOC_HOME` and `PDS3_HISTORY`, and for `staging` also `HARVEST_SOLR_CONF_HOME` and `PDS3_DATA_PATH` |
| `SMOKE_QUERIES` | `no` to skip the smoke queries after the install or upgrade (default `yes`) |
| `PERSIST_ENV` | `yes` to add `HARVEST_SOLR_HOME`, `CATALOG_SOLR_HOME`, `REGISTRY_MGR_SOLR_HOME` and the other variables that are set to `PROFILE_FILE` (default `$HOME/.bash_profile`) when it has no export for them yet |
| `DRY_RUN` | `yes` to print what would be done and change nothing |

Run it with `DRY_RUN=yes` first to see the steps. [Upgrading the Registry to a New Release](upgrading-a-release.md) is the step-by-step runbook, including when a reload is needed and how to roll back.

## Environment variables

| Variable | Used by | Description |
|---|---|---|
| `DATA_HOME` | installer | Required. Directory that holds the Solr volume (`$DATA_HOME/solrdata`) |
| `BACKUP_HOME` | installer | Directory for collection backups. Default: `$DATA_HOME/solr-backups` |
| `PDS4_SOLR_DOC_HOME` | `harvest-solr` | Required. Existing directory where PDS4 Solr documents are written (under `solr-docs`). The lock file lives here |
| `PDS3_SOLR_DOC_HOME` | `catalog-solr` | Required. Existing directory for the PDS3 lock file; use it as the output directory too |
| `HARVEST_SOLR_LOG_FILE` | `harvest-solr` | If set, all output is appended to this file |
| `HARVEST_SOLR_MARKER_FILE` | `harvest-solr` | If set, this file is created after a successful harvest so a scheduler can tell new documents are ready |
| `LEGACY_REGISTRY_EMAIL_RECIPIENTS` | `harvest-solr` | If set (and `mail` is installed), a success or failure email with a log summary is sent when harvest ends |
| `HOSTNAME_LABEL` | `harvest-solr` | Name used in the email subject instead of the hostname |
| `PDS_REGISTRY_API` | `harvest-solr` | PDS Registry API base URL used to fetch the registered context products. Default: `https://pds.mcp.nasa.gov/api` |
| `JAVA_HOME` | all | Java installation to use |

## Install Solr

The installer builds a Docker image from the unpacked Registry Manager distribution, starts a container named `registry-legacy` on port 8983, and creates the two collections.

```bash
DATA_HOME="$HOME/registry-data"
export DATA_HOME
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" install
```

Solr is then available at `http://localhost:8983/solr`. Index data is stored in `$DATA_HOME/solrdata`, outside the container. The Solr process in the container runs as user ID 8983, and the installer tries to give that user ownership of the data and backup directories. If your account is not allowed to do that, it prints a warning, and you need to set the ownership yourself.

The installer commands are:

| Command | Description |
|---|---|
| `install` | Build the image, start the container, create the `registry` and `data` collections |
| `uninstall` | Delete both collections, stop and remove the container and image. Deletes the indexes |
| `backup [LABEL]` | Back up both collections to `BACKUP_HOME` |
| `upgrade` | Back up, replace the container with one built from the unpacked distribution, keep the indexes |
| `restore [LABEL]` | Recreate the container and restore both collections from a backup |

| Option | Description |
|---|---|
| `-n` | Do not prompt for confirmation (`uninstall`, `restore`) |
| `-s` | Skip the automatic backup (`upgrade`) |

## Generate PDS4 documents with Harvest

Harvest reads a policy file that lists what to crawl, and a directory of search configuration that maps label content to Solr fields.

```bash
PDS4_SOLR_DOC_HOME="$HOME/registry-data/pds4"
mkdir -p "$PDS4_SOLR_DOC_HOME"
export PDS4_SOLR_DOC_HOME

PDS4_DATA="/path/to/pds4/data"

"$HARVEST_SOLR_HOME/bin/harvest-solr" \
    -c "$HARVEST_SOLR_HOME/conf/harvest/examples/harvest-policy-master.xml" \
    -C "$HARVEST_SOLR_HOME/conf/search/defaults" \
    -o "$PDS4_SOLR_DOC_HOME" \
    --target "$PDS4_DATA"
```

The Solr documents are written to `$PDS4_SOLR_DOC_HOME/solr-docs`.

| Option | Description |
|---|---|
| `-c`, `--harvest-config` | Harvest policy file (required). Examples are in `conf/harvest/examples`; `harvest-policy-master.xml` covers all PDS4 product types |
| `-C`, `--doc-config` | Directory of search configuration files (field mappings) |
| `-o`, `--output-dir` | Directory for the generated Solr documents (default: current directory) |
| `-t`, `--target` | One or more directories to crawl. Overrides the paths in the policy file |
| `-e`, `--regexp` | File patterns to look for when crawling, in quotes |
| `-D`, `--ignore-dir` | Directory name patterns to skip, in quotes |
| `-pds3`, `--is-pds3-dir` | Treat the targets as PDS3 directories |
| `-l`, `--log-file` | Log file (default: standard out) |
| `-v`, `--verbose` | Log level: 0=Debug, 1=Info, 2=Warning, 3=Error (default 1) |
| `-h`, `--help` | Show usage |
| `-V`, `--version` | Show the version |
| `-cp`, `--context-products` | Use this context products file instead of fetching the list from the PDS Registry API |
| `-ncp`, `--no-context-products` | Do not look up registered context products; names and types come from the labels |

Things to know:

- Only one harvest can run at a time on a machine. The script creates `harvest-solr.lock` in `$PDS4_SOLR_DOC_HOME` and refuses to start if it exists. If a previous run was killed, remove the lock file.
- Harvest runs with up to 8 GB of heap and takes roughly 0.6 seconds per label (about 10 minutes per 1000 labels).
- The script sends output to `HARVEST_SOLR_LOG_FILE` when it is set. For long runs, run it in the background with output redirected:

  ```bash
  HARVEST_SOLR_LOG_FILE="$HOME/registry-data/harvest-logs/harvest_$(date +%Y%m%d%H%M%S).txt"
  export HARVEST_SOLR_LOG_FILE
  nohup "$HARVEST_SOLR_HOME/bin/harvest-solr" -c "$HARVEST_SOLR_HOME/conf/harvest/examples/harvest-policy-master.xml" -C "$HARVEST_SOLR_HOME/conf/search/defaults" -o "$PDS4_SOLR_DOC_HOME" --target "$PDS4_DATA" > /dev/null 2>&1 &
  ```

- The Solr URL the script uses for lookups is set at the top of `bin/harvest-solr` (`SOLR_URL`, default `http://localhost:8983/solr`).
- Policy files for specific missions are in `conf/harvest`.

### Names, types and aliases from context products

At the start of each run, Harvest fetches the registered context products (targets, investigations, instruments and instrument hosts) from the PDS Registry API and saves them as `context_products.json` in the output directory, next to `solr-docs`. A product that references a context product gets:

- `target_name`, `investigation_name`, `instrument_name` and `instrument_host_name` set to the context product's title, instead of the spelling in the label.
- `target_type`, `investigation_type`, `instrument_type` and `instrument_host_type` set to the context product's type. (An instrument's type is its `Type_List` type, or its legacy type when it has none.)
- `target_name_alternate`, `investigation_name_alternate`, `instrument_name_alternate` and `instrument_host_name_alternate` listing every other name it is known by: the context product's name, all of its `alternate_title` and `alternate_id` values, and the label's spelling when it differs. Keyword search matches and boosts these like the names. Together with the label's own `alternate_id` and `alternate_title` values they are copied into one indexed `aliases` field, which is the field keyword search boosts for aliases.

Keyword search (`/keyword`) also matches the four type fields, at half the weight of a name, so a search for `spectrometer` or `asteroid` finds products by the type of what they reference. These are changes to `solrconfig.xml` only, so `upgrade` applies them without a reload.

A reference that is not registered, and every PDS3 product, keeps the values from the label. When a product has registered and unregistered references of one kind, the label names that no registered product accounts for (and the label's types) stay in the name and type fields next to the registered ones, because a label does not say which name belongs to which reference. The run ends by logging how many references were and were not found.

The host that runs Harvest needs outbound HTTPS to the API (set `PDS_REGISTRY_API` to use another URL). If the API cannot be reached, Harvest uses `context_products.json` from the previous run and logs a warning with its age. With no earlier copy it stops with exit status 1; run it again when the API is reachable, pass `--context-products FILE` (for example a copy taken from another machine), or pass `--no-context-products`.

Documents harvested before this feature gain the new fields only when they are harvested and loaded again. The registry needs the `upgrade` first so that its schema has the new fields.

## Generate PDS3 documents with Catalog

```bash
PDS3_SOLR_DOC_HOME="$HOME/registry-data/pds3"
mkdir -p "$PDS3_SOLR_DOC_HOME"
export PDS3_SOLR_DOC_HOME

PDS3_DATA="/path/to/pds3/data"

"$CATALOG_SOLR_HOME/bin/catalog-solr" \
    --mode ingest \
    --doc-config "$CATALOG_SOLR_HOME/search-conf/defaults/" \
    --output-dir "$PDS3_SOLR_DOC_HOME" \
    --target "$PDS3_DATA"
```

| Option | Description |
|---|---|
| `-m`, `--mode` | `ingest` to generate Solr documents; `compare` and `validate` are the other modes |
| `-C`, `--doc-config` | Directory of document generation configuration files |
| `-o`, `--output-dir` | Directory for the generated Solr documents (default: current directory) |
| `-t`, `--target` | One or more catalog files or directories to process |
| `-L`, `--local` | Do not descend into sub-directories of a target |
| `-I`, `--include` | Paths to search for files referenced by pointer statements (default: the catalog file's directory) |
| `-r`, `--report-file` | Report file (default: standard out) |
| `-v`, `--verbose` | Report level: 1=info, 2=warning, 3=error (default 2) |

As with Harvest, only one `catalog-solr` can run at a time (lock file `catalog-solr.lock` in `$PDS3_SOLR_DOC_HOME`).

## Load documents into Solr

`registry-mgr-solr` posts every `.xml` file in the directory it is given (searched recursively) to the `data` collection, and commits.

```bash
"$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr" "$PDS4_SOLR_DOC_HOME/solr-docs"
"$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr" "$PDS3_SOLR_DOC_HOME"
```

Loading takes about 11 seconds per 1000 products. A document with the same identifier (`lid::version_id`) as one already indexed replaces it, so loading the same documents twice is safe.

| Option | Description |
|---|---|
| `-host`, `-port`, `-proto` | Solr server (default `localhost`, `8983`, `http`) |
| `-delete-pkg PACKAGE_ID` | Delete all documents of one harvest package |
| `-delete-all` | Delete all documents from every collection (`registry` and `data`) |
| `-mark-superseded` | Flag older versions of each product so keyword search hides them. Runs by itself after loading documents; this option runs only that step |

Without a directory argument the tool looks in `$HOME/registry-data/solr-docs`.

Individual documents can be removed with the Solr update API, for example one PDS4 product by `lid`:

```bash
LID="urn:nasa:pds:context:instrument_host:spacecraft.a17s"
curl -X POST -H 'Content-Type: application/json' --data-binary \
  '{"delete":{"query":"lid:\"'$LID'\""}}' \
  'http://localhost:8983/solr/data/update?commit=true'
```

### Older versions of a product

Every version of a product stays in the index, so version history is available through the `/all` handler and direct `lidvid` queries. Keyword search (`/keyword`) and the archive filter (`/archive-filter`) return only the latest version of each product, and a search term that appears only in an older version does not return the product.

To hide older versions before matching, each older version carries `superseded=1` and the newest version carries no flag. Versions only move forward, so a flag is never cleared. After it loads documents, `registry-mgr-solr` reads the documents that are not flagged yet (the latest version of each product plus anything just loaded) and flags every version but the newest. Loading a document replaces it and drops its flag, so the step has to run after every load; run it yourself after loading documents with another tool:

```bash
"$REGISTRY_MGR_SOLR_HOME/bin/registry-mgr-solr" -mark-superseded
```

Removing the newest version of a product leaves its older versions flagged, so the product no longer appears in keyword search. To make an older version current again, load its document again; loading drops the flag.

The `superseded` field is part of the `data` schema. A registry installed before it existed gets it from `upgrade`; until then the step prints a warning and older versions stay visible. Any other failure while marking makes `registry-mgr-solr` exit with a non-zero status, so a scheduled load reports it.

## Reload everything

`bin/reload_all.sh` in the Harvest distribution regenerates and reloads all PDS4 and PDS3 documents.

```bash
"$HARVEST_SOLR_HOME/bin/reload_all.sh" staging
```

| Argument | Description |
|---|---|
| `staging` | Run `harvest-solr` and `catalog-solr`, then load the historical PDS3 documents, the PDS4 documents and the PDS3 documents into the Solr registry on this machine |
| `prod` | Load the documents that were generated on staging; nothing is harvested |

The script reads its settings from environment variables:

| Variable | Needed for | Description |
|---|---|---|
| `REGISTRY_MGR_SOLR_HOME` | both | Registry Manager installation directory |
| `PDS4_SOLR_DOC_HOME` | both | Existing directory for the PDS4 documents; the reload lock file lives here |
| `PDS3_SOLR_DOC_HOME` | both | Existing directory for the PDS3 documents (`solr-docs` is created inside) |
| `PDS3_HISTORY` | both | Directory with the historical PDS3 documents |
| `HARVEST_SOLR_HOME`, `HARVEST_SOLR_CONF_HOME` | staging | Harvest installation and the directory with `harvest-policy-reload-all.xml` |
| `CATALOG_SOLR_HOME`, `PDS3_DATA_PATH` | staging | Catalog installation and the PDS3 data directory |
| `REGISTRY_LOG_DIR` | optional | Parent of `harvest-logs` and `catalog-logs` (default `$HOME/registry-logs`) |
| `SOLR_HOST`, `SOLR_PORT` | optional | Solr registry to load (default `localhost` and `8983`) |

The script stops at the first step that fails and exits non-zero, so a failed harvest never leads to loading old or partial documents. New PDS3 documents are generated in `solr-docs_new` and replace `solr-docs` only when `catalog-solr` succeeds; the previous ones are kept as `solr-docs_pre<timestamp>` and can be deleted when no longer needed. Only one reload can run at a time (lock directory `reload_all.lock` in `$PDS4_SOLR_DOC_HOME`; remove it if a run was killed).

Between the delete of the legacy context products and the end of the PDS4 load, the PDS4 context products are missing from search results. Run a reload when that is acceptable, or reload on a Solr that is not serving traffic.

The script is tested with stubbed tools by `test/reload-all-tests.sh`.

### Ranking of PDS4 and PDS3 results

Keyword search (`/keyword`), `/search` and `/archive-filter` multiply the score of every PDS4 product by 1.5, so a PDS4 product ranks above a PDS3 product of similar relevance, while a much better PDS3 match still ranks first. The factor is the `boost` parameter in `solrconfig.xml` (`if(query({!lucene v='pds_model_version:pds4'},0),1.5,1)`); change `1.5` to make the preference weaker or stronger. A change to `solrconfig.xml` is applied with `upgrade`, without loading documents again.

### Searching by identifier

Keyword search (`/keyword`), `/search` and `/archive-filter` recognize a query that is a PDS4 logical identifier and search the `lid` field for it. The whole query has to be one identifier: `urn:` followed by at least three more colon separated parts (lowercase letters, digits, `-`, `.` and `_`), so a bundle LID (`urn:nasa:pds:bopps2014`), a collection LID (`urn:nasa:pds:bopps2014:biassub`) or a product LID (`urn:nasa:pds:bopps2014:biassub:<product>`). A query with other words, or with fewer parts, is an ordinary keyword search.

| Query | Result |
|---|---|
| `<LID>` | The product with that LID (the latest version) |
| `<LID>::<version_id>` | The same: the version is dropped, because only the latest version is searched |
| `<LID>*` | The product and every product whose LID starts with it, the product first |
| A LID that is not in the index | Its parents, nearest first: a product LID finds its collection and its bundle (products themselves are not searched), and a collection LID finds its bundle |

Case does not matter. The older versions and the product types a handler leaves out are hidden as in any other search.

## Check what is in Solr

```bash
SOLR_URL="http://localhost:8983/solr"
curl -s "$SOLR_URL/data/select?q=*:*&rows=0&wt=json"
curl -s "$SOLR_URL/admin/cores?action=STATUS"
```

`numFound` in the first response is the number of indexed documents. The search handler used by keyword search is `/keyword`, for example:

```bash
curl -s "$SOLR_URL/data/keyword?q=mars&rows=5&wt=json"
```

## Back up, upgrade and restore

### Back up

```bash
BACKUP_HOME="$HOME/registry-backups"
export BACKUP_HOME
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" backup before-upgrade
```

This copies both collections, while Solr keeps serving requests, to `$BACKUP_HOME/registry-before-upgrade` and `$BACKUP_HOME/data-before-upgrade`. The label is optional (default: a timestamp) and can only be used once. Keep `BACKUP_HOME` outside the Solr volume, and copy the backups off the machine for protection against disk loss.

### Upgrade

An upgrade installs a new release of the tools, including the Solr version and configuration in its Docker image, while keeping the existing indexes:

```bash
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" upgrade
```

It backs up both collections, builds the new image, stops Solr, replaces the container (reusing the Solr volume), uploads the configuration from the new image to ZooKeeper, and reloads the collections. Use `-s` to skip the backup when you have a copy already.

Whether the indexes can be kept depends on the change:

| Change | Indexes kept? |
|---|---|
| `solrconfig.xml` (request handlers, boosts, caches) | Yes |
| Harvest policy or search configuration | Yes. Documents already loaded gain new fields only when they are generated and loaded again |
| New field in the schema | Yes. Documents already loaded have no value for it until they are loaded again or a tool sets it (for example `registry-mgr-solr -mark-superseded`) |
| Solr patch or minor upgrade (for example 9.8 to 9.10) | Yes |
| Solr upgrade of one major version | Yes. Read the Solr release notes first |
| Schema change to a field type, analyzer, `indexed`, `stored`, `docValues` or `multiValued` | No. Reinstall and load the documents again |
| Solr upgrade that skips a major version | No. Reinstall and load the documents again |

To reinstall and load again, run `uninstall`, then `install`, then `registry-mgr-solr` on the saved Solr document directories. Keep the generated documents until an upgrade has been verified, so no new harvest is needed.

### Restore

Restore recreates the container and the collections from a backup. Use it when the Solr volume has been deleted or damaged, or to go back to an earlier release.

```bash
LABEL="before-upgrade"
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" restore $LABEL
```

Without a label the latest backup is used. If the collections still exist, run `uninstall` first (this deletes the current indexes). The restored collections use the Solr configuration from the unpacked distribution, so restore with the release that took the backup, then `upgrade` to move to a newer one.

Documents loaded after the backup was taken are not part of the restore; load them again from the saved Solr document directories.

## Typical workflows

**First load**: install Solr, run Harvest (and Catalog, if PDS3 data is needed), load the documents with `registry-mgr-solr`, and check the document count.

**Adding data**: run Harvest on the new data into the same output directory, load `solr-docs`.

**Adding or changing searchable attributes**: see [Adding New Attributes to Search](../README.md#adding-new-attributes-to-search). A schema change needs a reinstall and reload; a harvest or search configuration change needs the documents to be generated and loaded again.

**Staging then production**: run Harvest once on a staging machine and load the documents there to verify the result, then load the same Solr document directory into each production instance with `registry-mgr-solr -host`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Another instance of harvest-solr is already running` | A lock file exists in `$PDS4_SOLR_DOC_HOME`. If no harvest is running, delete `harvest-solr.lock` (`catalog-solr.lock` in `$PDS3_SOLR_DOC_HOME` for Catalog) |
| `PDS4_SOLR_DOC_HOME environment variable is not set` | Create the directory and export the variable before running Harvest (`PDS3_SOLR_DOC_HOME` for Catalog) |
| Installer says `DATA_HOME environment variable must be set` | Export `DATA_HOME` before running the installer |
| `Could not start Solr` | Check `docker ps -a` and `docker logs registry-legacy`; check nothing else uses port 8983 |
| Installer warns it could not `chown` | The data or backup directory is not writable by user ID 8983 in the container. Set the ownership manually |
| `backup` or `upgrade` reports `/backups` is not writable | The container was created without the backup directory mounted, or the backup directory has the wrong ownership. Take a copy of `$DATA_HOME/solrdata` while Solr is stopped, then run `upgrade -s`; the new container has the mount |
| `restore` reports a collection already exists | Run `uninstall` first, or restore on a machine with no indexes |
| Installer log | Each run writes `registry_installer_docker-log-<timestamp>.txt` to the home directory of the user running it |
