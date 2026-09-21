package pt.porto.bus.shared;

/**
 * An error that already knows its HTTP status: "this stop does not exist" is a
 * 404 and "STCP is down and the store can't stand in" is a 503. Flattening
 * either to 502 tells the client to retry something that will never succeed.
 */
public class ApiException extends RuntimeException {
  private final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, message);
  }

  public static ApiException badRequest(String message) {
    return new ApiException(400, message);
  }
}
