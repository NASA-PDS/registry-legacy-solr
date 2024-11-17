package gov.nasa.pds.citool;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import gov.nasa.pds.citool.comparator.CatalogComparator;
import gov.nasa.pds.citool.ingestor.CatalogObject;
import gov.nasa.pds.citool.ingestor.CatalogVolumeIngester;
import gov.nasa.pds.citool.report.IngestReport;
import gov.nasa.pds.citool.search.DocGenerator;
import gov.nasa.pds.citool.target.Target;
import gov.nasa.pds.citool.util.ReferenceUtils;
import gov.nasa.pds.citool.util.References;
import gov.nasa.pds.citool.util.RegistryObjectCache;
import gov.nasa.pds.tools.LabelParserException;
import gov.nasa.pds.tools.constants.Constants.ProblemType;
import gov.nasa.pds.tools.label.Label;
import gov.nasa.pds.tools.label.ManualPathResolver;
import gov.nasa.pds.tools.label.parser.DefaultLabelParser;


public class CIToolIngester 
{
	private Logger log;
	private IngestReport report;
	
	
    public CIToolIngester(IngestReport report)
    {
        this.report = report;
        log = Logger.getLogger(this.getClass().getName());
    }
    
    
    /**
     * Ingest catalog file(s).
     *
     * @param target URL of the target (directory or file)
     * @throws Exception
     */
    public void ingest(Target target, boolean recurse) throws Exception 
    {
        if(target.isDirectory())
        {
            List<URL> urls = target.traverse(recurse);
            List<Label> catLabels = parseLabels(urls);
            processVolume(catLabels);
        }
        else
        {
        	throw new Exception("Target must be a directory: " + target.toURL());
        }
    }

    
    private List<Label> parseLabels(List<URL> urls)
    {
    	List<Label> catLabels = new ArrayList<Label>();
    	
        for(URL url: urls) 
        {
        	Label lbl = parse(url);
        	if(lbl != null)
        	{
        		catLabels.add(lbl);
        	}
        }
    	
        return catLabels;
    }
    
    
    public void processVolume(List<Label> catLabels) throws Exception
    {   	
    	CatalogVolumeIngester catIngester = createCatalogVolumeIngester(catLabels);
    	
    	References.getInstance().clear();
    	RegistryObjectCache.getInstance().clear();
    	
    	// generate Reference info for each catObj, it will be used to create associations
    	Map<String, List<String>> refs = ReferenceUtils.populateReferenceEntries(catIngester);
 
    	// Publish file objects
    	for(CatalogObject obj: catIngester.getCatalogObjects())
    	{	
    		if (obj.getIsLocal()) 
    		{
    			catIngester.ingest(obj);	
    			report.record(obj.getLabel().getLabelURI(), obj.getLabel().getProblems());
    		}    		
       	} 
       	
    	// Set references
    	for(CatalogObject obj: catIngester.getCatalogObjects())
    	{
    		if(obj.getIsLocal())
    		{
    			// Update extrinsic object with associations
    			catIngester.setProductReferences(catIngester.getCatalogObjects(), obj, refs);

    			// Cache the registry object. Search doc publisher may need it to resolve
    			// complex references like "INSTRUMENT_HOST_ID={instrument_host_ref.instrument_host_id}"
    			RegistryObjectCache.getInstance().put(obj.getExtrinsicObject());
    		}
    	}
    	
    	DocGenerator.getInstance().addVolume(catIngester.getVolumeId());
    	
    	// Generate search docs
    	for(CatalogObject obj: catIngester.getCatalogObjects())
    	{
    		if(obj.getIsLocal())
    		{
    			catIngester.publishObject(obj);
    		}
    	}
    }

    
    private CatalogVolumeIngester createCatalogVolumeIngester(List<Label> catLabels) 
    {
    	CatalogVolumeIngester catIngester = new CatalogVolumeIngester();
    	
		List<String> pointerFiles = null;
		
		// read a set of catalog archive volume files
		// TODO: need to make sure to read voldesc.cat first
		// how to handle the multiple sets of CATALOG object
		boolean isVolumeCatalog = false;
		
		// TODO: need to add to handle multiple catalog objects (sets of catalog references???)
		for (Label lbl : catLabels) 
		{
			CatalogObject catObj = new CatalogObject(this.report);
			boolean validFile = catObj.processLabel(lbl);
			if (validFile) 
			{
				if(catObj.getCatObjType().equalsIgnoreCase("VOLUME"))
				{
					pointerFiles = catObj.getPointerFiles();
					
					String volumeId = catObj.getPdsLabelMap().get("VOLUME_ID").getValue().toString().trim();					
					String volumeSetId = catObj.getPdsLabelMap().get("VOLUME_SET_ID").getValue().toString().trim();
					String id = (volumeSetId + "__" + volumeId).toLowerCase();
					catIngester.setVolumeId(id);
					
					isVolumeCatalog = true;
				}
				
				catIngester.addCatalogObject(catObj);
			}
			else 
			{
				LabelParserException lp = new LabelParserException(
                        lbl.getLabelURI(), null, null,
                        "ingest.warning.skipFile",
                        ProblemType.INVALID_LABEL_WARNING, "This file is not required to ingest into the registry service.");
                report.recordSkip(lbl.getLabelURI(), lp);
			}
		}
	    
		if (!isVolumeCatalog) 
		{
			System.err.println("\nError: VOLUME catalog object is missing in this archive volume. Can't process further.\n");
			System.exit(1);
		}
		
		for(String ptrFile : pointerFiles) 
		{
			// Ignore the reference file when it's N/A
			if(ptrFile.equals("N/A")) 
			{
				continue;
			}

			if(!catIngester.labelExists(ptrFile))
			{
				log.warning("Missing catalog file " + ptrFile);
			}
		}
		
		return catIngester;
    }
    
         
    /**
     * Method to parse the PDS catalog file
     * @param url URL of the pds catalog file
     * 
     * @return a Label object
     */
    public Label parse(URL url) 
    {
    	URI uri = null;
        try {
            uri = url.toURI();
        } catch (URISyntaxException u) {
            //Ignore
        }
        ManualPathResolver resolver = new ManualPathResolver();
        resolver.setBaseURI(ManualPathResolver.getBaseURI(uri));
        //Parser must have "parser.pointers" set to false
        DefaultLabelParser parser = new DefaultLabelParser(false, true, resolver);
        Label label = null;
        try 
        {
            label = parser.parseLabel(url);
        } 
        catch (LabelParserException lp) 
        {
        	lp.printStackTrace();
            //Product tools library records files that have a missing
            //PDS_VERSION_ID as an error. However, we want CITool to record
            //this as a warning, so we need to instantiate a new
            //LabelParserException.
            if("parser.error.missingVersion".equals(lp.getKey())) {
                report.recordSkip(uri, new LabelParserException(
                        lp.getSourceFile(), null, null,
                        lp.getKey(), ProblemType.INVALID_LABEL_WARNING,
                        lp.getArguments()));
            }
            else {
                report.recordSkip(uri, lp);
            }
        } catch (Exception e) {
        	e.printStackTrace();
            report.recordSkip(uri, e);
        }
        return label;
    }
    
    
    /**
     * Perform a comparison between 2 files.
     *
     * @param source URL of the source file.
     * @param target URL of the target file.
     * @throws LabelParserException
     * @throws IOException
     */
    public boolean compare(URL source, URL target) {
        Label sourceLabel = parse(source);
        Label targetLabel = parse(target);
        if (sourceLabel == null || targetLabel == null) {
            return false;
        }
        if (sourceLabel.getProblems().isEmpty()
            && targetLabel.getProblems().isEmpty()) {
            CatalogComparator comparator = new CatalogComparator();
            targetLabel = comparator.checkEquality(sourceLabel, targetLabel);
            report.record(targetLabel.getLabelURI(),
                    targetLabel.getProblems());
            
            if (targetLabel.getProblems().isEmpty()) { // same file
            	return true;
            }
            else 
            	return false;           
        }
        else {
            if (!sourceLabel.getProblems().isEmpty()) {
                LabelParserException lp = new LabelParserException(
                        sourceLabel.getLabelURI(), null, null,
                        "compare.source.UnParseable",
                        ProblemType.INVALID_LABEL, sourceLabel.getLabelURI());
                report.recordSkip(sourceLabel.getLabelURI(), lp);
            }
            if (!targetLabel.getProblems().isEmpty()) {
                LabelParserException lp = new LabelParserException(
                        sourceLabel.getLabelURI(), null, null,
                        "compare.target.UnParseable",
                        ProblemType.INVALID_LABEL, targetLabel.getLabelURI());
                report.recordSkip(targetLabel.getLabelURI(), lp);
            }
            return false;
        }
    }
}
