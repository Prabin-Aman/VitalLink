# Tools

Command-line helpers for local work, refused outside ENV=local/test.

- `DevToken <email> [--first A --last B --unconfirmed --hours N]`: a bearer token for that account
- `DemoSeed [--email x]`: 90 days of data across every module (Figma values in the last week)
- `SystemCheck <base-url>`: black-box system test of a running API over real HTTP (44 steps; exit 1 on any failure)
- `LoadCheck <base-url>`: times a 5,000-bucket ingest for a new user and the p95 of the dashboard, health
  overview, training and goals for the demo account (run DemoSeed first); exit 1 if a target is missed

Run with `./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.<Tool> -Dexec.args="…"`.
