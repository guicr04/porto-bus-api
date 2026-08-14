/**
 * Turn an address into coordinates, once, so you can paste them into .env.
 *
 *   node scripts/geocode.js "Rua de Santa Catarina 100, Porto"
 *   make geocode ADDRESS="Rua de Santa Catarina 100, Porto"
 *
 * Deliberately a setup script and not a runtime endpoint. The board is polled
 * every few seconds by a device on your desk; geocoding on each poll would send a
 * third party your address over and over for an answer that never changes, and
 * would breach Nominatim's usage policy (max ~1 request/second, and they ask that
 * you cache results). Resolve it once, store the numbers, stop calling out.
 */
import { config } from '../src/config.js';

const address = process.argv.slice(2).join(' ').trim();

if (!address) {
  console.error('Usage: node scripts/geocode.js "<address>"');
  process.exit(1);
}

const url =
  'https://nominatim.openstreetmap.org/search?format=json&limit=5&' +
  // Bias towards Porto: a bare street name matches half of Europe otherwise.
  `countrycodes=pt&q=${encodeURIComponent(address)}`;

const resp = await fetch(url, {
  headers: { 'User-Agent': config.userAgent, Accept: 'application/json' },
});

if (!resp.ok) {
  console.error(`Geocoder returned ${resp.status}`);
  process.exit(1);
}

const results = await resp.json();

if (!results.length) {
  console.error(`No match for "${address}". Try adding the city or postcode.`);
  process.exit(1);
}

console.log(`\nMatches for "${address}":\n`);
results.forEach((r, i) => {
  console.log(`  ${i + 1}. ${r.display_name}`);
  console.log(`     lat=${Number(r.lat).toFixed(6)}  lon=${Number(r.lon).toFixed(6)}\n`);
});

const best = results[0];
console.log('Add the one you want to .env, e.g.:\n');
console.log(`  HOME_LAT=${Number(best.lat).toFixed(6)}`);
console.log(`  HOME_LON=${Number(best.lon).toFixed(6)}`);
console.log('  HOME_LABEL=HOME\n');
console.log('Data © OpenStreetMap contributors (ODbL), via Nominatim.\n');
