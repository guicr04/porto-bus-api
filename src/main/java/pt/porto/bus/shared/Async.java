package pt.porto.bus.shared;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Waiting on fanned-out calls without burying the real exception. */
public final class Async {
  private Async() {}

  public static <T> T join(CompletableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (ExecutionException | CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException re) throw re;
      if (cause instanceof Error err) throw err;
      throw new IllegalStateException(cause);
    }
  }
}
