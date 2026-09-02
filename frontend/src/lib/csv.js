// CSV parsing, deliberately generic: it returns rows of raw cells and knows nothing about students
// or subjects. Field mapping belongs to the caller.
//
// A single character scan rather than split("\n") then split(","), because the whole point is
// quoted cells: a subject name like "Design, Analysis of Algorithms" contains the column separator,
// and a line-splitting parser cannot recover from that — it silently shifts every later field into
// the wrong column and reports nothing. Own module (not a helper inside a tab):
// react-refresh/only-export-components bars a non-component export from a .jsx.

/**
 * Parse CSV text into rows of cells.
 *
 * Quoted cells (`"a,b"`) keep their commas and newlines; `""` inside them is a literal quote.
 * Unquoted cells are trimmed, quoted ones are preserved verbatim — a caller that quotes a value
 * is being explicit about its content. Rows are separated by LF or CRLF, and all-empty rows
 * (blank lines, a trailing newline) are dropped.
 *
 * Throws on an unclosed quote rather than swallowing the rest of the file into one cell. That
 * shape parses "successfully" into a single absurd row, which reads as a one-row import and is
 * exactly the silent misread this parser exists to prevent.
 *
 * @param {string} text
 * @returns {string[][]}
 */
export function parseCsv(text) {
  const src = text == null ? "" : String(text);
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;   // this cell was written in quotes, so don't trim it
  let inQuotes = false; // the scan is currently between quotes
  let i = 0;

  const endField = () => {
    row.push(quoted ? field : field.trim());
    field = "";
    quoted = false;
  };
  const endRow = () => {
    endField();
    rows.push(row);
    row = [];
  };

  while (i < src.length) {
    const ch = src[i];

    if (inQuotes) {
      if (ch === '"') {
        if (src[i + 1] === '"') {
          field += '"';
          i += 2;
        } else {
          inQuotes = false;
          i += 1;
        }
      } else {
        field += ch;
        i += 1;
      }
      continue;
    }

    // An opening quote only counts at the start of a cell, so a stray quote mid-value
    // (6" ruler) stays literal instead of hijacking the rest of the file.
    if (ch === '"' && field.trim() === "") {
      field = "";
      quoted = true;
      inQuotes = true;
      i += 1;
    } else if (ch === ",") {
      endField();
      i += 1;
    } else if (ch === "\r") {
      endRow();
      i += src[i + 1] === "\n" ? 2 : 1;
    } else if (ch === "\n") {
      endRow();
      i += 1;
    } else {
      field += ch;
      i += 1;
    }
  }

  if (inQuotes) {
    throw new Error("Unclosed quote in the CSV — check the quotation marks.");
  }
  // the last cell of a file that doesn't end in a newline
  if (field !== "" || quoted || row.length > 0) {
    endRow();
  }

  return rows.filter((cells) => cells.some((c) => c !== ""));
}

/**
 * Drop a leading header line, if present. Only row 0 is considered: filtering EVERY row whose first
 * cell looks like a header can silently discard a data row that happens to start with the same
 * text, which is a worse failure than leaving a stray header in (that row errors visibly).
 *
 * @param rows   output of parseCsv
 * @param names  accepted first-cell spellings, lowercase, spaces already removed
 */
export function dropHeaderRow(rows, names) {
  if (rows.length === 0) return rows;
  const first = (rows[0][0] || "").toLowerCase().replace(/\s+/g, "");
  return names.includes(first) ? rows.slice(1) : rows;
}
