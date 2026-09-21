package pt.porto.bus.model;

import java.util.List;

/** Which lines serve one stop: an element of `/stops/lines?bbox=`. */
public record StopLines(String stopCode, List<LineBadge> lines) {}
