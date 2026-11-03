package gov.nasa.pds.harvest.search.context;

/**
 * Thrown when the registered context products cannot be fetched, read or written.
 */
public class ContextProductsException extends Exception {
  private static final long serialVersionUID = 1L;

  public ContextProductsException(String message) {
    super(message);
  }

  public ContextProductsException(String message, Throwable cause) {
    super(message, cause);
  }
}
