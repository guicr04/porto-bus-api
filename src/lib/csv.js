/**
 * Tiny RFC-4180 CSV parser, good enough for GTFS text files.
 * Handles double-quoted fields, escaped quotes ("") and commas inside quotes.
 * Returns an array of row objects keyed by the header row.
 *
 * @param {string} text
 * @returns {Record<string, string>[]}
 */
export function parseCsv(text) {
  return [...iterCsv(text)];
}

/**
 * The same parse, yielded one row at a time.
 *
 * This exists for `stop_times.txt`: 850k rows and 50 MB of text, where
 * materialising the whole `string[][]` plus an object per row costs over a
 * gigabyte for no reason — the ingest consumes each row once and drops it.
 * Everything smaller can keep using `parseCsv`.
 *
 * @param {string} text
 * @returns {Generator<Record<string, string>>}
 */
export function* iterCsv(text) {
  /** @type {string[] | null} */
  let header = null;
  for (const row of iterRows(text)) {
    if (header === null) {
      header = row.map((h) => h.replace(/^\uFEFF/, '').trim()); // strip BOM
      continue;
    }
    if (row.length === 1 && row[0] === '') continue; // skip blank lines
    /** @type {Record<string, string>} */
    const obj = {};
    for (let c = 0; c < header.length; c++) obj[header[c]] = row[c] ?? '';
    yield obj;
  }
}

/**
 * Row tokenizer. Unquoted fields — which is almost every field in GTFS — are
 * taken with one `slice` rather than accumulated a character at a time; over
 * 50 MB that difference is minutes, not milliseconds. Quoted fields fall back
 * to character accumulation, since escaped quotes have to be unescaped.
 *
 * @param {string} text
 * @returns {Generator<string[]>}
 */
function* iterRows(text) {
  const len = text.length;
  let i = 0;
  /** @type {string[]} */
  let row = [];

  while (i < len) {
    let field;

    if (text[i] === '"') {
      i++; // opening quote
      field = '';
      while (i < len) {
        const ch = text[i];
        if (ch === '"') {
          if (text[i + 1] === '"') {
            field += '"';
            i += 2;
            continue;
          }
          i++; // closing quote
          break;
        }
        field += ch;
        i++;
      }
    } else {
      const start = i;
      while (i < len) {
        const ch = text[i];
        if (ch === ',' || ch === '\n' || ch === '\r') break;
        i++;
      }
      field = text.slice(start, i);
    }

    row.push(field);

    // Whatever ended the field decides whether the row continues.
    if (i < len && text[i] === ',') {
      i++;
      continue;
    }
    while (i < len && text[i] === '\r') i++;
    if (i < len && text[i] === '\n') i++;
    yield row;
    row = [];
  }

  if (row.length > 0) yield row;
}
