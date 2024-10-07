FROM solr:9.7.0
ADD VERSION.txt .

# Config sets
COPY collections/registry /opt/solr/server/solr/configsets/registry/conf
COPY collections/data /opt/solr/server/solr/configsets/data/conf

# Plugins
COPY dist /opt/solr/contrib/pds/lib

# Libs
COPY lib/Saxon* /opt/solr/server/solr-webapp/webapp/WEB-INF/lib

ENTRYPOINT ["/bin/bash", "-c", "solr start -c -Djavax.xml.transform.TransformerFactory=net.sf.saxon.TransformerFactoryImpl -Dsolr.pds.home=/opt/solr/server/solr/ -Dsolr.modules=scripting && tail -f /dev/null"]