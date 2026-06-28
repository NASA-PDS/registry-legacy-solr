package gov.nasa.pds.citool.commandline.options;

import java.io.File;
import java.util.List;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileHandler;

public class ToolsPropertiesConfiguration extends PropertiesConfiguration {
    public ToolsPropertiesConfiguration(File file) throws ConfigurationException {
        super();
        setListDelimiterHandler(new DefaultListDelimiterHandler(','));
        new FileHandler(this).load(file);
    }

    public boolean containsKey(ConfigKey configKey) {
        return containsKey(configKey.getKey());
    }

    public List<String> getList(ConfigKey configKey) {
      List<?> list = getList(configKey.getKey());
      return (List<String>) list;
    }

    public int getInt(ConfigKey configKey) {
        return getInt(configKey.getKey());
    }

    public String getString(ConfigKey configKey) {
        return getString(configKey.getKey());
    }

    public Boolean getBoolean(ConfigKey configKey) {
        return getBoolean(configKey.getKey());
    }

}
