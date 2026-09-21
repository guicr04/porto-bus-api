package pt.porto.bus.model;

/** A stop from the static store. `stop_code` is the key end to end (README §2a). */
public record Stop(String stopCode, String name, Double lat, Double lon) {}
