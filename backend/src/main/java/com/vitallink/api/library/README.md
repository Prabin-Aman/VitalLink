# Library

Health articles, the same for everyone, no per-user reads. Content is Markdown in
`src/main/resources/library/` and is loaded at startup (idempotent by slug), so a deploy publishes it.

- `GET /library`, `GET /library/categories`, `GET /library/categories/{code}`, `GET /library/articles/{slug}`

Tables: `article_categories`, `articles`, `library_features`.
