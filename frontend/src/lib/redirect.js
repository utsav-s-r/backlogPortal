// Owns "is this ?redirect= destination safe to navigate to?". Own module, not a helper inside a
// login page: a non-component export in a .jsx takes lint to 10 (react-refresh/only-export-components),
// and both login pages must share ONE rule or the weaker one becomes the hole.
//
// The gap this closes: AdminLoginPage passed searchParams.get("redirect") straight to navigate().
// react-router 7.14.1 carries GHSA-wrjc-x8rr-h8h6 (open redirect via backslash in useNavigate, the
// CVE-2025-68470 bypass, range >=6.0.0 <7.18.0), so a crafted value escapes app routing entirely.
// That is the ideal phishing primitive here — real domain, real cert, real successful login, then a
// bounce to an attacker's "session expired, sign in again" page. Worse for this app than most:
// staff passwords are derivable (username + "4321") and no login path rate-limits.

// Browsers STRIP tab/CR/LF from a URL before resolving it, so "/\tevil.com" resolves as
// "//evil.com". Reject every control char rather than predict the stripping. Char codes, not a
// regex: a control-char class trips `no-control-regex`, and this file must not add a 10th lint
// error to the 9 intentional ones.
function hasControlChar(value) {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

/** Only an absolute same-origin PATH is allowed. Everything else falls back. */
function isSafePath(value) {
  if (typeof value !== "string" || value === "") return false;
  if (hasControlChar(value)) return false;
  if (value.includes("\\")) return false; // the specific GHSA-wrjc-x8rr-h8h6 bypass
  if (!value.startsWith("/")) return false; // absolute path only — no "https:", no "evil.com"
  if (value.startsWith("//")) return false; // protocol-relative: "//evil.com" is off-site
  return true;
}

/**
 * Resolve a `?redirect=` value to somewhere it is safe to navigate.
 * Returns `fallback` for anything that is not a same-origin absolute path — never throws, so a
 * caller is always a single `navigate(safeRedirect(...))`.
 */
export function safeRedirect(value, fallback) {
  return isSafePath(value) ? value : fallback;
}
