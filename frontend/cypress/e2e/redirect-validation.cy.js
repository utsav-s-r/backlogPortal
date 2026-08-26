// AdminLoginPage used to hand searchParams.get("redirect") straight to navigate(). On react-router
// 7.14.1 (GHSA-wrjc-x8rr-h8h6, vulnerable <7.18.0) a backslash or protocol-relative value escapes
// app routing and leaves the site — a phishing primitive delivered on the real domain, over the
// real certificate, after a genuinely successful login.
//
// NO DOM ASSERTION, deliberately, for the same reason session-expiry-load.cy.js has none: this is
// redirect-adjacent. Navigation assignment is non-configurable so cy.stub throws, and once
// navigation commits the page under test is gone. The decision is pure, so it is asserted directly.
// Mutation-tested 2026-08-26: each guard removed in turn, confirmed exactly the matching cases fail.
import { safeRedirect } from "../../src/lib/redirect";

const FALLBACK = "/admin";

describe("safeRedirect only honours same-origin paths", () => {
  it("keeps a legitimate deep link — the whole point of the parameter", () => {
    expect(safeRedirect("/admin/students", FALLBACK)).to.equal("/admin/students");
    expect(safeRedirect("/admin/registrations?status=SUBMITTED", FALLBACK)).to.equal(
      "/admin/registrations?status=SUBMITTED",
    );
  });

  it("rejects the backslash bypass — the specific CVE-2025-68470 bypass", () => {
    expect(safeRedirect("\\evil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("/\\evil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("/admin\\@evil.com", FALLBACK)).to.equal(FALLBACK);
  });

  it("rejects protocol-relative and absolute URLs", () => {
    expect(safeRedirect("//evil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("https://evil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("javascript:alert(1)", FALLBACK)).to.equal(FALLBACK);
  });

  it("rejects control characters — browsers strip them, turning /\\tevil.com into //evil.com", () => {
    expect(safeRedirect("/\tevil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("/\n/evil.com", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("/\r/evil.com", FALLBACK)).to.equal(FALLBACK);
  });

  it("rejects a relative path — only an absolute same-origin path is allowed", () => {
    expect(safeRedirect("admin/students", FALLBACK)).to.equal(FALLBACK);
  });

  it("falls back when the parameter is absent or empty", () => {
    expect(safeRedirect(null, FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect("", FALLBACK)).to.equal(FALLBACK);
    expect(safeRedirect(undefined, FALLBACK)).to.equal(FALLBACK);
  });

  // Two call sites with DIFFERENT fallbacks (/admin and /student), so the caller's value must be
  // returned rather than a constant. Without this every case above shares one fallback, and a
  // hardcoded "/admin" inside the helper would pass the whole suite while breaking student login.
  it("returns the CALLER's fallback, not a baked-in one", () => {
    expect(safeRedirect("//evil.com", "/student")).to.equal("/student");
    expect(safeRedirect(null, "/student")).to.equal("/student");
  });
});
