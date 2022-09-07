package com.linkedin.venice.integration.utils;

import static com.linkedin.venice.ConfigKeys.*;

import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.VeniceProperties;


/**
 * Utility class to help with integration tests.
 *
 * N.B.: The visibility of this class and its functions is package-private on purpose.
 */
public class IntegrationTestUtils {
  static final int MAX_ASYNC_START_WAIT_TIME_MS = 30 * Time.MS_PER_SECOND;

  /**
   * N.B.: Visibility is package-private on purpose.
   */
  static VeniceProperties getClusterProps(
      String clusterName,
      String zkAddress,
      KafkaBrokerWrapper kafkaBrokerWrapper,
      boolean sslToKafka) {
    // TODO: Validate that these configs are all still used.
    // TODO: Centralize default config values in a single place

    VeniceProperties clusterProperties = new PropertyBuilder()

        // Helix-related config
        .put(ZOOKEEPER_ADDRESS, zkAddress)

        // Kafka-related config
        .put(KAFKA_BOOTSTRAP_SERVERS, sslToKafka ? kafkaBrokerWrapper.getSSLAddress() : kafkaBrokerWrapper.getAddress())
        .put(KAFKA_ZK_ADDRESS, kafkaBrokerWrapper.getZkAddress())
        .put(KAFKA_LINGER_MS, 0)

        // Other configs
        .put(CLUSTER_NAME, clusterName)
        .put(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.toString())
        .put(CONTROLLER_ADD_VERSION_VIA_ADMIN_PROTOCOL, false)
        .build();

    return clusterProperties;
  }
}