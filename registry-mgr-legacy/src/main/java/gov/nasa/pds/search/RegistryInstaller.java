package gov.nasa.pds.search;

import static gov.nasa.pds.search.util.RegistryInstallerUtils.copyDir;
import static gov.nasa.pds.search.util.RegistryInstallerUtils.getPreset;
import static gov.nasa.pds.search.util.RegistryInstallerUtils.print;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.nasa.pds.search.util.RegistryInstallerUtils;


/*
 * @author hyunlee
 *
 */
public class RegistryInstaller {
	private static final Logger logger = LoggerFactory.getLogger(RegistryInstaller.class);

	private static final String SEP = File.separator;

	// Number of bytes per gigabyte
	public static final long BYTES_PER_GIG = (long)1e+9;
	private static String solrCmd = "solr";

	private static String osName;
	private static String termName = null;
	private static int solrPort = 8983;
	private static String solrHost = "localhost";

	private static String registry_version;
	private static String registry_root;
	private static String solrRoot;
	private static String solrBin;

	private static String installType;

	private static boolean uninstall = false;

	private static String maxShardsPerNode;
	private static String numShards;
	private static String replicationFactor;

	public RegistryInstaller() {}

	public static void main(String args[]) throws Exception 
	{
		for (String arg : args) {
            if (arg.equals("--uninstall") || arg.equals("-u")) {
                uninstall = true;
                break;
            }
            if (arg.equals("--install") || arg.equals("-i")) {
                break;
            }
        }
		
		Scanner reader = new Scanner(System.in);  // Reading from System.in

		installType = "standalone"; // Scans the next token of the input as a string.			

		Path currentRelativePath = Paths.get("");
		registry_root = currentRelativePath.toAbsolutePath().toString() + SEP + "..";

		getVersion();
		getOsName();
		getTerminal();
		getSolrPort();
		getSolrHost();

		printWelcomeMessage();

		System.out.print("Enter location of SOLR installation: ");
		solrRoot = reader.next();

		init();
		if (osName.contains("Windows")) {
			solrCmd = "solr.cmd";
		}

		if(uninstall) 
		{
		    deleteCollection("data");
			deleteCollection("registry");
			
			stopSOLRServer();
			exit(0);
		}

		// copy search service confs, *jar and lib into SOLR directories
		setupSOLRDirs();
		startSOLRServer();

		maxShardsPerNode = getPreset("maxShardsPerNode");
		numShards = getPreset("numShards");
		replicationFactor = getPreset("replicationFactor");

		// Create collections
		createCollection("registry");
		createCollection("data");		

		reader.close();
	}

	private static void init() {
		solrBin  = solrRoot + SEP + "bin";
	}

	private static void exit(int status) {
		System.exit(status);
	}

	private static void getVersion() {
		registry_version = System.getenv("REGISTRY_VER");
	}

	private static void getOsName() {
		osName = System.getProperty("os.name");
	}

	private static void getTerminal() 
	{
		if (System.getenv("TERM") != null) 
		{
			termName = System.getenv("TERM");
		}
	}

	private static void getSolrPort() {
		solrPort = Integer.parseInt(getPreset("solr.port"));
	}

	private static void getSolrHost() {
		solrHost = getPreset("solr.host");
	}

	private static void printWelcomeMessage() throws Exception 
	{
		print ("");
		print ("PDS Registry");
		print ("  Version:     " + registry_version);
		print ("  Platform:    " + osName);		
		InetAddress inetAddress = InetAddress.getLocalHost();
        print ("  IP Address:  " + inetAddress.getHostAddress());
        print ("  Host Name:   " + inetAddress.getHostName());
        print ("");
	}

	
	private static void copyConfigSet(String name) throws IOException
	{
        String solrConfigsets = solrRoot + SEP + "server" + SEP + "solr" + SEP + "configsets";

        String fromDir = registry_root + SEP + "collections" + SEP + name;
        String toDir = solrConfigsets + SEP + name + SEP + "conf";
        
        copyDir(fromDir, toDir);
	}
	
	
	private static void setupSOLRDirs() throws Exception 
	{
		// Copy PDS plugins
		String toDir  = solrRoot + SEP + "contrib" + SEP + "pds" + SEP + "lib";
    	copyDir(registry_root + SEP + "dist", toDir);

		// Copy config sets
		copyConfigSet("registry");
		copyConfigSet("data");
	}

	
	private static void startSOLRServer() {		
        Process progProcess = null;
        int returnVal = -1;
		try {
			// need to check wheather the SOLR server is running already or not
			// ./solr status       -> "No Solr nodes are running."
			String[] statusCmd = new String[] 
			{
				solrBin + SEP + solrCmd, 
				"status", 
				"-p", String.valueOf(solrPort)
			};
		
			progProcess = Runtime.getRuntime().exec(statusCmd);
			BufferedReader in = new BufferedReader(
                                new InputStreamReader(progProcess.getInputStream()));
            String line = null;
            boolean runningProc = false;
            while ((line = in.readLine()) != null) {
                //System.out.println(line);
                if (line.contains("running on port " + solrPort)) {
                	runningProc = true;
                	System.out.println(line);
                }
            }

			try{
               	returnVal = progProcess.waitFor();

               	if (runningProc) {
               		print("Failed to start the SOLR server. There is already RUNNING SOLR instance.");
               		exit(1);
           		}
            } catch(Exception ex){
               logger.error("Error during SOLR server startup", ex);
            }
            in.close();
            print("\nStarting a SOLR server... Waiting up to 180 seconds to see Solr running on port " + solrPort + "...");

			String[] execCmd = new String[] 
			{ 
				solrBin + SEP + solrCmd, 
				"start", "-c",
		        "-p", String.valueOf(solrPort), 
		        "-s", solrRoot + SEP + "server" + SEP + "solr"};

			progProcess = Runtime.getRuntime().exec(execCmd);		
			in = new BufferedReader(new InputStreamReader(progProcess.getInputStream()));
            
            // SOLR starup hangs for some reason
            // Sleep for x seconds instead of calling waitFor() on Windows platform
            if (osName.contains("Windows")) {
            	Thread.sleep(Integer.parseInt(getPreset("waitTime"))*1000);
            	print("SOLR server is started on " + osName);
            }
            else { 
            	while ((line = in.readLine()) != null) {
                	System.out.println(line);
            	}
            	try{
               		returnVal = progProcess.waitFor();
               		print("Return status from the SOLR server startup = " + returnVal);
               		if (returnVal!=0) {
                  		print("Failed to start the SOLR server....");
                  		exit(1);
               		}
               		else 
               			print("The SOLR server is started successfully.");
            	} catch(InterruptedException ie){
               		logger.error("Installation interrupted", ie);
            	} 
            }       
           	in.close();
		} catch (Exception err) {
			logger.error("Error during SOLR server startup", err);
		} finally {
			RegistryInstallerUtils.safeClose(progProcess);
        }
	}

	private static void stopSOLRServer() {		
        Process progProcess = null;
        int returnVal = -1;
		try {
			// need to check wheather the SOLR server is running already or not
			// ./solr status       -> "No Solr nodes are running."
			progProcess = Runtime.getRuntime().exec(new String[] { solrBin + SEP + solrCmd, "status", "-p", String.valueOf(solrPort)});
			BufferedReader in = new BufferedReader(
                                new InputStreamReader(progProcess.getInputStream()));
            String line = null;
            boolean runningProc = false;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                if (line.contains("running on port")) {
                	runningProc = true;
                }
            }
			try{
               	returnVal = progProcess.waitFor();
          		if (!runningProc) {
               		print("There is no RUNNING SOLR instance. No need to stop the SOLR server.");
               		exit(1);
           		}
            } catch(Exception ex){
               logger.error("Error during SOLR server status check", ex);
            }
            in.close();

            print("\nStopping the SOLR server...");

            //./bin/solr stop
			String[] execCmd = new String[] { solrBin + SEP + solrCmd, "stop", "-p", String.valueOf(solrPort)};

			progProcess = Runtime.getRuntime().exec(execCmd);		
			in = new BufferedReader(new InputStreamReader(progProcess.getInputStream()));
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            try{
               	returnVal = progProcess.waitFor();
               	if (returnVal!=0) {
                  	print("Failed to stop the SOLR server....");
                  	exit(1);
               	}
               	else {
               		print("The SOLR server is stopped successfully.");
               	}
            } catch(InterruptedException ie){
               	logger.error("Installation interrupted", ie);
            }    
           	in.close();
		} catch (Exception err) {
			logger.error("Error during SOLR server stop", err);
		} finally {
			RegistryInstallerUtils.safeClose(progProcess);
        }
	}

	
	private static void createCollection(String name)
	{
		// Create collection
		String[] execCmd = new String[] 
		{
			solrBin + SEP + solrCmd, 
			"create", 
			"-p", String.valueOf(solrPort), 
			"-c", name, "-d", name,
			"-shards", numShards,
			"-replicationFactor", replicationFactor
		};
				
		execCreateCommand(execCmd, name + " collection");
	}
	
	
	private static void execCreateCommand(String[] cmd, String name)
	{
		Process proc = null;

		print("Creating " + name + "...");
		
        try 
        {
			proc = Runtime.getRuntime().exec(cmd);
			
			BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			String line = null;
	        while ((line = in.readLine()) != null) 
	        {
	            System.out.println(line);
	        }
        
        	int returnVal = proc.waitFor();
            print("Return status from creating " + name + " = " + returnVal);
            if(returnVal != 0) 
            {
                print("Failed to create " + name);
                exit(1);
            }
            else 
            {
            	// Capitalize
            	name = name.substring(0, 1).toUpperCase() + name.substring(1);
            	print(name + " is created successfully.");
            }
            
            RegistryInstallerUtils.safeClose(proc);
        } 
        catch(Exception ex)
        {
        	logger.error("Error during " + name + " creation", ex);
        	exit(1);
        }
	}

	
	private static void execDeleteCommand(String[] cmd, String name)
	{
		Process proc = null;

		print("Deleting " + name + "...");
		
        try 
        {
			proc = Runtime.getRuntime().exec(cmd);
			
			BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			String line = null;
	        while ((line = in.readLine()) != null) 
	        {
	            System.out.println(line);
	        }
        
        	int returnVal = proc.waitFor();
            print("Return status from deleting " + name + " = " + returnVal);
            if(returnVal != 0) 
            {
                print("Failed to delete " + name);
            }
            else 
            {
            	// Capitalize
            	name = name.substring(0, 1).toUpperCase() + name.substring(1);
            	print(name + " is deleted successfully.");
            }
        } 
        catch(Exception ex)
        {
        	logger.error("Error during " + name + " deletion", ex);
        }
        finally
        {
        	RegistryInstallerUtils.safeClose(proc);
        }
	}

		
	private static void deleteCollection(String name)
	{
        // Registry collection
		String[] execCmd = new String[]
        { 
        	solrBin + SEP + solrCmd,
        	"delete", 
        	"-p", String.valueOf(solrPort), 
        	"-c", name
        };

        execDeleteCommand(execCmd, name + " collection");
	}
	
}
