package pt.porto.bus.shared;

/**
 * A failed call to stcp.pt or the GTFS portal.
 *
 * <p>The status travels with the error rather than living only in the message:
 * the fallback has to tell "STCP is unwell" (fall back to the store) from "this
 * stop does not exist" (propagate — never fabricate a board for it). A null
 * status means the request never got an HTTP answer: timeout, connection
 * refused, a body that wasn't JSON.
 */
public class UpstreamException extends RuntimeException {
  private final Integer status;

  public UpstreamException(Integer status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public UpstreamException(Integer status, String message) {
    this(status, message, null);
  }

  public Integer status() {
    return status;
  }

  /**
   * Should this failure fall back to the timetable?
   *
   * <p>A 404 means the thing genuinely does not exist upstream, and answering it
   * with a fabricated board would be worse than the error. Timeouts, connection
   * failures, 5xx and rate limiting mean "STCP is unwell", which is exactly what
   * the store is for. Anything that is not an HTTP answer at all counts as unwell.
   */
  public static boolean isOutage(Throwable err) {
    Integer status =
        switch (err) {
          case UpstreamException u -> u.status();
          case ApiException a -> a.status();
          default -> null;
        };
    if (status == null) return true;
    if (status == 404 || status == 400) return false;
    return status >= 500 || status == 429 || status == 408;
  }
}
