package gov.nasa.pds.search.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InstallerPresets extends Properties {

	private static final long serialVersionUID = 1L;
	private String presetsFilePath;
	private static final Logger logger = LoggerFactory.getLogger(InstallerPresets.class);

	public InstallerPresets() {
		super();
		InputStream propStream = null;
		try {
			presetsFilePath = System.getenv("REGISTRY_INSTALLER_PRESET_FILE");
			propStream = new FileInputStream(new File (presetsFilePath));
			this.load(propStream);
			//System.out.println(this.toString());
		} catch (Exception e) {
			logger.error("Error loading presets", e);
		} finally {
			try {
				if (propStream != null)
					propStream.close();
			} catch (IOException e) {
				logger.error("Error closing presets stream", e);
			}
		}
	}

	public InstallerPresets(Properties defaults) {
		super(defaults);
	}
	
	public String getPresetsFilePath() {
		return presetsFilePath;
	}
	
	public void writeOutToFile() {
		System.out.println("Writing configuration properties to: " + presetsFilePath);
		try {
			File file = new File(presetsFilePath);
			file.delete();
			file.createNewFile();
			FileOutputStream fileOut = new FileOutputStream(file);
			this.store(fileOut, "REGISTRY Configuration Properties (see: https://wiki.jpl.nasa.gov/display/search/Configuring+SEARCH)" + System.currentTimeMillis());
			fileOut.close();
			
			File nextVer = new File(presetsFilePath);
			String preContents = RegistryInstallerUtils.getFileContents(nextVer.toPath());
			String nextContents = preContents.replaceAll("\\\\", "");
			RegistryInstallerUtils.writeToFile(nextVer.toPath(), nextContents);
		}
		catch (Exception e) {
			logger.error("Error saving presets", e);
		}
	}
}
