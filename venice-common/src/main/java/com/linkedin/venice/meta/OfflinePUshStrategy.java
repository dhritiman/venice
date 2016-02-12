package com.linkedin.venice.meta;

/**
 * Enum of strategies used to decide the when the data is ready to serve in off-line push.
 */
public enum OfflinePUshStrategy {
    /*Wait all replica is ready, the version is ready to serve.*/
    WAIT_ALL_REPLICAS,
    /*Wait at least one replica in each parition is ready, the version is ready to server.*/
    WAIT_ONE_REPLICA_PER_PARTITION
}
