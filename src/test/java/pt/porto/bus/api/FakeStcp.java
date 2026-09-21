package pt.porto.bus.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** stcp.pt, played by a local server: canned responses by path, and a log of what was asked. */
final class FakeStcp implements AutoCloseable {

  record Response(int status, String body) {}

  private final HttpServer server;
  private final Map<String, Response> routes = new ConcurrentHashMap<>();
  final List<String> requests = new CopyOnWriteArrayList<>();

  FakeStcp() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getRawPath();
          String query = exchange.getRequestURI().getRawQuery();
          requests.add(query == null ? path : path + "?" + query);
          Response r = routes.getOrDefault(path, new Response(404, "{\"error\":\"not found\"}"));
          byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(r.status(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
  }

  void on(String path, int status, String body) {
    routes.put("/api" + path, new Response(status, body.replace('\'', '"')));
  }

  void reset() {
    routes.clear();
    requests.clear();
  }

  long hits(String pathPrefix) {
    return requests.stream().filter(r -> r.startsWith("/api" + pathPrefix)).count();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
