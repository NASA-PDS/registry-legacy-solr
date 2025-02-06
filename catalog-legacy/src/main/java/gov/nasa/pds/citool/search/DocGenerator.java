package gov.nasa.pds.citool.search;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import gov.nasa.pds.citool.ingestor.CatalogObject;
import gov.nasa.pds.citool.registry.model.RegistryObject;
import gov.nasa.pds.citool.registry.model.Slots;
import gov.nasa.pds.citool.util.RegistryObjectCache;
import gov.nasa.pds.search.core.logging.ToolsLevel;
import gov.nasa.pds.search.core.logging.ToolsLogRecord;
import gov.nasa.pds.search.core.schema.DataType;
import gov.nasa.pds.search.core.schema.Field;
import gov.nasa.pds.search.core.schema.OutputString;
import gov.nasa.pds.search.core.schema.OutputStringFormat;
import gov.nasa.pds.search.core.schema.Product;
import gov.nasa.pds.search.core.util.PDSDateConvert;
import gov.nasa.pds.tools.LabelParserException;
import gov.nasa.pds.tools.constants.Constants.ProblemType;


public class DocGenerator 
{
	private static DocGenerator instance;
	
	private Logger log;
	private String outDir;
	private DocWriter writer;
	
	private DocGenerator(String outDir) throws Exception
	{
		log = Logger.getLogger(this.getClass().getName());
		this.outDir = outDir;
	}

	public static void init(String outDir) throws Exception
	{
		instance = new DocGenerator(outDir);
	}
	
	public static DocGenerator getInstance()
	{
		return instance;
	}
	
	public void close() throws Exception
	{
      if (writer != null) {
        writer.close();
      }
	}
	
	
	public void addVolume(String volumeId) throws Exception
	{
		if(writer != null)
		{
			try 
			{ 
				writer.close(); 
			} 
			catch(Exception ex) 
			{
				// Ignore
			}
		}
		
		writer = new DocWriter(outDir, volumeId);
	}
	
	
    public void addDoc(CatalogObject obj) throws DocGeneratorException, IOException
	{
      RegistryObject ro = obj.getExtrinsicObject();
      String objType = ro.getObjectType();

      Product conf = DocConfigManager.getInstance().getConfigByObjectType(objType);
      if (conf == null) {
        throw new DocGeneratorException("Solr Doc Generator not configured to support " + objType);
      }

      Map<String, List<String>> docFields = getDocFields(conf, ro.getSlots(), obj);
      writer.write(docFields);
	}

	
    private Map<String, List<String>> getDocFields(Product conf, Slots slots, CatalogObject obj)
        throws DocGeneratorException
	{
		Map<String, List<String>> docFields = new TreeMap<String, List<String>>(); 
		
		for(Field field: conf.getIndexFields().getField())
		{
          List<String> values = getFieldValues(field, slots, obj);
			if(!values.isEmpty())
			{
				docFields.put(field.getName(), values);
			}
			else
			{
				if(field.getType() == DataType.REQUIRED)
				{
					log.warning("No value for required field " + field.getName());
				}
			}
		}

		return docFields;
	}
	
	
	private String normalizeDate(Field field, String value)
	{
		String fieldName = field.getName();
		
		try
		{
			value = PDSDateConvert.convert(fieldName, value);
		} 
		catch(Exception ex) 
		{
	        log.log(new ToolsLogRecord(ToolsLevel.WARNING, ex.getMessage() + " - " + fieldName));
			value = PDSDateConvert.getDefaultTime(fieldName);
		}
		
		return value;
	}


	private String convertValue(Field field, String value)
	{
		if(field.getType() == DataType.DATE)
		{
			return normalizeDate(field, value);
		}
		
		return value;
	}
	
	
    private List<String> handleComplexPath(String regPath, Slots slots, CatalogObject obj)
        throws DocGeneratorException
	{
		String pathArray[] = regPath.split("\\.");
		if(pathArray == null || pathArray.length < 2)
		{
			log.warning("Invalid registry path: " + regPath);
			return null;
		}
		
		List<String> lids = slots.get(pathArray[0]);		
        if (lids == null || lids.isEmpty()) {
          LabelParserException lp = new LabelParserException(obj.getLabel().getLabelURI(), null,
              null, "ingest.warning.missingRefValue", ProblemType.UNKNOWN_VALUE, pathArray[0]);
          obj.getLabel().addProblem(lp);
          return null;
		}

		List<String> values = new ArrayList<String>();
		
		for(String lid: lids)
		{
			RegistryObject ro = RegistryObjectCache.getInstance().get(lid);
			if(ro != null)
			{
				List<String> tmpVals = ro.getSlots().get(pathArray[1]);
				if(tmpVals != null)
				{
					values.addAll(tmpVals);
				}
			}
			else
			{
				log.warning("Could not find lid: " + lid);
			}
		}
		
		return values;
	}
	
	
    private List<String> getFieldValues(Field field, Slots slots, CatalogObject obj)
        throws DocGeneratorException
	{
		List<String> docValues = new ArrayList<String>(); 
		
		// Registry path
		for(String regPath: field.getRegistryPath())
		{			
			List<String> values = null;
		
			if(regPath.contains("."))
			{
              values = handleComplexPath(regPath, slots, obj);
			}
			else
			{
				values = slots.get(regPath);
			}
				
			if(values != null)
			{
				for(String value: values)
				{
					docValues.add(convertValue(field, value));
				}
			}
		}
		
		// Output string
		if(docValues.isEmpty() && field.getOutputString() != null) 
		{
          String value = handleTemplate(field.getOutputString(), slots, obj);
			if(value != null)
			{
				docValues.add(value);
			}
		}
	    
		// Default value
		if(docValues.isEmpty() && field.getDefault() != null) 
		{
			docValues.add(field.getDefault());
		}

		return docValues;
	}
	
	
    private String handleTemplate(OutputString outString, Slots slots, CatalogObject catalogObject)
        throws DocGeneratorException
	{
		String str = outString.getValue();
		
	    while(str.contains("{")) 
	    {
			int start = str.indexOf("{");
			int end = str.indexOf("}", start);
			
			String key = str.substring(start + 1, end);
			String value = null;
			
            try {
			if(key.contains("."))
			{
              List<String> vals = handleComplexPath(key, slots, catalogObject);
				value = (vals != null && vals.size() > 0) ? vals.get(0) : null;
			}
			else
			{
				value = slots.getFirst(key);
			}
			
			if(value != null)
			{
				if(outString.getFormat().equals(OutputStringFormat.URL)) 
				{
					value = URLEncoder.encode(value, "UTF-8");
				} 

				str = str.replace("{" + key + "}", value);	          
			} 
			else 
			{
				log.warning("No value for key " + key);
				str = str.replace("{" + key + "}", "");
			}
          } catch (UnsupportedEncodingException e) {
            throw new DocGeneratorException(e.getClass().getName() + " : " + e.getMessage());
          }
	    }
	    
	    return str;
	}
	
}
