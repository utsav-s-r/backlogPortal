// The shared failure ritual for a fetch-on-load. Own module, not lib/api.js: that is pure transport
// + the auth interceptor, this takes a setState. Hand-written it drifted three ways — ERR_CANCELED
// skipped or not, 401 skipped or not, server message kept or dropped.

/**
 * Report a failed load through `setError` unless the page is already going away.
 * True = page still alive, finish the loading transition. False = leave `loading` SET, because:
 *   - ERR_CANCELED — superseded by a newer request, which owns the flag now.
 *   - 401 — session lapsed, api.js is redirecting; clearing loading paints the empty state
 *     ("no departments are configured") or a false error banner before the redirect lands.
 * NOT solvable by suppressing 401 in the interceptor: that needs a never-settling promise, which
 * hangs every chain and skips `finally` blocks doing real cleanup.
 */
export function reportLoadError(err, setError, fallback) {
  if (err?.code === "ERR_CANCELED") return false;
  if (err?.response?.status === 401) return false;
  setError(err?.response?.data?.message || fallback);
  return true;
}
