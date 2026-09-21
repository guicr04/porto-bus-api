package pt.porto.bus.gtfs;

/** Which feed the store was built from: the single feed_meta row. */
public record FeedMeta(
    String resourceName,
    String sourceUrl,
    String feedVersion,
    String feedStartDate,
    String feedEndDate,
    String ingestedAt) {}
