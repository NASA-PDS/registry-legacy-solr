package gov.nasa.pds.harvest.search.util;

import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.xml.transform.sax.SAXSource;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;

import net.sf.saxon.Configuration;
import net.sf.saxon.lib.ParseOptions;
import net.sf.saxon.om.DocumentInfo;
import net.sf.saxon.tree.tiny.TinyElementImpl;
import net.sf.saxon.tree.tiny.TinyNodeImpl;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.xpath.XPathEvaluator;

import org.json.JSONObject;
import org.json.XML;
import org.xml.sax.InputSource;

/**
 * Class to extract data from an XML file.
*/
public class XMLExtractor {
    /** Logger instance */
    private static final Logger log = Logger.getLogger(XMLExtractor.class.getName());
    
    /** The DOM source. */
    private DocumentInfo xml = null;

    /** The XPath evaluator object. */
    private XPathEvaluator xpath = null;

    /** Default namespace uri */
    private static String defaultNamespaceUri = "";

    /** Namespace Context */
    private static PDSNamespaceContext namespaceContext = null;

    /**
     * Constructor.
     *
     */
    public XMLExtractor() {
        xpath = new XPathEvaluator();
        xpath.getStaticContext().setDefaultElementNamespace(
                defaultNamespaceUri);
        if(namespaceContext != null) {
            xpath.getStaticContext().setNamespaceContext(namespaceContext);
        }
    }

    /**
     * Parse the given file.
     *
     * @param src An XML file.
     *
     * @throws XPathException If an error occurred while parsing the XML file.
     */
    public void parse(File src) throws XPathException {
      String uri = src.toURI().toString();
      Configuration configuration = xpath.getConfiguration();
      configuration.setLineNumbering(true);
      configuration.setXIncludeAware(true);
      ParseOptions options = new ParseOptions();
      options.setErrorListener(new XMLErrorListener());
      xml = configuration.buildDocument(new SAXSource(new InputSource(uri)),
          options);
    }

    /**
     * Parse the given file.
     *
     * @param src An XML file.
     *
     * @throws XPathException If an error occurred while parsing the XML file.
     */
    public void parse(String src) throws XPathException {
        parse(new File(src));
    }

    /**
     * Sets the default namespace URI.
     *
     * @param uri A URI.
     */
    public static void setDefaultNamespace(String uri) {
        //xpath.getStaticContext().setDefaultElementNamespace(uri);
        defaultNamespaceUri = uri;
    }

    /**
     * Get the default namespace URI.
     *
     * @return The namespace.
     */
    public static String getDefaultNamespace() {
      return defaultNamespaceUri;
    }

    /**
     * Sets the Namespace Context to support handling of namespaces
     * in XML documents.
     *
     * @param context The NamespaceContext object.
     */
    public static void setNamespaceContext(PDSNamespaceContext context) {
        //xpath.getStaticContext().setNamespaceContext(context);
        namespaceContext = context;
    }

    /**
     * Gets the value of the given expression.
     *
     * @param expression An XPath expression.
     *
     * @return The resulting value or null if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     * @throws XPathException
     */
    public String getValueFromDoc(String expression)
    throws XPathExpressionException, XPathException {
        return getValueFromItem(expression, xpath.setSource(xml));
    }

    /**
     * Gets the value of the given expression.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the
     * XPath expression.
     *
     * @return The resulting value or null if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public String getValueFromItem(String expression, Object item)
    throws XPathExpressionException {
        return xpath.evaluate(expression, item);
    }

    /**
     * Gets a Node object from the given expression.
     *
     * @param expression An XPath expression.
     *
     * @return A Node object.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public TinyElementImpl getNodeFromDoc(String expression)
    throws XPathExpressionException {
        return getNodeFromItem(expression, xml);
    }

    /**
     * Gets a Node object from the given expression.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the
     * XPath expression.
     *
     * @return A Node object.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public TinyElementImpl getNodeFromItem(String expression, Object item)
    throws XPathExpressionException {
        return (TinyElementImpl) xpath.evaluate(expression, item,
            XPathConstants.NODE);
    }

    /**
     * Gets the values of the given expression.
     *
     * @param expression An XPath expression.
     *
     * @return The resulting values or an empty list if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getValuesFromDoc(String expression)
    throws XPathExpressionException {
        return getValuesFromItem(expression, xml);
    }

    /**
     * Gets the values of the given expression.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the
     * XPath expression.
     *
     * @return The resulting values or an empty list if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getValuesFromItem(String expression, Object item)
    throws XPathExpressionException {
        List<String> vals = new ArrayList<String>();
        List<TinyElementImpl> nList = (List<TinyElementImpl>) xpath.evaluate(
            expression, item, XPathConstants.NODESET);
        if (nList != null) {
            for (int i = 0, sz = nList.size(); i < sz; i++) {
                TinyElementImpl aNode = nList.get(i);
                vals.add(aNode.getStringValue());
            }
        }
        return vals;
    }

    /**
     * Gets the document node of the XML document.
     *
     * @return The Document Node.
     * @throws XPathException
     */
    public DocumentInfo getDocNode() throws XPathException {
        return xpath.setSource(xml).getDocumentRoot();
    }

    /**
     * Gets Node objects from the given expression.
     *
     * @param expression An XPath expression.
     *
     * @return A NodeList object.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<TinyElementImpl> getNodesFromDoc(String expression)
    throws XPathExpressionException {
        return getNodesFromItem(expression, xml);
    }

    /**
     * Gets Node objects from the given expression.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the
     * XPath expression.
     *
     * @return A NodeList object.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<TinyElementImpl> getNodesFromItem(String expression, Object item)
    throws XPathExpressionException {
        return (List<TinyElementImpl>) xpath.evaluate(
                expression, item, XPathConstants.NODESET);
    }

    /**
     * Gets the values of the given expression.
     *
     * @param expression An XPath expression.
     *
     * @return The resulting values or an empty list if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getAttributeValuesFromDoc(String expression)
    throws XPathExpressionException {
        return getAttributeValuesFromItem(expression, xml);
    }

    /**
     * Gets the values of the given expression.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the
     * XPath expression.
     *
     * @return The resulting values or an empty list if nothing was found.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getAttributeValuesFromItem(String expression, Object item)
    throws XPathExpressionException {
        List<String> vals = new ArrayList<String>();
        List<TinyNodeImpl> nList = (List<TinyNodeImpl>) xpath.evaluate(
            expression, item, XPathConstants.NODESET);
        if (nList != null) {
            for (int i = 0, sz = nList.size(); i < sz; i++) {
                TinyNodeImpl aNode = nList.get(i);
                vals.add(aNode.getStringValue());
            }
        }
        return vals;
    }

    /**
     * Gets the values of the given expression as JSON strings.
     * Each matching node is converted to a JSON object and returned as a string.
     *
     * @param expression An XPath expression.
     *
     * @return A list of JSON strings, one for each matching node.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getValuesAsJsonFromDoc(String expression)
    throws XPathExpressionException {
        return getValuesAsJsonFromItem(expression, xml);
    }

    /**
     * Gets the values of the given expression as JSON strings.
     * Each matching node is converted to a JSON object and returned as a string.
     *
     * @param expression An XPath expression.
     * @param item The starting point from which to evaluate the XPath expression.
     *
     * @return A list of JSON strings, one for each matching node.
     *
     * @throws XPathExpressionException If the given expression was malformed.
     */
    public List<String> getValuesAsJsonFromItem(String expression, Object item)
    throws XPathExpressionException {
        List<String> jsonStrings = new ArrayList<String>();
        List<TinyElementImpl> nList = (List<TinyElementImpl>) xpath.evaluate(
            expression, item, XPathConstants.NODESET);

        if (nList != null) {
            for (int i = 0, sz = nList.size(); i < sz; i++) {
                TinyElementImpl node = nList.get(i);
                try {
                    // Convert the node to an XML string
                    String xmlString = nodeToString(node);

                    // Convert XML to JSON using org.json library
                    JSONObject jsonObject = XML.toJSONObject(xmlString);

                    // Add the JSON string to the result list
                    jsonStrings.add(jsonObject.toString());
                } catch (RuntimeException e) {
                    // Let RuntimeExceptions (programming errors) propagate
                    throw e;
                } catch (Exception e) {
                    // If conversion fails, log and skip this node
                    log.warning("Failed to convert XML node to JSON: " + e.getMessage());
                }
            }
        }
        return jsonStrings;
    }

    /**
     * Converts a TinyElementImpl node to an XML string.
     *
     * @param node The node to convert.
     *
     * @return An XML string representation of the node.
     *
     * @throws Exception If conversion fails.
     */
    private String nodeToString(TinyElementImpl node) throws Exception {
        // Use Saxon's built-in serialization
        net.sf.saxon.s9api.Processor processor = new net.sf.saxon.s9api.Processor(false);
        net.sf.saxon.s9api.Serializer serializer = processor.newSerializer();

        StringWriter writer = new StringWriter();
        serializer.setOutputWriter(writer);
        serializer.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.OMIT_XML_DECLARATION, "yes");
        serializer.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.INDENT, "no");

        serializer.serializeNode(new net.sf.saxon.s9api.XdmNode(node));

        return writer.toString();
    }
}
