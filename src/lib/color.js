/**
 * GTFS gives route_color as bare hex ("187EC2"); every other colour field in
 * this API (the live feed's route_color, /stops/:code/routes) comes back
 * already `#`-prefixed. Normalise so callers never have to special-case which
 * endpoint they got a colour from.
 *
 * Its own module because both the ingest and the GTFS client need it, and
 * neither should have to import the other.
 *
 * @param {string} v
 * @returns {string | null}
 */
export function toHexColor(v) {
  const trimmed = (v || '').trim();
  return trimmed ? `#${trimmed.replace(/^#/, '')}` : null;
}
