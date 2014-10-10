package com.linkedin.venice.client;

import com.linkedin.venice.config.GlobalConfiguration;
import com.linkedin.venice.kafka.producer.KafkaProducer;
import com.linkedin.venice.message.OperationType;
import com.linkedin.venice.message.VeniceMessage;
import org.apache.log4j.Logger;

/**
 * Class which acts as the primary client API
 */
public class VeniceClient {

  // log4j logger
  static final Logger logger = Logger.getLogger(VeniceClient.class.getName());

  private static KafkaProducer kp;

  private VeniceMessage msg;

  public VeniceClient() {

    // TODO: Deprecate/refactor the config. It's really not needed for the most part
    try {
      GlobalConfiguration.initializeFromFile("./config/config.properties");
    } catch (Exception e) {
      logger.error("Error while starting up configuration for Venice Client.");
      logger.error(e);
      System.exit(1);
    }

    kp = new KafkaProducer();

  }

  /**
   * Execute a standard "get" on the key. Returns null if empty.
   * @param key - The key to look for in storage.
   * @return The result of the "Get" operation
   * */
  public Object get(String key) {
    throw new UnsupportedOperationException("Cross communication between Client and Server is not ready.");
  }

  /**
   * Execute a standard "delete" on the key.
   * @param key - The key to delete in storage.
   * */
  public void delete(String key) {

    msg = new VeniceMessage(OperationType.DELETE, "");
    kp.sendMessage(key, msg);

  }

  /**
   * Execute a standard "put" on the key.
   * @param key - The key to put in storage.
   * @param value - The value to be associated with the given key
   * */
  public void put(String key, Object value) {

    msg = new VeniceMessage(OperationType.PUT, value.toString());
    kp.sendMessage(key, msg);

  }

}
