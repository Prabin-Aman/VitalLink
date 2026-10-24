# Jobs

`POST /internal/jobs/nightly` (header `X-Job-Secret`, compared in constant time), run by
`.github/workflows/nightly.yml`. Per user, in its own transaction: goal achievements, weekly goals at risk,
yesterday's missed doses, refills due, tomorrow's appointments, overdue devices. Then retention: old
notifications, old ingest batches, long-expired shares, abandoned uploads.
