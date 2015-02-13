package com.linkedin.venice.store;

import com.linkedin.venice.VeniceException;

/**
 * This exception indicates that the server was unable to initialize on or more
 * storage services or stores within a service.
 */
public class StorageEngineInitializationException extends VeniceException {

  private final static long serialVersionUID = 1;

  public StorageEngineInitializationException() {
  }

  public StorageEngineInitializationException(String message) {
    super(message);
  }

  public StorageEngineInitializationException(Throwable t) {
    super(t);
  }

  public StorageEngineInitializationException(String message, Throwable t) {
    super(message, t);
  }

}
