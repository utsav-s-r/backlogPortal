import { defineConfig } from "cypress";

// SEPARATE config for the unstubbed smoke specs (cypress/e2e-live), deliberately not folded into
// cypress.config.js. Two reasons, both load-bearing:
//   1. `npm run test:e2e` must keep needing NO backend. The default config's specPattern covers
//      cypress/e2e only, so these can never be picked up by it.
//   2. The default support file throws on any unstubbed /api/admin call — correct there, fatal
//      here, where every call is real. This config loads its own support file instead.
// Needs a booted backend and the backlog_e2e database — full recipe in the header of
// cypress/e2e-live/student-journey.cy.js.
export default defineConfig({
  e2e: {
    baseUrl: "http://127.0.0.1:4173",
    specPattern: "cypress/e2e-live/**/*.cy.{js,jsx}",
    supportFile: "cypress/support/live.js",
    // A real JVM answers these, not an intercept: login does BCrypt and the PDF is rendered
    // in-process, both far slower than a stub. Generous rather than flaky.
    defaultCommandTimeout: 15000,
    responseTimeout: 30000,
  },
  video: false,
});
