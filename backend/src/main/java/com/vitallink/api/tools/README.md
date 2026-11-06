# Tools

Command-line helpers for local work, refused outside ENV=local/test.

- `DevToken <email> [--first A --last B --unconfirmed --hours N]`: a bearer token for that account
- `DemoSeed [--email x]`: 90 days of data across every module (Figma values in the last week)
- `SystemCheck <base-url>`: black-box system test of a running API over real HTTP (47 steps; exit 1 on any failure)
- `LoadCheck <base-url>`: times a 5,000-bucket ingest for a new user and the p95 of the dashboard, health
  overview, training and goals for the demo account (run DemoSeed first); exit 1 if a target is missed

- `PhoneSim <base-url> [--email E --hours N --platform healthkit|health_connect]`: uploads test readings through ingest
  until phase 9 — uploads the last N hours of steps, heart rate and active energy as 5-minute buckets with the
  watch and phone as devices, through `POST /v1/me/health/ingest`. Values come from each bucket's time, so a
  rerun shows up as unchanged readings

- `SetPassword <email>`: gives an existing account (e.g. the demo account) an email/password sign-in; the
  password comes from the `VITALLINK_PASSWORD` environment variable

Run with `./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.<Tool> -Dexec.args="…"`.
