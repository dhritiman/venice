package com.linkedin.venice.config;

import com.linkedin.venice.exceptions.ConfigurationException;
import com.linkedin.venice.server.VeniceConfigService;
import com.linkedin.venice.store.bdb.BdbServerConfig;
import com.linkedin.venice.utils.Props;


/**
 * class that maintains config very specific to a Venice server
 */
public class VeniceServerConfig extends VeniceClusterConfig {

  private int nodeId;
  protected BdbServerConfig bdbServerConfig;

  private static final String VENICE_NODE_ID_VAR_NAME = "VENICE_NODE_ID";

  public VeniceServerConfig(Props serverProperties) throws ConfigurationException {
    super(serverProperties);
    verifyProperties(serverProperties);
  }

  private void verifyProperties(Props serverProps) {
    if (serverProps.containsKey(VeniceConfigService.NODE_ID)) {
      nodeId = serverProps.getInt(VeniceConfigService.NODE_ID);
    } else {
      nodeId = getIntEnvVariable(VENICE_NODE_ID_VAR_NAME);
    }
    dataBasePath = serverProps.getString(VeniceConfigService.DATA_BASE_PATH);

    /* TODO: this is basically bdb environment settings. We can make it tunable for each environment.
     * In current implementation, all environments share same settings. Need further discussion on this.
     */
    bdbServerConfig = new BdbServerConfig(serverProps);
  }

  /**
   * Get config from Environment
   *
   * @param name
   * @return
   */
  private int getIntEnvVariable(String name) {
    String var = System.getenv(name);
    if (var == null) {
      throw new ConfigurationException("The environment variable " + name + " is not defined.");
    }
    try {
      return Integer.parseInt(var);
    } catch (NumberFormatException e) {
      throw new ConfigurationException("Invalid format for environment variable " + name + ", expecting an integer.",
        e);
    }
  }

  public int getNodeId() {
    return nodeId;
  }

  /**
   * TODO create a ServerStorageConfig abstract class and extend BdbServerConfig from that
   *
   * @return class object of ServerStorageConfig makes more sense...
   */
  public BdbServerConfig getBdbServerConfig() {
    return this.bdbServerConfig;
  }


  /**
   * Get base path of Venice storage data.
   *
   * @return Base path of persisted Venice database files.
   */
  public String getDataBasePath() {
    return this.dataBasePath;
  }
}
