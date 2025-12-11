# PDS Legacy Registry Manager
The PDS Legacy Registry provides functionality for tracking, searching, auditing, locating, and maintaining artifacts within the system using our legacy Apache Solr capabilities. These artifacts can range from data files and label files, schemas, dictionary definitions for objects and elements, services, etc. Future implementations are already underway for the PDS Search API.

## Prerequisites

- **OpenJDK 17 or higher** - Required for building and running
- **Maven 3.x** - For building the project
- **Docker** - For Solr deployment

# Documentation
The documentation for the latest release of the PDS Registry, including release notes, installation and operation of the software are online at https://nasa-pds.github.io/registry-mgr-legacy/.

If you would like to get the latest documentation, including any updates since the last release, you can execute the "mvn site:run" command and view the documentation locally at http://localhost:8080.

# Build

**Note:** Ensure you have OpenJDK 17 or higher installed and `JAVA_HOME` set correctly before building.

The software can be compiled and built with the "mvn compile" command but in order
to create the JAR file, you must execute the "mvn compile jar:jar" command. 

In order to create a complete distribution package, execute the 
following commands: 

```
% mvn site
% mvn package
```
# Release
Here is the procedure for releasing the software both in Github and pushing the JARs to the public Maven Central repo.

## Pre-Requisites
* Make sure you have your GPG Key created and sent to server.
* Make sure you have your .settings configured correctly for GPG:
```
<profiles>
  <profile>
    <activation>
      <activeByDefault>true</activeByDefault>
    </activation>
    <properties>
      <gpg.executable>gpg</gpg.executable>
      <gpg.keyname>KEY_NAME</gpg.keyname>
      <gpg.passphrase>KEY_PASSPHRASE</gpg.passphrase>
    </properties>
  </profile>
</profiles>
```

## Operational Release
1. Checkout the dev branch.

2. Version the software:
```
mvn versions:set -DnewVersion=1.2.0
```

3. Deploy software to Sonatype Maven repo:
```
# Operational release
mvn clean site deploy -P release
```

4. Create pull request from dev -> master and merge.

5. Tag release in Github

6. Update version to next snapshot:
```
mvn versions:set -DnewVersion=1.3.0-SNAPSHOT
```

## SNAPSHOT Release
1. Checkout the dev branch.

2. Deploy software to Sonatype Maven repo:
```
# Operational release
mvn clean site deploy
```

# Maven JAR Dependency Reference

## Snapshots
https://oss.sonatype.org/content/repositories/snapshots/gov/nasa/pds/registry/

If you want to access snapshots, add the following to your `~/.m2/settings.xml`:
```
<profiles>
  <profile>
     <id>allow-snapshots</id>
     <activation><activeByDefault>true</activeByDefault></activation>
     <repositories>
       <repository>
         <id>snapshots-repo</id>
         <url>https://oss.sonatype.org/content/repositories/snapshots</url>
         <releases><enabled>false</enabled></releases>
         <snapshots><enabled>true</enabled></snapshots>
       </repository>
     </repositories>
   </profile>
</profiles>
```
