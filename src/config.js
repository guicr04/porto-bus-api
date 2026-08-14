import 'dotenv/config';

export const config = {
  port: Number(process.env.PORT ?? 8000),

  // Official static GTFS feed (Porto open data).
  //
  // The portal republishes the feed every few days as a *brand new resource*
  // with a fresh UUID, so a pinned download URL always 404s eventually. We
  // therefore resolve the newest resource at load time via the CKAN API, and
  // only fall back to GTFS_URL if it's explicitly set (handy for pinning a
  // known-good feed or pointing at a local copy).
  gtfsUrl: process.env.GTFS_URL || null,
  gtfsPortalBase: (process.env.GTFS_PORTAL_BASE ?? 'https://opendata.porto.digital').replace(/\/$/, ''),
  gtfsDatasetId: process.env.GTFS_DATASET_ID ?? 'horarios-paragens-e-rotas-em-formato-gtfs-stcp',
  gtfsTtlSeconds: Number(process.env.GTFS_TTL_SECONDS ?? 60 * 60 * 24 * 7), // one week

  // Modern stcp.pt public JSON API (what the website itself calls)
  stcpApiBase: (process.env.STCP_API_BASE ?? 'https://stcp.pt/api').replace(/\/$/, ''),

  // Default origin for the departure board — the address the display sits at.
  // Coordinates rather than an address on purpose: the board is polled every few
  // seconds, and geocoding on every poll would hammer a third-party geocoder for
  // an answer that never changes. Resolve the address once (`make geocode`) and
  // paste the result here.
  homeLat: process.env.HOME_LAT ? Number(process.env.HOME_LAT) : null,
  homeLon: process.env.HOME_LON ? Number(process.env.HOME_LON) : null,
  homeLabel: process.env.HOME_LABEL ?? 'HOME',

  // How long live arrivals are cached. Keeps an always-on display from hammering
  // stcp.pt; set to 0 to disable.
  realtimeTtlMs: Number(process.env.REALTIME_TTL_MS ?? 15000),

  userAgent: process.env.USER_AGENT ?? 'porto-bus-api/0.1 (personal project)',
  httpTimeoutMs: Number(process.env.HTTP_TIMEOUT_MS ?? 10000),
};
