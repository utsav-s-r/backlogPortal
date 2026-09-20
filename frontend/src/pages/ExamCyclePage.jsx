import { Fragment, useState, useEffect } from "react";
import { CalendarRange, Check, CheckCircle2, CircleSlash, LoaderCircle, Pencil, PlusCircle, Trash2, X } from "lucide-react";
import AdminPageShell from "../components/layout/AdminPageShell";
import PrimaryCta from "../components/ui/PrimaryCta";
import api from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import AlertBanner from "../components/AlertBanner";
import { ADMIN_ONLY } from "../lib/roles";
import { useRoleGuard } from "../hooks/useRoleGuard";
import { FIELD_CONTROL, FIELD_INPUT, FIELD_LABEL } from "../lib/formClasses";
import { EXAM_MONTHS, formatExamMonth, isCanonicalExamMonth, toExamMonthValue } from "../lib/examMonth";
import Field from "../components/ui/Field";
import { btn } from "../lib/buttonClasses";

// Mirrors ExamCycleRequest.MAX_BATCH_LINES. The form is ONE page: more lines than this push the
// student's details off it. The server refuses them too — this only stops the admin typing a
// thirteenth and losing the save.
const MAX_BATCH_LINES = 12;

function ExamCyclePage() {
  // ADMIN only: this page is entirely create/activate/deactivate, and those are the college-wide
  // registration switch — restricted server-side in ExamCycleController. Any other role would see
  // buttons that 403. (Reading the cycle LIST stays open to every role; that happens on the
  // dashboard filter, not here.)
  const allowed = useRoleGuard(ADMIN_ONLY);

  const [cycles, setCycles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState("");
  // The month is stored as "YYYY-MM" — two controls, never a free-text box, and never
  // <input type="month">: desktop Safari has no month picker and silently renders a text
  // input, which is the free-text box back again on one browser.
  const [examMonth, setExamMonth] = useState("");
  const [examYear, setExamYear] = useState("");
  const [creating, setCreating] = useState(false);
  const [activatingId, setActivatingId] = useState(null);
  const [endingId, setEndingId] = useState(null);
  // The row being corrected, and its draft. One at a time: the table holds a handful of rows and
  // a per-row component would need the same reload plumbing the page already owns.
  const [editingId, setEditingId] = useState(null);
  const [editName, setEditName] = useState("");
  const [editMonth, setEditMonth] = useState("");
  const [editYear, setEditYear] = useState("");
  const [savingId, setSavingId] = useState(null);
  // The batch list printed on this cycle's forms, edited as one block with the rest of the cycle:
  // one save, and a legacy cycle's month gets corrected in the same action rather than 400ing.
  const [editLines, setEditLines] = useState([]);
  const [error, setError] = useState("");

  const loadCycles = () => {
    setLoading(true);
    api
      .get("/admin/exam-cycles")
      .then((res) => {
        setCycles(res.data);
        setLoading(false);
      })
      // no .finally: on a 401 the spinner must stay up until the redirect lands
      .catch((err) => {
        console.error("Failed to load exam cycles", err);
        if (reportLoadError(err, setError, "Could not load exam cycles.")) setLoading(false);
      });
  };

  useEffect(() => {
    if (!allowed) return;
    // NOTE: react-hooks/set-state-in-effect flags this (loadCycles setStates internally).
    // Intended and correct — fetch-on-mount into an external system, state lands in the async
    // .then/.finally. A knowing lint error, deliberately not disabled.
    loadCycles();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCreate = async (e) => {
    e.preventDefault();
    setError("");
    if (!name.trim()) {
      setError("Cycle name is required.");
      return;
    }
    // Client-side copy of ExamCycleRequest's @Pattern. Convenience, not the control — the server
    // refuses the same value, which is what an API caller hits.
    const monthValue = toExamMonthValue(examYear, examMonth);
    if (!isCanonicalExamMonth(monthValue)) {
      setError("Pick the exam month and enter its four-digit year.");
      return;
    }
    setCreating(true);
    try {
      await api.post(
        "/admin/exam-cycles",
        { name: name.trim(), examMonthYear: monthValue },
      );
      setName("");
      setExamMonth("");
      setExamYear("");
      loadCycles();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to create exam cycle.");
    } finally {
      setCreating(false);
    }
  };

  const handleActivate = async (id) => {
    setActivatingId(id);
    setError("");
    try {
      await api.put(`/admin/exam-cycles/${id}/activate`, {});
      loadCycles();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to activate exam cycle.");
    } finally {
      setActivatingId(null);
    }
  };

  const handleEnd = async (id) => {
    setEndingId(id);
    setError("");
    try {
      await api.put(`/admin/exam-cycles/${id}/deactivate`, {});
      loadCycles();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to end exam cycle.");
    } finally {
      setEndingId(null);
    }
  };

  const startEdit = (cycle) => {
    setError("");
    setEditingId(cycle.id);
    setEditName(cycle.name || "");
    // Only a canonical value can populate the two controls; legacy free text cannot be split into
    // a month and a year, so the admin picks both afresh — which is the whole point of the edit.
    const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(String(cycle.examMonthYear || "").trim());
    setEditYear(match ? match[1] : "");
    setEditMonth(match ? match[2] : "");
    // A copy: editing must not mutate the loaded row, which Cancel falls back to.
    setEditLines((cycle.batchLines || []).map((l) => ({ label: l.label, batch: l.batch })));
  };

  const updateLine = (index, field, value) =>
    setEditLines((prev) => prev.map((l, i) => (i === index ? { ...l, [field]: value } : l)));
  const addLine = () => setEditLines((prev) => [...prev, { label: "", batch: "" }]);
  const removeLine = (index) => setEditLines((prev) => prev.filter((_, i) => i !== index));

  const handleSaveEdit = async (id) => {
    if (!editName.trim()) {
      setError("Cycle name is required.");
      return;
    }
    const monthValue = toExamMonthValue(editYear, editMonth);
    if (!isCanonicalExamMonth(monthValue)) {
      setError("Pick the exam month and enter its four-digit year.");
      return;
    }
    // A wholly empty line is a row the admin added and left alone — dropped rather than refused.
    // A HALF-filled one is a mistake, and prints as "B.E. I to VII Semester ( Batch Students)".
    const lines = editLines
      .map((l) => ({ label: l.label.trim(), batch: l.batch.trim() }))
      .filter((l) => l.label || l.batch);
    if (lines.some((l) => !l.label || !l.batch)) {
      setError("Every batch line needs both its programme text and its batch.");
      return;
    }
    setSavingId(id);
    setError("");
    try {
      await api.put(
        `/admin/exam-cycles/${id}`,
        { name: editName.trim(), examMonthYear: monthValue, batchLines: lines },
      );
      setEditingId(null);
      loadCycles();
    } catch (err) {
      // A 409 here is the server refusing a cycle that gained a registration since the list
      // loaded. Stay in the form with the message — the edit is not retryable, but retreating to
      // the read-only row would lose the typing and explain nothing.
      setError(err.response?.data?.message || "Failed to update exam cycle.");
    } finally {
      setSavingId(null);
    }
  };

  if (!allowed) return null;

  return (
    <AdminPageShell containerClassName="max-w-4xl">
      <h1 className="text-2xl font-semibold text-secondary-ink">Exam cycles</h1>
      <p className="mb-6 mt-1 text-sm text-ink-muted">
        One cycle at a time is active, and that is the college-wide switch for student registration.
        Admin only.
      </p>

      {error && (
        <AlertBanner tone="error" className="mb-4">
          {error}
        </AlertBanner>
      )}

      <section className="mb-6 py-5">
        <h3 className="mb-4 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <PlusCircle size={18} /> New Exam Cycle
        </h3>
        <form onSubmit={handleCreate} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Cycle Name *" htmlFor="cycle-name">
            <input
              id="cycle-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. June 2026 Backlog Exams"
              className={FIELD_CONTROL}
            />
          </Field>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Exam Month *" htmlFor="cycle-month">
              <select
                id="cycle-month"
                value={examMonth}
                onChange={(e) => setExamMonth(e.target.value)}
                className={FIELD_INPUT}
              >
                <option value="">Select month</option>
                {EXAM_MONTHS.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Exam Year *" htmlFor="cycle-year">
              <input
                id="cycle-year"
                type="number"
                inputMode="numeric"
                value={examYear}
                onChange={(e) => setExamYear(e.target.value)}
                placeholder="e.g. 2026"
                className={FIELD_INPUT}
              />
            </Field>
          </div>
          <p className="text-xs text-ink-muted sm:col-span-2" data-cy="cycle-inherit-note">
            The new cycle starts with the most recent cycle's batch list. Edit it on the row below
            once created.
          </p>
          <div className="sm:col-span-2">
            <PrimaryCta type="submit" disabled={creating} className="gap-2">
              {creating ? <LoaderCircle size={16} className="animate-spin" /> : <PlusCircle size={16} />}
              {creating ? "Creating..." : "Create Cycle"}
            </PrimaryCta>
          </div>
        </form>
      </section>

      <section className="py-5">
        <h3 className="mb-4 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <CalendarRange size={18} /> Exam Cycles
        </h3>
        {/* No active cycle is the college-wide "registration is closed" state. It was only
            inferable before, from the absence of an Active pill somewhere down the list. */}
        {!loading && cycles.length > 0 && !cycles.some((c) => c.active) && (
          <AlertBanner tone="warning" role="status" className="mb-4" data-cy="cycles-none-active">
            No cycle is active, so <strong className="font-semibold">registration is closed</strong>.
            Activating a cycle opens it college-wide.
          </AlertBanner>
        )}
        {loading ? (
          <p className="inline-flex items-center gap-2 text-sm">
            <LoaderCircle size={16} className="animate-spin" /> Loading...
          </p>
        ) : cycles.length === 0 ? (
          <p className="text-sm text-ink-muted">
            No exam cycles yet. Create one above — registrations stay closed until a cycle is active.
          </p>
        ) : (
          // A table, not stacked rows: four facts per cycle line up as columns, and the row
          // separators are rules — no wrapper box.
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse text-left text-sm">
              <thead>
                <tr className="border-b border-stroke text-xs uppercase tracking-[0.08em] text-ink-muted">
                  <th className="px-4 py-3 font-semibold">Cycle</th>
                  <th className="px-4 py-3 font-semibold">Exam month / year</th>
                  <th className="px-4 py-3 font-semibold">Created</th>
                  <th className="px-4 py-3 text-right font-semibold">Status</th>
                </tr>
              </thead>
              <tbody>
                {cycles.map((c) => (
                  <Fragment key={c.id}>
                  <tr className="border-t border-stroke transition-colors hover:bg-surface-muted">
                    <td className="px-4 py-3">
                      {editingId === c.id ? (
                        <input
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          className={FIELD_INPUT}
                          aria-label="Cycle name"
                          data-cy="cycle-edit-name"
                        />
                      ) : (
                        <>
                          <span className="font-semibold text-ink">{c.name}</span>
                          {(c.batchLines || []).length > 0 && (
                            <span className="block text-xs text-ink-muted" data-cy="cycle-batch-count">
                              {c.batchLines.length} batch line{c.batchLines.length === 1 ? "" : "s"}
                            </span>
                          )}
                        </>
                      )}
                      {c.active && (
                        <span className="ml-2 inline-flex items-center gap-1 rounded-full bg-success-tint px-2.5 py-0.5 text-[11px] font-semibold text-success">
                          <CheckCircle2 size={12} /> Active
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {editingId === c.id ? (
                        <div className="flex gap-2">
                          <select
                            value={editMonth}
                            onChange={(e) => setEditMonth(e.target.value)}
                            className={FIELD_CONTROL}
                            aria-label="Exam month"
                            data-cy="cycle-edit-month"
                          >
                            <option value="">Month</option>
                            {EXAM_MONTHS.map((m) => (
                              <option key={m.value} value={m.value}>
                                {m.label}
                              </option>
                            ))}
                          </select>
                          <input
                            type="number"
                            inputMode="numeric"
                            value={editYear}
                            onChange={(e) => setEditYear(e.target.value)}
                            className={FIELD_CONTROL}
                            placeholder="Year"
                            aria-label="Exam year"
                            data-cy="cycle-edit-year"
                          />
                        </div>
                      ) : (
                        formatExamMonth(c.examMonthYear) || "\u2014"
                      )}
                    </td>
                    <td className="px-4 py-3 text-ink-muted">
                      {c.createdAt ? new Date(c.createdAt).toLocaleDateString() : "\u2014"}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-3 whitespace-nowrap">
                        {editingId === c.id ? (
                          <>
                            <button
                              type="button"
                              onClick={() => handleSaveEdit(c.id)}
                              disabled={savingId === c.id}
                              className={btn("accent", "sm")}
                              data-cy="cycle-edit-save"
                            >
                              {savingId === c.id ? (
                                <LoaderCircle size={14} className="animate-spin" />
                              ) : (
                                <Check size={14} />
                              )}
                              Save
                            </button>
                            <button
                              type="button"
                              onClick={() => setEditingId(null)}
                              className={btn("quiet", "sm")}
                              data-cy="cycle-edit-cancel"
                            >
                              <X size={14} /> Cancel
                            </button>
                          </>
                        ) : c.referenced ? (
                          // Every editable field here is one the registrations table and the
                          // printed form read live, so a referenced cycle offers no edit at all —
                          // a disabled button that 409s would be the same refusal, later.
                          <span className="text-xs font-semibold text-ink-muted" data-cy="cycle-locked">
                            Locked — has registrations
                          </span>
                        ) : (
                          <button
                            type="button"
                            onClick={() => startEdit(c)}
                            className={btn("quiet", "sm")}
                            data-cy="cycle-edit"
                          >
                            <Pencil size={14} /> Edit
                          </button>
                        )}
                        {c.active ? (
                          <>
                            <span className="text-xs font-semibold text-success">
                              Accepting registrations
                            </span>
                            <button
                              type="button"
                              onClick={() => handleEnd(c.id)}
                              disabled={endingId === c.id}
                              className={btn("danger", "sm")}
                              data-cy="cycle-end"
                            >
                              {endingId === c.id ? (
                                <LoaderCircle size={14} className="animate-spin" />
                              ) : (
                                <CircleSlash size={14} />
                              )}
                              End cycle
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            onClick={() => handleActivate(c.id)}
                            disabled={activatingId === c.id}
                            className={btn("accent", "sm")}
                            data-cy="cycle-activate"
                          >
                            {activatingId === c.id ? (
                              <LoaderCircle size={14} className="animate-spin" />
                            ) : (
                              <CheckCircle2 size={14} />
                            )}
                            Activate
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                  {editingId === c.id && (
                    // Full-width row, not cells: the repeater is its own two-column list and
                    // splitting it across the table's columns would tie line order to column
                    // order. Same shape as the subject catalog's inline edit.
                    <tr className="border-t border-stroke">
                      <td colSpan={4} className="px-4 pb-4">
                        <p className={`${FIELD_LABEL} text-ink-muted`}>Batch list on the printed form</p>
                        <p className="mt-1 text-xs text-ink-muted">
                          Each line prints as <span className="font-semibold">Programme and semesters</span>
                          {" ("}<span className="font-semibold">batch</span>{" Batch Students)"}. A cycle with
                          no lines prints no batch block.
                        </p>
                        {editLines.length === 0 && (
                          <p className="mt-2 text-sm text-ink-muted" data-cy="cycle-lines-empty">
                            No batch lines yet.
                          </p>
                        )}
                        <div className="mt-2 flex flex-col gap-2">
                          {editLines.map((line, index) => (
                            <div key={index} className="flex flex-wrap items-center gap-2">
                              <input
                                value={line.label}
                                onChange={(e) => updateLine(index, "label", e.target.value)}
                                placeholder="e.g. B.E. I to VII Semester"
                                maxLength={80}
                                className={`${FIELD_CONTROL} flex-1`}
                                aria-label={`Batch line ${index + 1} programme and semesters`}
                                data-cy="cycle-line-label"
                              />
                              <input
                                value={line.batch}
                                onChange={(e) => updateLine(index, "batch", e.target.value)}
                                placeholder="e.g. 2021"
                                maxLength={40}
                                className={`${FIELD_CONTROL} w-32`}
                                aria-label={`Batch line ${index + 1} batch`}
                                data-cy="cycle-line-batch"
                              />
                              <button
                                type="button"
                                onClick={() => removeLine(index)}
                                className={btn("danger", "sm")}
                                aria-label={`Remove batch line ${index + 1}`}
                                data-cy="cycle-line-remove"
                              >
                                <Trash2 size={14} />
                              </button>
                            </div>
                          ))}
                        </div>
                        <button
                          type="button"
                          onClick={addLine}
                          disabled={editLines.length >= MAX_BATCH_LINES}
                          className={`${btn("quiet", "sm")} mt-2`}
                          data-cy="cycle-line-add"
                        >
                          <PlusCircle size={14} /> Add line
                        </button>
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </AdminPageShell>
  );
}

export default ExamCyclePage;
