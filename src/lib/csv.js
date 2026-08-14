/**
 * Tiny RFC-4180 CSV parser, good enough for GTFS text files.
 * Handles double-quoted fields, escaped quotes ("") and commas inside quotes.
 * Returns an array of row objects keyed by the header row.
 *
 * @param {string} text
 * @returns {Record<string, string>[]}
 */
export function parseCsv(text) {
  const rows = parseRows(text);
  if (rows.length === 0) return [];
  const header = rows[0].map((h) => h.replace(/^\uFEFF/, '').trim()); // strip BOM
  const out = [];
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    if (row.length === 1 && row[0] === '') continue; // skip blank lines
    /** @type {Record<string, string>} */
    const obj = {};
    for (let c = 0; c < header.length; c++) obj[header[c]] = row[c] ?? '';
    out.push(obj);
  }
  return out;
}

/**
 * @param {string} text
 * @returns {string[][]}
 */
function parseRows(text) {
  const rows = [];
  let field = '';
  let row = [];
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];

    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
      continue;
    }

    if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      row.push(field);
      field = '';
    } else if (ch === '\n') {
      row.push(field);
      rows.push(row);
      field = '';
      row = [];
    } else if (ch === '\r') {
      // ignore; \n handles the line break
    } else {
      field += ch;
    }
  }
  // last field / row (if file doesn't end with newline)
  if (field !== '' || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
