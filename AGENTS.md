# SignDesk development

This repository implements the agreed Spring Boot Web design. Keep Java 21,
Spring Boot 4 / Spring MVC, SQLite, Hutool HTTP, and Vue3 / JavaScript. No Redis,
application login, Spring Security, JWT, desktop runtime, or separate production
frontend server is required. SQLite and the master key live outside the JAR.

## Build and test

- Backend: `mvn -f backend/pom.xml test` (Java 21, Maven 3.9+).
- Frontend: `cd frontend && npm ci && npm run build` (Node 22.12+ or 24).
- Complete release: `node scripts/build.mjs`. Builds frontend first, copies only
  fresh resources into backend, runs backend tests, and packages one JAR.
- Browser smoke tests: `cd frontend && npx playwright install chromium && npm run test:e2e`.
  These start a dedicated backend on port 18080 with an isolated temporary data
  directory, and use only an explicitly allowed local fixture target.

## Invariants

- Never execute pasted cURL with a shell or read files referenced by cURL.
- Preserve raw URL and body bytes; unsupported semantics need explicit diagnostics.
- Do not log raw requests, response bodies, SQL parameters, passwords, or keys.
- Keep an independent HTTP object / Cookie per execution. Do not use global cookies.
- Persist queued and running states before sending. HTTP timeout / interruption is
  `unknown`, never an automatic retry. A crash must not replay a running request.
- Automatic occurrence key is platform + UTC instant, independent of plan revision.
- Completed-day and unknown-day markers are independent of execution log retention.
- Platform requests are serial; network I/O must never hold a database transaction.
- Immutable request revision and rule snapshots are frozen when creating a batch.
- Updating code does not delete data or generate a replacement key for existing secrets.
- Never commit real cURL, `.env`, key files, databases, log files, or generated build files.
- Document supported behavior and limitations in README rather than implying all cURL
  options or browser transport fingerprints can be reproduced by Hutool.

Tests should exercise actual SQLite and a local HTTP fixture for execution changes.
Use an injected Clock for schedule boundary tests. No real platform credentials are
needed for repository tests.
