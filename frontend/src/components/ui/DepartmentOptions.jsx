// The id-keyed <option> list for a department picker. Options ONLY — no <select>, no placeholder:
// placeholder text differs per site and `departments-fetch-failure.cy.js` counts it. Returns a bare
// array so options stay DIRECT children of the <select>; a wrapper there is invalid HTML and breaks
// that count.
//
// BulkProgressionTab deliberately does NOT use this — it keys by `d.code`, since bulk progression
// filters on the branch code (`lower(branch)`, a functional index). Swapping it in would send an
// id where a code is expected, and the preview would look legitimately empty.
function DepartmentOptions({ departments }) {
  return departments.map((d) => (
    <option key={d.id} value={String(d.id)}>
      {d.deptName}
    </option>
  ));
}

export default DepartmentOptions;
