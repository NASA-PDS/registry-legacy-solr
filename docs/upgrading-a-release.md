# Upgrading the Registry to a New Release

This runbook installs a release of the Registry Legacy Solr tools (Harvest, Catalog and Registry Manager) and upgrades the Solr registry that runs in Docker. It uses `scripts/install-release.sh`. The script and its settings are described in [Running the tools](running-the-tools.md#installing-a-release-with-the-install-script).

Every path and setting in this runbook is an environment variable. Set them for your machine; none of them is stored in the repository.

## Overview

1. Decide whether the release needs a reload.
2. Set the environment variables.
3. Get the script.
4. Do a dry run.
5. Run the script.
6. Read the result.
7. Reload the registry, if the release needs it.
8. Verify the registry.
9. Roll back, if something is wrong.

Run the whole sequence on the staging registry first, then repeat it on each production registry.

## 1. Decide whether the release needs a reload

An upgrade keeps the existing indexes. Documents that are already loaded only change when they are loaded again. Read the release notes and use this table:

| The release changes | What to do |
|---|---|
| Request handlers, boosts or caches in `solrconfig.xml`, a Solr patch or minor version, or only the tools | Upgrade only. No reload |
| A new field in the Solr schema, or a harvest policy or search configuration change | Upgrade, then reload (step 7), so the documents already loaded gain the new values |
| A field type, analyzer, or `indexed`, `stored`, `docValues` or `multiValued` setting of a field, or a Solr upgrade that skips a major version | The indexes cannot be kept: see [A release that cannot keep the indexes](#a-release-that-cannot-keep-the-indexes) |

If the release notes do not say, ask the maintainers before you reload: a reload is slow, and on staging it harvests all the data again.

## 2. Set the environment variables

```bash
export VERSION="latest"

# Check these are already set in your environment, if not set them
echo $INSTALL_DIR $DATA_HOME $BACKUP_HOME
```

| Variable | Description |
|---|---|
| `VERSION` | The release number without a leading `v` (for example `5.5.5`), or `latest` for the newest published release |
| `INSTALL_DIR` | Directory that holds the unpacked releases and the symlinks `catalog-legacy`, `harvest-legacy` and `registry-manager-legacy` |
| `DATA_HOME` | The Solr data directory. Use the same one as the original installation |
| `BACKUP_HOME` | Where the backup of the upgrade goes. Keep it outside `DATA_HOME` (default: `$DATA_HOME/solr-backups`) |

A reload (step 7) needs more variables. Set them when the release needs one:

```bash
# Check these are already set in your environment, if not set them
echo $PDS4_SOLR_DOC_HOME $PDS3_SOLR_DOC_HOME $PDS3_HISTORY
```

| Variable | Needed for | Description |
|---|---|---|
| `PDS4_SOLR_DOC_HOME` | every reload | Directory with the PDS4 Solr documents (`solr-docs` is inside) |
| `PDS3_SOLR_DOC_HOME` | every reload | Directory with the PDS3 Solr documents (`solr-docs` is inside) |
| `PDS3_HISTORY` | every reload | Directory with the historical PDS3 Solr documents |
| `HARVEST_SOLR_CONF_HOME` | a staging reload | Directory with `harvest-policy-reload-all.xml` |
| `PDS3_DATA_PATH` | a staging reload | Directory with the PDS3 data |

On the first run you can add `PERSIST_ENV=yes`. The script then adds `HARVEST_SOLR_HOME`, `CATALOG_SOLR_HOME`, `REGISTRY_MGR_SOLR_HOME` and the variables above that are set to the shell profile (`PROFILE_FILE`, default `$HOME/.bash_profile`), unless the profile already exports them.

## 3. Get the script

```bash
curl -fLO https://raw.githubusercontent.com/NASA-PDS/registry-legacy-solr/main/scripts/install-release.sh
chmod +x install-release.sh
```

## 4. Do a dry run

```bash
DRY_RUN=yes ./install-release.sh "$VERSION"
```

The dry run prints every step and changes nothing. Check that the release number, `INSTALL_DIR` and the registry action (`install` or `upgrade`) are what you expect.

## 5. Run the script

```bash
./install-release.sh "$VERSION"
```

The script:

1. Refuses to start while a harvest, a catalog run or a document load is running, because a Solr restart in the middle of a crawl makes the harvest skip products. Wait for the process to end, or set `FORCE=yes` when you are sure.
2. Downloads and unpacks the three tools into `INSTALL_DIR` and points the symlinks at them. The previous versions stay on disk.
3. Backs up both collections to `BACKUP_HOME`, replaces the Solr container and reloads the collections. It installs the registry instead when no registry container exists.
4. Checks that Solr answers and that the container image has the release tag.
5. Runs the smoke queries.

Settings for this step:

| Setting | Effect |
|---|---|
| `SKIP_BACKUP=yes` | Upgrade without the backup, when you already have a copy |
| `UPGRADE_REGISTRY=no` | Install the tools only, and leave the registry alone |
| `SMOKE_QUERIES=no` | Skip the smoke queries |

## 6. Read the result

A good run ends with `Done.` and exit status 0. Look for these lines:

| Line | Meaning |
|---|---|
| `<link> -> <tool>-<version> (was: ...)` | The symlink moved to the new release |
| `Registry container '...' found with N documents` | The document count before the upgrade |
| `Solr answers with N documents` | The document count after it. A warning follows when the two differ |
| `The container image '...' is not tagged ...` | Warning: the container does not run the release you installed |
| `Smoke query ok: /<handler> q=... -> N documents` | One line for each of the six smoke queries |
| `Smoke query FAILED: ...` | A handler did not answer, or a match-all query found no documents in a registry that had some. The script exits with status 1 after printing the summary |

The smoke queries run against the `select`, `all`, `keyword`, `search` and `archive-filter` handlers of the `data` collection: a match-all query on each, and a keyword search for `mars`.

The script also prints the commands that move the symlinks back to the previous tools. Keep that output until the release is verified.

## 7. Reload the registry, if the release needs it

Skip this step when step 1 said no reload. The reload runs `reload_all.sh` of the new Harvest, which loads the historical PDS3 documents, removes the legacy context products, then loads the PDS4 and PDS3 documents, in that order. Do not load the document directories by hand one after another: the order and the removal matter.

Between the removal of the legacy context products and the end of the PDS4 load, the PDS4 context products are missing from search results. Reload on a registry that is not serving traffic, or when that is acceptable.

Reload as part of the upgrade, by setting `RELOAD_ALL` for the run in step 5:

```bash
RELOAD_ALL="prod" ./install-release.sh "$VERSION"
```

Or reload afterwards, with the new tools:

```bash
export REGISTRY_MGR_SOLR_HOME="$INSTALL_DIR/registry-manager-legacy"
"$INSTALL_DIR/harvest-legacy/bin/reload_all.sh" prod
```

| Value | Use it on | What it does |
|---|---|---|
| `prod` | production, and staging when the documents already exist | Loads the Solr documents that are already generated |
| `staging` | the machine that generates the documents | Runs `harvest-solr` and `catalog-solr` to generate the documents, then loads them. It takes an hour or more |

Generate the documents once on staging, verify the result there, then run `prod` on each production registry with the same documents. Only one harvest may run on a machine at a time.

## 8. Verify the registry

Set the Solr address, then check the document count and a keyword search:

```bash
export SOLR_URL="http://localhost:8983/solr"
curl -s "$SOLR_URL/data/select?q=*:*&rows=0&wt=json"
curl -s -G "$SOLR_URL/data/keyword" --data-urlencode "q=mars" --data-urlencode "rows=1" --data-urlencode "wt=json"
```

The count should match the count from before the upgrade (or the new total after a reload), and the keyword search should return results. Then check the keyword search page that is backed by this registry.

## 9. Roll back

**The tools.** Run the symlink commands that the script printed. They have this form:

```bash
ln -sfn "$PREVIOUS_TOOL_DIR" "$INSTALL_DIR/harvest-legacy"
```

**The registry.** Restore the backup with the Registry Manager of the release that took it. The backup directories are named after the collection and the label (a timestamp, printed by the upgrade as `Backing up (label: ...)`):

```bash
ls "$BACKUP_HOME"
export LABEL="<label-from-the-listing>"
export PREVIOUS_VERSION="<the-release-you-upgraded-from>"
"$INSTALL_DIR/registry-mgr-legacy-$PREVIOUS_VERSION/bin/registry_legacy_installer_docker.sh" restore "$LABEL"
```

If the collections still exist, `restore` stops and asks you to run `uninstall` first, which deletes the current indexes. Documents loaded after the backup are not part of it; load them again from the saved Solr document directories.

## A release that cannot keep the indexes

When step 1 says the indexes cannot be kept, back up first, remove the registry, and let the script install the new one and reload it:

```bash
export REGISTRY_MGR_SOLR_HOME="$INSTALL_DIR/registry-manager-legacy"
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" backup before-reinstall
"$REGISTRY_MGR_SOLR_HOME/bin/registry_legacy_installer_docker.sh" uninstall
RELOAD_ALL="prod" ./install-release.sh "$VERSION"
```

With no registry container, the script installs the registry instead of upgrading it. Keep the generated Solr documents until the new registry is verified, so no new harvest is needed.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `The processes above are running` | A harvest, catalog run or document load is running. Wait for it to end, or set `FORCE=yes` |
| `DATA_HOME must be set` | Export `DATA_HOME`, or run with `UPGRADE_REGISTRY=no` to install only the tools |
| `Could not download ... has release v... been published?` | The release number is wrong or the release has no tarballs yet. Check the [releases page](https://github.com/NASA-PDS/registry-legacy-solr/releases) |
| `... exists and is not a symlink` | A real directory has the name of a tool link in `INSTALL_DIR`. Move it away and run again |
| `Solr did not answer ... after the upgrade` | Check `docker ps -a` and `docker logs` for the registry container |
| `RELOAD_ALL=... needs ...` | A variable that the reload needs is not set (step 2) |
| `Smoke query FAILED` | Look at the handler named in the message, check the Solr logs, and roll back (step 9) if the registry does not recover |
