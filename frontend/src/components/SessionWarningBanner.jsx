import AlertBanner from "./AlertBanner";

// Warns before the fixed 1h session ends. Sessions can't be extended, so this is the only chance
// the user gets to save work in progress — a long CSV import or a part-filled registration form
// is otherwise lost at the cutoff.
function SessionWarningBanner({ minutesLeft }) {
  if (minutesLeft == null) return null;

  return (
    <AlertBanner
      tone="warning"
      role="status"
      aria-live="polite"
      data-cy="session-warning"
      className="mx-auto mb-4 max-w-5xl"
    >
      <strong className="font-semibold">
        Your session ends in {minutesLeft} minute{minutesLeft === 1 ? "" : "s"}.
      </strong>{" "}
      Save anything in progress — sessions last one hour and can't be extended, so you'll need to
      sign in again.
    </AlertBanner>
  );
}

export default SessionWarningBanner;
