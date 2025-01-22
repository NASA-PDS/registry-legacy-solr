package gov.nasa.pds.citool.search;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang.StringEscapeUtils;

public class DocWriter 
{
	private File outDir;
	private FileWriter writer;	
	
    public DocWriter(String outDir, String volumeId) throws IOException
	{
		this.outDir = new File(outDir);
		this.outDir.mkdirs();
		
		File file = new File(this.outDir, volumeId + ".solr.xml");
		writer = new FileWriter(file);
		writer.write("<add>\n");
	}
	
    public void close() throws IOException
	{
		writer.write("</add>\n");
		writer.close();
	}
	
    public void write(Map<String, List<String>> fields) throws IOException
	{
		writer.write("<doc>\n");
		writer.write("<field name=\"package_id\">" + UUID.randomUUID().toString() + "</field>\n");
		
		for(Map.Entry<String, List<String>> field: fields.entrySet())
		{
			String fieldName = field.getKey();
			for(String value: field.getValue())
			{
				writer.write("<field name=\"" + fieldName + "\">");
				StringEscapeUtils.escapeXml(writer, value);
				writer.write("</field>\n");
			}
		}
		
		writer.write("</doc>\n");
	}
}
