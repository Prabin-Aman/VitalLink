# Backend documentation

**The product is now web only.** The mobile app was removed in November 2026 and phone sync (phase 9) was cancelled.
Docs marked *historical* record decisions made at the time and may still mention the phone app, HealthKit or Python.
For those topics, the code and the current docs below are correct.

## Current

| Document | Contents |
|---|---|
| [01 Introduction](01-introduction.md) | What the backend does, who uses it, requirements |
| [02 Architecture](02-architecture.md) | System context, modules, request handling |
| [03 Class diagram](03-class-diagram.md) | Classes per module |
| [04 Database](04-database.md) | Schema and migrations |
| [05 API](05-api.md) | Routes, errors, limits |
| [06 Security and consent](06-security-and-consent.md) | Tokens, row-level security, the consent engine |
| [07 Flows](07-flows.md) | Key flows step by step |
| [08 Operations and testing](08-operations-and-testing.md) | Environments, secrets, failure handling |
| [11 Java stack](11-java-stack.md) | Every library and version, and the port from Python |
| [12 Test report](12-test-report.md) | Test suites, stress test, what's not covered |

## Historical

| Document | Contents |
|---|---|
| [09 Build plan](09-build-plan.md) | The phases as planned |
| [10 Open decisions](10-open-decisions.md) | Decisions D1 to D16 (D1 and D3 still need the owner) |
| [13 Review](13-review.md) | The phase 10 security review and its fixes |

The Word and PDF files in this folder are course deliverables written earlier. They aren't kept up to date.
