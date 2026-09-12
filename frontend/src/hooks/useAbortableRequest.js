import { useEffect, useRef } from "react";

/**
 * One in-flight request per caller: asking for a new signal ABORTS whatever was still running.
 *
 * The problem it removes: a reply is written to state with no correlation to the request that
 * produced it, so a slow reply lands after the user has already changed the inputs and contradicts
 * what is on screen — a failed login reporting itself over corrected credentials, a student list
 * rendered under a department filter it was not fetched for. Disabling the trigger does NOT fix
 * that: the INPUTS stay editable, so the stale reply still arrives against changed state.
 *
 * Callers ignore the aborted attempt with the codebase's existing convention:
 *   catch (err) { if (err.code === "ERR_CANCELED") return; ... }
 * (see lib/loadError.js — "superseded by a newer request, which owns the flag now").
 *
 * Pair it with a trigger that is NOT `disabled` while busy, or nothing can supersede and this
 * buys only the unmount abort. A genuine mutation is the exception — there, double-submit
 * protection is worth more than a fast retry, so the trigger stays disabled deliberately.
 */
export function useAbortableRequest() {
  const controllerRef = useRef(null);

  // Leaving the page aborts whatever is still running; without this the reply lands on an
  // unmounted component and its state writes are dropped silently.
  useEffect(() => () => controllerRef.current?.abort(), []);

  /** Abort the previous attempt and return the signal for this one. */
  return () => {
    controllerRef.current?.abort();
    controllerRef.current = new AbortController();
    return controllerRef.current.signal;
  };
}
