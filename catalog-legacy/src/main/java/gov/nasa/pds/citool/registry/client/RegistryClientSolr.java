package gov.nasa.pds.citool.registry.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import gov.nasa.pds.citool.registry.model.FileInfo;
import gov.nasa.pds.citool.registry.model.RegistryObject;


public class RegistryClientSolr implements RegistryClient 
{
	private static final String PDS_COLLECTION = "pds";
	private static final String FILE_COLLECTION = ".system";
	private static final String BLOB_EP = "/.system/blob/";

	private Logger log;
	private SolrClient solrClient;

	
	RegistryClientSolr(String solrUrl)
	{
		log = Logger.getLogger(this.getClass().getName());
		solrClient = new HttpSolrClient.Builder(solrUrl).build();
	}
	
	
	public String publishObject(RegistryObject obj) throws Exception
	{
		return obj.getLid();
	}


    public boolean publishFile(FileInfo fi) throws IOException
	{
		// A file with this MD5 hash already exists
		if(md5Exists(fi))
		{
			return false;
		}
		
		File file = new File(fi.path);
		String blobName = fi.lid.replaceAll(":", ".");
        String endPoint = BLOB_EP + blobName;
        
        ContentStreamUpdateRequest req = new ContentStreamUpdateRequest(endPoint);
        req.addFile(file, "application/octet-stream");
        req.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);

        try
        {
	        NamedList<Object> resp = solrClient.request(req);
	        Object error = resp.get("error"); 
	        if(error != null)
	        {
              throw new RegistryClientException("Blob: " + blobName + ": " + error.toString());
	        }
	    }
		catch (HttpSolrClient.RemoteSolrException ex)
		{
			// For some reason, RemoteSolrException is a sublcass of RuntimeException, even though
			// that's typically for unchecked exceptions of failures in the JVM itself, hence a
			// separate `catch` here.
			return false;
		}
	    catch (RuntimeException ex)
	    {
	    	throw ex;
	    }
	    catch (Exception ex)
	    {
	    	return false;
	    }

        return true;
	}


	public List<String> getResourceIds(String dataSetId) throws Exception
	{
		List<String> ids = new ArrayList<String>();
		if(dataSetId == null) return ids;
		
		String qStr = createGetResourceIdsQuery(dataSetId);
		
		SolrQuery query = new SolrQuery();
		query.add("q", qStr);
		query.add("fl", "identifier");
		query.add("rows", "100");
		
		try
		{
			QueryResponse resp = solrClient.query(PDS_COLLECTION, query);
			SolrDocumentList res = resp.getResults();		
			
			for(SolrDocument doc: res)
			{
				Object obj = doc.getFirstValue("identifier");
				if(obj != null)
				{
					ids.add(obj.toString());
				}
			}
			
		}
		catch (HttpSolrClient.RemoteSolrException ex)
		{
			// For some reason, RemoteSolrException is a sublcass of RuntimeException, even though
			// that's typically for unchecked exceptions of failures in the JVM itself, hence a
			// separate `catch` here.
			return ids;
		}
		catch (RuntimeException ex)
		{
			throw ex;
		}
		catch (Exception ex)
		{
			// Ignore
		}

		return ids;
	}
	
	
    private boolean md5Exists(FileInfo fi)
	{
		// If md5 starts with '0' Solr 7.7.x strips it out. 
		// Is it a Solr bug?
		String md5 = fi.md5.startsWith("0") ? fi.md5.substring(1) : fi.md5;
				
		SolrQuery query = new SolrQuery("md5:\"" + md5 + "\"");
		try
		{
			QueryResponse resp = solrClient.query(FILE_COLLECTION, query);
			SolrDocumentList res = resp.getResults();
			return res.getNumFound() > 0;
		}
		catch (HttpSolrClient.RemoteSolrException ex)
		{
			// For some reason, RemoteSolrException is a sublcass of RuntimeException, even though
			// that's typically for unchecked exceptions of failures in the JVM itself, hence a
			// separate `catch` here.
			return false;
		}
		catch (RuntimeException ex)
		{
			throw ex;
		}
		catch (Exception ex)
		{
			// Ignore
		}
		return false;
	}
	
	
	private static String createGetResourceIdsQuery(String dataSetId)
	{
		return "identifier:urn\\:nasa\\:pds\\:context_pds3\\:resource\\:resource."
				+ dataSetId.toLowerCase() + "__* AND data_class:Resource";
	}

}
