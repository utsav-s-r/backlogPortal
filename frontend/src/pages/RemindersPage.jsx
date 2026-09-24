import { Fragment, useState, useEffect } from "react";
import { BellRing, CalendarClock, Eye, LoaderCircle, Send, X } from "lucide-react";
import AdminPageShell from "../components/layout/AdminPageShell";
import PrimaryCta from "../components/ui/PrimaryCta";
import AlertBanner from "../components/AlertBanner";
import Field from "../components/ui/Field";
import DepartmentOptions from "../components/ui/DepartmentOptions";
import api from "../lib/api";
import { reportLoadError } from "../lib/loadError";
import { ADMIN_ONLY } from "../lib/roles";
import { useRoleGuard } from "../hooks/useRoleGuard";
import { useArmedConfirm } from "../hooks/useArmedConfirm";
import { useAbortableRequest } from "../hooks/useAbortableRequest";
import { FIELD_CONTROL, FIELD_INPUT } from "../lib/formClasses";
import { btn } from "../lib/buttonClasses";
import { EMAIL_ERROR, isValidEmail } from "../lib/email";

// Mirrors ReminderService.MAX_SUBJECT / MAX_MESSAGE — the server refuses longer.
const MAX_SUBJECT = 150;
const MAX_MESSAGE = 4000;

// Send times are the college's clock: typed as IST and shown as IST, whatever the admin's browser
// zone. The server converts the typed wall-clock value with the same zone (ReminderService).
const IST = new Intl.DateTimeFormat("en-IN", {
  timeZone: "Asia/Kolkata",
  dateStyle: "medium",
  timeStyle: "short",
});
const formatIst = (iso) => (iso ? `${IST.format(new Date(iso))} IST` : "—");

const STATUS_PILL = {
  SCHEDULED: "bg-surface-muted text-ink",
  SENDING: "bg-surface-muted text-ink",
  SENT: "bg-success-tint text-success",
  CANCELLED: "bg-surface-muted text-ink-muted",
};

const statusLabel = (s) => s.charAt(0) + s.slice(1).toLowerCase();

function RemindersPage() {
  // ADMIN only: this emails students college-wide. Enforced by ReminderController's class-level
  // @PreAuthorize; this guard only keeps other roles off a page whose every call would 403.
  const allowed = useRoleGuard(ADMIN_ONLY);

  const [config, setConfig] = useState(null);
  const [reminders, setReminders] = useState(null);
  const [cycles, setCycles] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [loadError, setLoadError] = useState("");

  const [cycleId, setCycleId] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [sendDate, setSendDate] = useState("");
  const [sendTime, setSendTime] = useState("09:00");
  const [subject, setSubject] = useState("");
  const [message, setMessage] = useState("");

  const [preview, setPreview] = useState(null);
  const [previewing, setPreviewing] = useState(false);
  const nextPreviewSignal = useAbortableRequest();
  const [scheduling, setScheduling] = useState(false);
  const [formError, setFormError] = useState("");
  const [notice, setNotice] = useState("");

  const [testTo, setTestTo] = useState("");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null); // { ok, text }

  const confirmCancel = useArmedConfirm();
  const [cancellingId, setCancellingId] = useState(null);
  const [rowError, setRowError] = useState(null); // { id, message }
  const [failuresFor, setFailuresFor] = useState(null); // { id, rows | null, error }

  // Sets state only inside the promise callbacks, so calling it from the mount effect stays clear
  // of react-hooks/set-state-in-effect (lint holds at its known 9).
  const load = () =>
    Promise.all([
      api.get("/admin/reminders/config"),
      api.get("/admin/reminders"),
      api.get("/admin/exam-cycles"),
      api.get("/departments"),
    ])
      .then(([cfg, list, cyc, depts]) => {
        setConfig(cfg.data);
        setReminders(list.data);
        setCycles(cyc.data);
        setDepartments(Array.isArray(depts.data) ? depts.data : []);
      })
      .catch((err) => {
        reportLoadError(err, setLoadError, "Could not load reminders.");
      });

  const reloadList = () =>
    api
      .get("/admin/reminders")
      .then((res) => setReminders(res.data))
      .catch((err) => reportLoadError(err, setLoadError, "Could not reload reminders."));

  useEffect(() => {
    if (!allowed) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Any edit makes a preview stale — a shown one, and one still in flight, whose reply would
  // describe the email as it WAS. The edit is the superseder, so it aborts the request and clears
  // `previewing` itself: nothing else owns the flag once the request is cancelled.
  const edit = (setter) => (e) => {
    setter(e.target.value);
    setPreview(null);
    setNotice("");
    if (previewing) {
      nextPreviewSignal();
      setPreviewing(false);
    }
  };

  const requestBody = () => ({
    examCycleId: cycleId ? Number(cycleId) : null,
    departmentId: departmentId ? Number(departmentId) : null,
    sendAt: sendDate && sendTime ? `${sendDate}T${sendTime}` : "",
    subject: subject.trim(),
    message: message.trim(),
  });

  // Client copy of the server's rules — convenience only, ReminderService is the control.
  const validate = (needsTime) => {
    if (!cycleId) return "Pick an exam cycle.";
    if (needsTime && (!sendDate || !sendTime)) return "Pick a send date and time.";
    if (!subject.trim()) return "Subject is required.";
    if (!message.trim()) return "Message is required.";
    return "";
  };

  const handlePreview = async () => {
    const problem = validate(false);
    if (problem) {
      setFormError(problem);
      return;
    }
    const signal = nextPreviewSignal();
    setPreviewing(true);
    setFormError("");
    try {
      const res = await api.post("/admin/reminders/preview", requestBody(), { signal });
      setPreview(res.data);
    } catch (err) {
      if (err.code === "ERR_CANCELED") return; // superseded by an edit, which cleared the flag
      setFormError(err.response?.data?.message || "Could not build the preview.");
    }
    setPreviewing(false);
  };

  const handleSchedule = async (e) => {
    e.preventDefault();
    const problem = validate(true);
    if (problem) {
      setFormError(problem);
      return;
    }
    setScheduling(true);
    setFormError("");
    try {
      await api.post("/admin/reminders", requestBody());
      setSubject("");
      setMessage("");
      setPreview(null);
      setNotice("Reminder scheduled.");
      reloadList();
    } catch (err) {
      setFormError(err.response?.data?.message || "Could not schedule the reminder.");
    }
    setScheduling(false);
  };

  const handleTest = async () => {
    const to = testTo.trim();
    if (!isValidEmail(to)) {
      setTestResult({ ok: false, text: EMAIL_ERROR });
      return;
    }
    if (!subject.trim() || !message.trim()) {
      setTestResult({ ok: false, text: "Write the subject and message first." });
      return;
    }
    setTesting(true);
    setTestResult(null);
    try {
      const res = await api.post("/admin/reminders/test", {
        to,
        subject: subject.trim(),
        message: message.trim(),
      });
      setTestResult({ ok: true, text: res.data?.warning || `Test sent to ${to}.` });
    } catch (err) {
      setTestResult({ ok: false, text: err.response?.data?.message || "The test email failed." });
    }
    setTesting(false);
  };

  const handleCancel = async (id) => {
    setCancellingId(id);
    setRowError(null);
    try {
      await api.post(`/admin/reminders/${id}/cancel`);
      confirmCancel.disarm();
      reloadList();
    } catch (err) {
      // stays armed, like the department delete: the admin sees why and decides again
      setRowError({ id, message: err.response?.data?.message || "Could not cancel." });
    }
    setCancellingId(null);
  };

  const toggleFailures = (id) => {
    if (failuresFor?.id === id) {
      setFailuresFor(null);
      return;
    }
    setFailuresFor({ id, rows: null, error: "" });
    api
      .get(`/admin/reminders/${id}/failures`)
      .then((res) => setFailuresFor((cur) => (cur?.id === id ? { id, rows: res.data, error: "" } : cur)))
      .catch((err) =>
        setFailuresFor((cur) =>
          cur?.id === id
            ? { id, rows: [], error: err.response?.data?.message || "Could not load failures." }
            : cur,
        ),
      );
  };

  const deptName = (code) =>
    code ? departments.find((d) => d.code === code)?.deptName || code : "All departments";

  if (!allowed) return null;

  return (
    <AdminPageShell containerClassName="max-w-5xl">
      <h1 className="text-2xl font-semibold text-secondary-ink">Reminders</h1>
      <p className="mt-1 text-sm text-ink-muted">
        Schedule an email to every student with a verified registration in an exam cycle. Admin only.
      </p>
      {config && (
        <p className="mb-6 mt-2 text-sm text-ink-muted" data-cy="reminder-sender">
          {config.mailConfigured ? (
            <>
              Sent from{" "}
              <span className="font-semibold text-ink">
                {config.senderName} &lt;{config.senderEmail}&gt;
              </span>
              {" · "}up to {config.dailyCap} emails a day
            </>
          ) : (
            "Sender not configured."
          )}
        </p>
      )}

      {loadError && (
        <AlertBanner tone="error" role="alert" className="mb-4">
          {loadError}
        </AlertBanner>
      )}
      {config && !config.mailConfigured && (
        <AlertBanner tone="warning" className="mb-4" data-cy="reminder-mail-off">
          Email sending isn&apos;t set up on the server (BREVO_API_KEY and MAIL_FROM). You can
          schedule reminders, but nothing sends until it is.
        </AlertBanner>
      )}
      {config && !config.triggerConfigured && (
        <AlertBanner tone="warning" className="mb-4" data-cy="reminder-trigger-off">
          No scheduler token (CRON_TOKEN) is set, so nothing triggers scheduled reminders.
        </AlertBanner>
      )}

      <section className="mb-6 py-5">
        <h2 className="mb-4 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <CalendarClock size={18} /> New reminder
        </h2>
        <form onSubmit={handleSchedule} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Exam cycle *">
            <select
              value={cycleId}
              onChange={edit(setCycleId)}
              className={FIELD_INPUT}
              data-cy="reminder-cycle"
            >
              <option value="">Select a cycle</option>
              {cycles.map((c) => (
                <option key={c.id} value={String(c.id)}>
                  {c.name}
                  {c.active ? " (active)" : ""}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Department">
            <select
              value={departmentId}
              onChange={edit(setDepartmentId)}
              className={FIELD_INPUT}
              data-cy="reminder-department"
            >
              <option value="">All departments</option>
              <DepartmentOptions departments={departments} />
            </select>
          </Field>
          <div className="grid grid-cols-2 gap-4 sm:col-span-2">
            <Field label="Send date (IST) *">
              <input
                type="date"
                value={sendDate}
                onChange={edit(setSendDate)}
                className={FIELD_INPUT}
                data-cy="reminder-date"
              />
            </Field>
            <Field label="Send time (IST) *">
              <input
                type="time"
                value={sendTime}
                onChange={edit(setSendTime)}
                className={FIELD_INPUT}
                data-cy="reminder-time"
              />
            </Field>
          </div>
          <Field label="Subject *" className="sm:col-span-2">
            <input
              type="text"
              value={subject}
              maxLength={MAX_SUBJECT}
              onChange={edit(setSubject)}
              placeholder="e.g. Backlog exam hall tickets — collect by 3 October"
              className={FIELD_INPUT}
              data-cy="reminder-subject"
            />
          </Field>
          <Field label="Message *" className="sm:col-span-2">
            <textarea
              rows={6}
              value={message}
              maxLength={MAX_MESSAGE}
              onChange={edit(setMessage)}
              placeholder="Dates, venue, what to bring…"
              className={FIELD_INPUT}
              data-cy="reminder-message"
            />
          </Field>
          <p className="text-xs text-ink-muted sm:col-span-2">
            Each email opens with the student&apos;s name and USN, and ends with their verified
            subjects and a note that replies aren&apos;t read — you only write the middle. Who gets it
            is decided when it sends, so students verified after you schedule are included. It goes
            out at the next scheduler check after the send time.
          </p>

          {formError && (
            <AlertBanner tone="error" role="alert" className="sm:col-span-2" data-cy="reminder-form-error">
              {formError}
            </AlertBanner>
          )}
          {notice && (
            <AlertBanner tone="success" role="status" className="sm:col-span-2" data-cy="reminder-notice">
              {notice}
            </AlertBanner>
          )}

          <div className="flex flex-wrap items-center gap-3 sm:col-span-2">
            <button
              type="button"
              onClick={handlePreview}
              disabled={previewing}
              className={btn("neutral")}
              data-cy="reminder-preview"
            >
              {previewing ? <LoaderCircle size={14} className="animate-spin" /> : <Eye size={14} />}
              Preview
            </button>
            <PrimaryCta type="submit" disabled={scheduling} className="gap-2" data-cy="reminder-schedule">
              {scheduling ? <LoaderCircle size={16} className="animate-spin" /> : <BellRing size={16} />}
              {scheduling ? "Scheduling..." : "Schedule"}
            </PrimaryCta>
          </div>
        </form>

        {preview && (
          <div className="mt-5 rounded-lg bg-surface-muted p-4" data-cy="reminder-preview-panel">
            <p className="mb-3 text-sm font-semibold text-ink" data-cy="reminder-preview-count">
              Currently {preview.recipientCount} student{preview.recipientCount === 1 ? "" : "s"}
              <span className="font-normal text-ink-muted">
                {" "}
                — the final list is decided when it sends.
              </span>
            </p>
            <p className="text-xs text-ink-muted">
              To: {preview.sampleTo}
              {preview.recipientCount === 0 ? " (placeholder — no one matches yet)" : ""}
            </p>
            <p className="mb-2 text-xs text-ink-muted">Subject: {preview.sampleSubject}</p>
            <pre className="whitespace-pre-wrap break-words font-sans text-sm text-ink">
              {preview.sampleBody}
            </pre>
          </div>
        )}

        <div className="mt-5 flex flex-wrap items-center gap-2">
          <input
            type="email"
            value={testTo}
            onChange={(e) => setTestTo(e.target.value)}
            placeholder="you@example.com"
            aria-label="Send a test to"
            className={`${FIELD_CONTROL} w-72 max-w-full`}
            data-cy="reminder-test-to"
          />
          <button
            type="button"
            onClick={handleTest}
            disabled={testing}
            className={btn("neutral")}
            data-cy="reminder-test-send"
          >
            {testing ? <LoaderCircle size={14} className="animate-spin" /> : <Send size={14} />}
            Send test
          </button>
          <span className="text-xs text-ink-muted">Placeholder student data; check it arrives before scheduling.</span>
        </div>
        {testResult && (
          <p
            className={`mt-2 text-xs ${testResult.ok ? "text-success" : "text-alert"}`}
            role={testResult.ok ? "status" : "alert"}
            data-cy="reminder-test-result"
          >
            {testResult.text}
          </p>
        )}
      </section>

      <section className="py-5">
        <h2 className="mb-4 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
          <BellRing size={18} /> Scheduled and sent
        </h2>
        {reminders === null ? (
          !loadError && (
            <p className="inline-flex items-center gap-2 text-sm">
              <LoaderCircle size={16} className="animate-spin" /> Loading...
            </p>
          )
        ) : reminders.length === 0 ? (
          <p className="text-sm text-ink-muted">No reminders yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse text-left text-sm">
              <thead>
                <tr className="border-b border-stroke text-xs uppercase tracking-[0.08em] text-ink-muted">
                  <th className="px-4 py-3 font-semibold">Send at</th>
                  <th className="px-4 py-3 font-semibold">Audience</th>
                  <th className="px-4 py-3 font-semibold">Subject</th>
                  <th className="px-4 py-3 font-semibold">Status</th>
                  <th className="px-4 py-3 text-right font-semibold">Actions</th>
                </tr>
              </thead>
              <tbody>
                {reminders.map((r) => (
                  <Fragment key={r.id}>
                    <tr className="border-t border-stroke align-top" data-cy={`reminder-row-${r.id}`}>
                      <td className="whitespace-nowrap px-4 py-3">{formatIst(r.sendAt)}</td>
                      <td className="px-4 py-3">
                        <span className="font-semibold text-ink">{r.examCycleName}</span>
                        <span className="block text-xs text-ink-muted">{deptName(r.departmentCode)}</span>
                      </td>
                      <td className="px-4 py-3">{r.subject}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-flex rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${STATUS_PILL[r.status] || STATUS_PILL.SCHEDULED}`}
                          data-cy={`reminder-status-${r.id}`}
                        >
                          {statusLabel(r.status)}
                        </span>
                        {(r.sent > 0 || r.failed > 0) && (
                          <span className="block text-xs text-ink-muted" data-cy={`reminder-counts-${r.id}`}>
                            {r.sent} sent{r.failed > 0 ? ` · ${r.failed} failed` : ""}
                          </span>
                        )}
                        {r.lastError && r.status === "SENDING" && (
                          <span className="block max-w-xs text-xs text-alert" data-cy={`reminder-error-${r.id}`}>
                            {r.lastError}
                          </span>
                        )}
                        {rowError?.id === r.id && (
                          <span className="block text-xs text-alert" role="alert">
                            {rowError.message}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-2 whitespace-nowrap">
                          {r.failed > 0 && (
                            <button
                              type="button"
                              onClick={() => toggleFailures(r.id)}
                              aria-expanded={failuresFor?.id === r.id}
                              className={btn("quiet", "sm")}
                              data-cy={`reminder-failures-${r.id}`}
                            >
                              Failures
                            </button>
                          )}
                          {(r.status === "SCHEDULED" || r.status === "SENDING") &&
                            (confirmCancel.isArmed(r.id) ? (
                              <>
                                <button
                                  type="button"
                                  onClick={() => handleCancel(r.id)}
                                  disabled={cancellingId === r.id}
                                  className={btn("danger", "sm")}
                                  data-cy={`reminder-cancel-confirm-${r.id}`}
                                >
                                  {cancellingId === r.id && <LoaderCircle size={13} className="animate-spin" />}
                                  Confirm cancel
                                </button>
                                <button
                                  type="button"
                                  onClick={confirmCancel.disarm}
                                  className={btn("quiet", "sm")}
                                >
                                  Keep
                                </button>
                              </>
                            ) : (
                              <button
                                type="button"
                                onClick={() => confirmCancel.arm(r.id)}
                                className={btn("neutral", "sm")}
                                data-cy={`reminder-cancel-${r.id}`}
                              >
                                <X size={13} /> Cancel
                              </button>
                            ))}
                        </div>
                      </td>
                    </tr>
                    {failuresFor?.id === r.id && (
                      <tr className="border-t border-stroke">
                        <td colSpan={5} className="px-4 pb-4 pt-2" data-cy={`reminder-failure-list-${r.id}`}>
                          {failuresFor.rows === null ? (
                            <p className="inline-flex items-center gap-2 text-xs text-ink-muted">
                              <LoaderCircle size={13} className="animate-spin" /> Loading failures...
                            </p>
                          ) : failuresFor.error ? (
                            <p className="text-xs text-alert">{failuresFor.error}</p>
                          ) : (
                            <ul className="text-xs">
                              {failuresFor.rows.map((f) => (
                                <li key={f.rollNo} className="py-1">
                                  <span className="font-mono font-semibold">{f.rollNo}</span>{" "}
                                  <span className="text-ink-muted">{f.email}</span>{" "}
                                  <span className="text-alert">{f.error}</span>
                                </li>
                              ))}
                            </ul>
                          )}
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

export default RemindersPage;
