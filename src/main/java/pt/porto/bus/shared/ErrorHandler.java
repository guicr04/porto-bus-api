package pt.porto.bus.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pt.porto.bus.model.ErrorBody;

/**
 * Every error leaves as {"detail": "..."}.
 *
 * <p>An error that knows its status keeps it. Anything else is an unclassified
 * upstream failure — and upstream is either stcp.pt or the GTFS portal, so the
 * message stays generic; naming the wrong one sends you debugging in the wrong
 * direction.
 */
@RestControllerAdvice
public class ErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> api(ApiException e) {
    return body(e.status(), e.getMessage());
  }

  @ExceptionHandler(UpstreamException.class)
  ResponseEntity<ErrorBody> upstream(UpstreamException e) {
    Integer status = e.status();
    if (status != null && status >= 400 && status < 600) {
      log.warn("upstream {}: {}", status, e.getMessage());
      return body(status, e.getMessage());
    }
    log.error("upstream request failed", e);
    return body(502, "Upstream request failed: " + e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> other(Exception e) {
    // Spring's own client errors (unknown path, wrong method, missing param)
    // keep their status; only genuine failures become a 502.
    if (e instanceof ErrorResponse er) {
      int status = er.getStatusCode().value();
      String detail = er.getBody().getDetail();
      if (status == 404) detail = "Not found";
      return body(status, detail != null ? detail : HttpStatus.valueOf(status).getReasonPhrase());
    }
    log.error("request failed", e);
    return body(502, "Upstream request failed: " + e.getMessage());
  }

  private static ResponseEntity<ErrorBody> body(int status, String detail) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(detail));
  }
}
