package gov.nasa.pds.citool;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import gov.nasa.pds.citool.commandline.options.ConfigKey;
import gov.nasa.pds.citool.commandline.options.Flag;
import gov.nasa.pds.citool.commandline.options.InvalidOptionException;
import gov.nasa.pds.citool.commandline.options.Mode;
import gov.nasa.pds.citool.commandline.options.ToolsPropertiesConfiguration;
import gov.nasa.pds.citool.registry.client.RegistryClientManager;
import gov.nasa.pds.citool.report.CompareReport;
import gov.nasa.pds.citool.report.IngestReport;
import gov.nasa.pds.citool.report.Report;
import gov.nasa.pds.citool.report.ValidateReport;
import gov.nasa.pds.citool.search.DocConfigManager;
import gov.nasa.pds.citool.search.DocGenerator;
import gov.nasa.pds.citool.target.Target;
import gov.nasa.pds.citool.util.ToolInfo;
import gov.nasa.pds.citool.util.Utility;
import gov.nasa.pds.tools.constants.Constants.Severity;

/**
 * Front end class for the Catalog Ingestion Tool.
 *
 * @author mcayanan, hyunlee
 *
 */
public class CITool 
{
	private static final String DEFAULT_REGISTRTY_URL = "http://localhost:8983/solr"; 
	
    private List<Target> targets;
    private List<String> dictionaries;
    private List<URL> includePaths;
    private Target oldTarget;
    private Target newTarget;
    private File reportFile;
    private Severity severity;
    private boolean traverse;
    private boolean alias;
    private Target target;
    private URL allrefs;
 
    private String registryUrl;
    private String docConfifDir;
    private String outputDir;
        
    private Mode toolMode;
    private Report report;

    private Logger log;
    
    public CITool() 
    {
    	log = Logger.getLogger(this.getClass().getName());
    	
        targets = new ArrayList<Target>();
        dictionaries = new ArrayList<String>();
        includePaths = new ArrayList<URL>();
        oldTarget = null;
        newTarget = null;
        severity = Severity.WARNING;
        toolMode = null;
        alias = false;
        traverse = true;
        reportFile = null;
        target = null;
        allrefs = null;
        report = null;
    }

    /**
     * Parses the CITool command-line.
     *
     * @param argv arguments given on the command-line
     * @throws ApplicationException
     */
    public CommandLine parseLine(String[] argv) throws Exception {
        CommandLineParser parser = new GnuParser();
        try {
            return parser.parse(Flag.getOptions(), argv);
        }
        catch( ParseException pEx ) {
            throw new Exception("Command line parser failed.\n\n"
                    + "Reason: " + pEx.getMessage());
        }
    }

    
    private void processCommandLine(CommandLine commandLine) throws Exception
    {
        List<Option> processedOptions = Arrays.asList(commandLine.getOptions());
        List<String> targetList = new ArrayList<String>();

        //Gets the implicit targets
        for(Iterator<String> i = commandLine.getArgList().iterator(); i.hasNext();) 
        {
            String[] values = i.next().split(",");
            for(int index=0; index < values.length; index++) 
            {
                targetList.add(values[index].trim());
            }
        }
        
        for (Option o : processedOptions) 
        {
            if (o.getOpt().equals(Flag.HELP.getShortName())) 
            {
                showHelp();
                System.exit(0);
            } 
            else if (o.getOpt().equals(Flag.VERSION.getShortName())) 
            {
                showVersion();
                System.exit(0);
            } 
            else if (o.getOpt().equals(Flag.CONFIG.getShortName())) 
            {
                File c = new File(o.getValue());
                if (c.exists()) {
                    query(c);
                } else {
                    throw new InvalidOptionException("Configuration file " + "does not exist: " + c);
                }
            } 
            else if (o.getOpt().equals(Flag.DOC_CONFIG.getShortName()))             
            {
                this.docConfifDir = o.getValue();
            } 
            else if (o.getOpt().equals(Flag.TARGET.getShortName()))             
            {
                targetList.addAll(o.getValuesList());
            } 
            else if (o.getOpt().equals(Flag.ALIAS.getShortName())) {
                setAlias(true);
            } else if (o.getOpt().equals(Flag.LOCAL.getShortName())) {
                setTraverse(false);
            } else if (o.getOpt().equals(Flag.MODE.getShortName())) {
                setFunction(o.getValue().toUpperCase());
            } else if (o.getOpt().equals(Flag.PDSDD.getShortName())) {
                setDictionaries(o.getValuesList());
            } else if (o.getOpt().equals(Flag.INCLUDES.getShortName())) {
                setIncludePaths(o.getValuesList());
            } else if (o.getOpt().equals(Flag.REPORT.getShortName())) {
                setReportFile(new File(o.getValue()));
            } else if (o.getOpt().equals(Flag.VERBOSE.getShortName())) {
                setSeverity(Integer.parseInt(o.getValue()));
            }
            else if (o.getOpt().equals(Flag.ALLREFS.getShortName())) {
                setAllrefs(o.getValue());
            } 
            else if (o.getOpt().equals(Flag.OUTPUT_DIR.getShortName())) {
            	this.outputDir = o.getValue();
            }
        }
        
        //while statement removes empty values that get added to the list as
        //a result of a bug in the Commons-CLI library. Only occurs when
        //passing multiple values, separated with a comma.
        while(targetList.remove(""));
        
        if (!targetList.isEmpty()) {
            List<Target> tList = new ArrayList<Target>();
            for (String t : targetList) {
                tList.add(new Target(t));
            }
            setTargets(tList);
        }
    }

    
    /**
     * Query the CommandLine object to process the options that were selected.
     *
     * @param commandLine The CommandLine object
     *
     * @throws Exception
     */
    public void query(CommandLine commandLine) throws Exception 
    {
    	processCommandLine(commandLine);

        try {
        	if (toolMode == null) 
        	{
        		throw new InvalidOptionException("No mode specified. 'm' "
                        + "flag must be specified.");
        	} 
        	else 
        	{
        		if (toolMode.equals(Mode.COMPARE)) {
        			if (targets.size() == 2) {
        				oldTarget = targets.get(0);
        				newTarget = targets.get(1);
        				//Check for valid targets
        				oldTarget.toURL().openStream().close();
        				newTarget.toURL().openStream().close();
        			} else {
        				throw new InvalidOptionException("2 Targets must be "
        						+ "specified when running in compare mode");
        			}
        		} 
        		else if (toolMode.equals((Mode.INGEST))) 
        		{
        			if (targets.size() == 1) 
        			{
        				target = targets.get(0);
        			} 
        			else 
        			{
        				throw new InvalidOptionException("No target specified.");
        			}
        			
        			registryUrl = System.getProperty("pds.registry");
        			if (registryUrl == null) 
        			{
        				registryUrl = DEFAULT_REGISTRTY_URL;
        				log.warning("'pds.registry' java property is not set. Using default: " + registryUrl);
        			}
        			
        			if(docConfifDir == null)
        			{
        				throw new InvalidOptionException("No doc-config specified.");
        			}
        		} 
        		else if (toolMode.equals(Mode.VALIDATE)) {
        			if (dictionaries.isEmpty()) {
        				throw new InvalidOptionException("-d must be specified "
        						+ "when running in validate mode.");
        			}
        			if (targets.size() == 0) {
        				throw new InvalidOptionException("No target specified.");
        			} else if (targets.size() == 1) {
        				target = targets.get(0);
        			} else {
        				throw new InvalidOptionException("Only 1 target should be specified.");
        			}
        			if (allrefs != null) {
        				allrefs.openStream().close();
        			}
        		} else {
        			throw new InvalidOptionException("No mode specified. 'm' "
        					+ "flag must be specified.");
        		}
        	}
            setupReport();
            printReportHeader();
        } catch(MalformedURLException mu) {
            throw new InvalidOptionException(mu.getMessage());
        } catch (IOException io) {
            throw new InvalidOptionException(io.getMessage());
        }
    }

    /**
     * Query the configuration file.
     *
     * @param configuration A configuration file.
     *
     * @throws ConfigurationException
     */
    public void query(File configuration) throws ConfigurationException {
        try {
            ToolsPropertiesConfiguration config = null;
            config = new ToolsPropertiesConfiguration(configuration);
            if (config.isEmpty()) {
                throw new ConfigurationException(
                        "Configuration file is empty: " + configuration);
            }
            if (config.containsKey(ConfigKey.MODE)) {
                setFunction(config.getString(ConfigKey.MODE).toUpperCase());
            }
            if (config.containsKey(ConfigKey.TARGET)) {
                // Removes quotes surrounding each pattern being specified
                List<String> list = config.getList(ConfigKey.TARGET);
                while (list.remove(""));
                list = Utility.removeQuotes(list);
                List<Target> targets = new ArrayList<Target>();
                for (String s : list) {
                    targets.add(new Target(s));
                }
                setTargets(targets);
            }
            if (config.containsKey(ConfigKey.DICTIONARIES)) {
                List<String> list = config.getList(ConfigKey.DICTIONARIES);
                list = Utility.removeQuotes(list);
                setDictionaries(list);
            }
            if (config.containsKey(ConfigKey.INCLUDES)) {
                List<String> list = config.getList(ConfigKey.INCLUDES);
                list = Utility.removeQuotes(list);
                setIncludePaths(list);
            }
            if (config.containsKey(ConfigKey.VERBOSE)) {
                setSeverity(config.getInt(ConfigKey.VERBOSE));
            }
            if (config.containsKey(ConfigKey.ALIAS)) {
               setAlias(config.getBoolean(ConfigKey.ALIAS));
            }
            if (config.containsKey(ConfigKey.LOCAL)) {
                if (config.getBoolean(ConfigKey.LOCAL)) {
                    setTraverse(false);
                } else {
                    setTraverse(true);
                }
            }
            if (config.containsKey(ConfigKey.ALLREFS)) {
                setAllrefs(config.getString(ConfigKey.ALLREFS));
            }
            if (config.containsKey(ConfigKey.REPORT)) {
                setReportFile(new File(config.getString(ConfigKey.REPORT)));
            }
        } catch(Exception e) {
            throw new ConfigurationException(e.getMessage());
        }
    }

    public void setTraverse(boolean value) {
        this.traverse = value;
    }

    public void setTargets(List<Target> targets) {
        this.targets = targets;
        while(this.targets.remove(""));
    }

    public void setIncludePaths(List<String> paths)
    throws MalformedURLException {
        while(paths.remove(""));
        for(String path : paths) {
            includePaths.add(Utility.toURL(path));
        }
    }

    public void setAlias(boolean value) {
        alias = value;
    }

    public void setAllrefs(String allrefs) throws MalformedURLException {
        this.allrefs = Utility.toURL(allrefs);
    }

    public void setDictionaries(List<String> dictionaries) {
        this.dictionaries = dictionaries;
        while (this.dictionaries.remove(""));
    }

    public void setReportFile(File file) {
        reportFile = file;
    }

    public void setSeverity(int value) throws InvalidOptionException {
        if (value == 0) {
            severity = Severity.NONE;
        } else if (value == 1) {
            severity = Severity.INFO;
        } else if (value == 2) {
            severity = Severity.WARNING;
        } else if (value == 3) {
            severity = Severity.ERROR;
        } else {
            throw new InvalidOptionException("Invalid verbose value: "
                    + value + ". Value must be 1, 2, or 3.");
        }
    }

    public Mode getToolMode() {
        return toolMode;

    }

    public void setToolMode(Mode mode) {
        this.toolMode = mode;
    }

    public void setFunction(String functionValue) throws InvalidOptionException {
        if (Mode.COMPARE.getName().equals(functionValue)) {
            setToolMode(Mode.COMPARE);
        } else if (Mode.INGEST.getName().equals(functionValue)) {
            setToolMode(Mode.INGEST);
        } else if (Mode.VALIDATE.getName().equals(functionValue)) {
            setToolMode(Mode.VALIDATE);
        } else {
            throw new InvalidOptionException("Invalid mode value: "
                    + functionValue + ". Value must be 'compare', 'ingest', "
                    + "or 'validate'.");
        }
    }
    

    /**
     * Show the version and disclaimer notice.
     *
     */
    public void showVersion() {
        System.err.println("\n" + ToolInfo.getName());
        System.err.println(ToolInfo.getVersion());
        System.err.println("Release Date: " + ToolInfo.getReleaseDate());
        System.err.println(ToolInfo.getCopyright() + "\n");
    }

    /**
     * Show the CITool usage.
     */
    public void showHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(80, "catalog", null, Flag.getOptions(), null);
    }



    private void setupReport() throws IOException {
        if (toolMode.equals(Mode.COMPARE)) {
            report = new CompareReport();
        } else if (toolMode.equals(Mode.INGEST)) {
            report = new IngestReport();
        } else if (toolMode.equals(Mode.VALIDATE)) {
            report = new ValidateReport();
        }
        report.setLevel(severity);
        if (reportFile != null) {
            report.setOutput(reportFile);
        }
        //This removes the log4j messages
        // ConsoleAppender ca = new ConsoleAppender(new PatternLayout("%-5p %m%n"));
        // ca.setThreshold(Priority.FATAL);
        // BasicConfigurator.configure(ca);

        ConfigurationBuilder<BuiltConfiguration> builder =
            ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.FATAL);

        AppenderComponentBuilder appenderBuilder = builder.newAppender("Stdout", "CONSOLE")
            .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        appenderBuilder
            .add(builder.newLayout("PatternLayout").addAttribute("pattern", "%-5p %m%n"));
        LoggerContext ctx = Configurator.initialize(builder.build());
        ctx.updateLoggers();
    }

    private void printReportHeader() throws MalformedURLException {
        report.addConfiguration("Version                " + ToolInfo.getVersion());
        report.addConfiguration("Date                   " + Utility.getDateTime());

        report.addParameter("Mode                   " + toolMode.getName().toLowerCase());
        if (toolMode.equals(Mode.COMPARE)) {
            if (!targets.isEmpty()) {
                report.addParameter("Target(s)                ");
            }
            if (oldTarget != null) {
                report.addParameter("  Source = " + oldTarget.toURL());
            }
            if (newTarget != null) {
                report.addParameter("  Target = " + newTarget.toURL());
            }
        } else if (target != null) {
            report.addParameter("Target                 " + target.toURL());
        }
        report.addParameter("Directory Recursion    " + traverse);
        if (!dictionaries.isEmpty()) {
            report.addParameter("Dictionary File(s)     " + dictionaries);
        }
        if (!includePaths.isEmpty()) {
            report.addParameter("Include Path(s)        " + includePaths);
        }
        if (allrefs != null) {
            report.addParameter("Allrefs File           " + allrefs.toString());
        }
        report.addParameter("Severity Level         " + severity.getName());
        if (reportFile != null) {
            report.addParameter("Report File            " + reportFile);
        }
        if (toolMode.equals(Mode.VALIDATE)) {
            report.addParameter("Aliasing Enabled       " + alias);
        }
    }

    public void processMain(String[] args) {
      // This removes the log4j messages
      ConfigurationBuilder<BuiltConfiguration> builder =
          ConfigurationBuilderFactory.newConfigurationBuilder();
      builder.setStatusLevel(Level.FATAL);

      AppenderComponentBuilder appenderBuilder = builder.newAppender("Stdout", "CONSOLE")
          .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
      appenderBuilder.add(builder.newLayout("PatternLayout").addAttribute("pattern", "%-5p %m%n"));
      LoggerContext ctx = Configurator.initialize(builder.build());
      ctx.updateLoggers();
        if (args.length == 0) {
            System.out.println("\nType 'catalog-legacy -h' for usage");
            System.exit(0);
        }
        try {
            CommandLine commandline = parseLine(args);
            query(commandline);
            
            if (Mode.COMPARE.equals(toolMode)) 
            {
                CIToolComparator comparator = new CIToolComparator((CompareReport) report);
                report.printHeader();
                comparator.compare(oldTarget, newTarget, traverse);
                report.printFooter();
            } 
            else if (Mode.INGEST.equals(toolMode)) 
            {
            	report.printHeader();
            	
            	DocConfigManager.init(docConfifDir);
            	RegistryClientManager.init(registryUrl);
            	DocGenerator.init(outputDir);
            	
            	CIToolIngester ingester = new CIToolIngester((IngestReport) report);

            	try
            	{
            		ingester.ingest(target, traverse);
            	}
            	finally
            	{
            		DocGenerator.getInstance().close();
            	}
            	
            	report.printFooter();
            } 
            else if (Mode.VALIDATE.equals(toolMode)) 
            {
                CIToolValidator validator = new CIToolValidator(report, includePaths);
                report.printHeader();
                for (Target target : targets) {
                    validator.validate(target, dictionaries, traverse, alias);
                }
                report.printFooter();
            }
        } catch (Exception e) {
        	e.printStackTrace();
            //System.err.println("\nException: " + e.getMessage() + "\n");
        }
    }

    public static void main(String[] argv) {
        new CITool().processMain(argv);
    }
}
