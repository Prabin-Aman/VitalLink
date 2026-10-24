package com.vitallink.api.library;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The health library: the same for everyone, no per-user reads (docs/04 §4.7). Content is Markdown in
 * {@code src/main/resources/library}; on startup it's loaded into the database (idempotent by slug), so a
 * deploy is how articles are published. There is no admin interface.
 */
@Service
public class LibraryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    public record Category(String code, String label) {}

    /** {@code Article} in types.ts. */
    public record Article(
            UUID id, String slug, String title, String category, int minutes, String reviewer, String summary) {}

    public record ArticleDetail(
            UUID id,
            String slug,
            String title,
            String category,
            int minutes,
            String reviewer,
            String summary,
            String bodyMd,
            LocalDate publishedOn) {}

    /** {@code Library} in types.ts. */
    public record Library(List<String> categories, Article featured, Article featuredMobile, List<Article> trending) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public LibraryService(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    // ---------------------------------------------------------------- loading

    /** A parsed article file: front matter (key: value lines between ---) and the Markdown body. */
    record ArticleFile(String slug, Map<String, String> meta, String body) {}

    static ArticleFile parse(String slug, String text) {
        String normalised = text.replace("\r\n", "\n");
        if (!normalised.startsWith("---\n")) {
            throw new IllegalArgumentException(slug + ": missing front matter");
        }
        int end = normalised.indexOf("\n---\n", 4);
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : normalised.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                meta.put(
                        line.substring(0, colon).strip(),
                        line.substring(colon + 1).strip());
            }
        }
        return new ArticleFile(slug, meta, normalised.substring(end + 5).strip() + "\n");
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            load();
        } catch (RuntimeException e) {
            // The API still serves everything else; the library says it's empty until the next start.
            log.error("library content failed to load", e);
        }
    }

    public void load() {
        var resolver = new PathMatchingResourcePatternResolver();
        List<Category> categories;
        List<ArticleFile> articles = new ArrayList<>();
        try {
            String cats =
                    resolver.getResource("classpath:library/categories.txt").getContentAsString(StandardCharsets.UTF_8);
            categories = Arrays.stream(cats.split("\n"))
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.split("\\|", 2))
                    .map(p -> new Category(p[0].strip(), p[1].strip()))
                    .toList();
            for (Resource r : resolver.getResources("classpath:library/articles/*.md")) {
                String name = r.getFilename();
                articles.add(parse(name.substring(0, name.length() - 3), r.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        articles.sort(Comparator.comparing(ArticleFile::slug));
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < categories.size(); i++) {
                jdbc.sql("UPDATE vitallink.article_categories SET position = position + 1000 WHERE code = :c")
                        .param("c", categories.get(i).code())
                        .update();
            }
            for (int i = 0; i < categories.size(); i++) {
                Category c = categories.get(i);
                jdbc.sql("""
                                INSERT INTO vitallink.article_categories (code, label, position) VALUES (:c, :l, :p)
                                ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, position = EXCLUDED.position
                                """)
                        .param("c", c.code())
                        .param("l", c.label())
                        .param("p", i)
                        .update();
            }
            jdbc.sql("UPDATE vitallink.articles SET trending_rank = NULL").update();
            jdbc.sql("DELETE FROM vitallink.library_features").update();
            for (ArticleFile a : articles) {
                Map<String, String> m = a.meta();
                String reviewer = m.getOrDefault("reviewer", "");
                UUID id = jdbc.sql("""
                                INSERT INTO vitallink.articles (id, slug, category_code, title, minutes, reviewer, summary,
                                                                body_md, trending_rank, published_on)
                                VALUES (:id, :slug, :cat, :title, :minutes, :reviewer, :summary, :body, :rank, :published)
                                ON CONFLICT (slug) DO UPDATE SET category_code = EXCLUDED.category_code,
                                  title = EXCLUDED.title, minutes = EXCLUDED.minutes, reviewer = EXCLUDED.reviewer,
                                  summary = EXCLUDED.summary, body_md = EXCLUDED.body_md,
                                  trending_rank = EXCLUDED.trending_rank, published_on = EXCLUDED.published_on,
                                  updated_at = now()
                                RETURNING id
                                """)
                        .param("id", Ids.uuid7())
                        .param("slug", a.slug())
                        .param("cat", m.get("category"))
                        .param("title", m.get("title"))
                        .param("minutes", Integer.parseInt(m.get("minutes")))
                        .param("reviewer", reviewer.isEmpty() ? null : reviewer)
                        .param("summary", m.get("summary"))
                        .param("body", a.body())
                        .param("rank", m.containsKey("trending") ? Integer.parseInt(m.get("trending")) : null)
                        .param("published", LocalDate.parse(m.get("published")))
                        .query(UUID.class)
                        .single();
                for (String placement : m.getOrDefault("featured", "").split(",")) {
                    if (!placement.isBlank()) {
                        jdbc.sql("INSERT INTO vitallink.library_features (placement, article_id) "
                                        + "VALUES (CAST(:p AS vitallink.placement), :a)")
                                .param("p", placement.strip())
                                .param("a", id)
                                .update();
                    }
                }
            }
            List<String> slugs = articles.stream().map(ArticleFile::slug).toList();
            jdbc.sql("DELETE FROM vitallink.articles WHERE slug NOT IN (:slugs)")
                    .param("slugs", slugs)
                    .update();
        });
        log.info("library loaded: {} categories, {} articles", categories.size(), articles.size());
    }

    // ---------------------------------------------------------------- reads

    private static final String ARTICLE = """
            SELECT a.id, a.slug, a.title, c.label AS category, a.minutes, a.reviewer, a.summary, a.body_md,
                   a.published_on
            FROM vitallink.articles a JOIN vitallink.article_categories c ON c.code = a.category_code
            """;

    private static Article article(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Article(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getInt("minutes"),
                rs.getString("reviewer"),
                rs.getString("summary"));
    }

    private Article featured(String placement) {
        return jdbc.sql(ARTICLE + " JOIN vitallink.library_features f ON f.article_id = a.id "
                        + "WHERE f.placement = CAST(:p AS vitallink.placement)")
                .param("p", placement)
                .query((rs, i) -> article(rs))
                .optional()
                .orElse(null);
    }

    public Library library() {
        List<String> categories = jdbc.sql("SELECT label FROM vitallink.article_categories ORDER BY position")
                .query(String.class)
                .list();
        List<Article> trending = jdbc.sql(ARTICLE + " WHERE a.trending_rank IS NOT NULL ORDER BY a.trending_rank")
                .query((rs, i) -> article(rs))
                .list();
        Article web = featured("web");
        if (web == null && trending.isEmpty()) {
            throw new ApiError(404, "library_empty", "The library has no articles yet.");
        }
        Article mobile = featured("mobile");
        return new Library(
                categories,
                web != null ? web : trending.getFirst(),
                mobile != null ? mobile : web != null ? web : trending.getFirst(),
                trending);
    }

    public ArticleDetail article(String slug) {
        return jdbc.sql(ARTICLE + " WHERE a.slug = :s")
                .param("s", slug)
                .query((rs, i) -> new ArticleDetail(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("category"),
                        rs.getInt("minutes"),
                        rs.getString("reviewer"),
                        rs.getString("summary"),
                        rs.getString("body_md"),
                        rs.getObject("published_on", LocalDate.class)))
                .optional()
                .orElseThrow(() -> ApiError.notFound("No such article."));
    }

    public List<Article> category(String code) {
        boolean exists = !jdbc.sql("SELECT 1 FROM vitallink.article_categories WHERE code = :c")
                .param("c", code)
                .query()
                .listOfRows()
                .isEmpty();
        if (!exists) {
            throw ApiError.notFound("No such category.");
        }
        return jdbc.sql(ARTICLE + " WHERE a.category_code = :c ORDER BY a.published_on DESC, a.title")
                .param("c", code)
                .query((rs, i) -> article(rs))
                .list();
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, label FROM vitallink.article_categories ORDER BY position")
                .query((rs, i) -> new Category(rs.getString("code"), rs.getString("label")))
                .list();
    }
}
