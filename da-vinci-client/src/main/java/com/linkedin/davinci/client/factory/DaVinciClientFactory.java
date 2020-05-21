package com.linkedin.davinci.client.factory;

import com.linkedin.davinci.client.DaVinciClient;
import com.linkedin.davinci.client.DaVinciConfig;
import org.apache.avro.specific.SpecificRecord;


public interface DaVinciClientFactory extends AutoCloseable {
  <K, V> DaVinciClient<K, V> getGenericAvroClient(String storeName, DaVinciConfig config);
  <K, V extends SpecificRecord> DaVinciClient<K, V> getSpecificAvroClient(String storeName, DaVinciConfig config, Class<V> valueClass);
}

