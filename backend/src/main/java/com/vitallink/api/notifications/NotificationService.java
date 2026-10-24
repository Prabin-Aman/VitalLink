package com.vitallink.api.notifications;

import com.vitallink.api.core.Ids;
import com.vitallink.api.core.userdata.UserData;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The in-app feed. Other modules call {@link #notify}; the dedupe key makes repeats harmless, so callers
 * don't need to remember what they already sent.
 */
@Service
public class NotificationService implements UserData {

    public enum Kind {
        fitness,
        health,
        medication,
        appointments,
        records,
        security,
        goals
    }

    /** {@code AppNotification} in types.ts. {@code target} is the app route the action opens. */
    public record Notification(
            UUID id,
            Kind kind,
            OffsetDateTime at,
            String title,
            String body,
            String shortBody,
            String action,
            String target,
            boolean read) {}

    /** What to say; built by the module the event belongs to. */
    public record Message(Kind kind, String title, String body, String shortBody, String action, String target) {}

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }

    /** Adds a notification unless one with the same dedupe key exists. Returns whether it was added. */
    public boolean notify(UUID userId, String dedupeKey, Message m) {
        return jdbc.sql("""
                        INSERT INTO vitallink.notifications
                          (id, user_id, kind, title, body, short_body, action, target, dedupe_key)
                        VALUES (:id, :u, CAST(:kind AS vitallink.notification_kind), :title, :body, :short, :action,
                                :target, :key)
                        ON CONFLICT (user_id, dedupe_key) DO NOTHING
                        """)
                        .param("id", Ids.uuid7())
                        .param("u", userId)
                        .param("kind", m.kind().name())
                        .param("title", clip(m.title(), 120))
                        .param("body", clip(m.body(), 300))
                        .param("short", clip(m.shortBody(), 160))
                        .param("action", clip(m.action(), 30))
                        .param("target", m.target())
                        .param("key", clip(dedupeKey, 200))
                        .update()
                == 1;
    }

    public List<Notification> list(UUID userId, String tz, boolean unreadOnly, int limit) {
        ZoneId zone = ZoneId.of(tz);
        return jdbc.sql("SELECT * FROM vitallink.notifications WHERE user_id = :u"
                        + (unreadOnly ? " AND read_at IS NULL" : "")
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("u", userId)
                .param("limit", limit)
                .query((rs, i) -> new Notification(
                        rs.getObject("id", UUID.class),
                        Kind.valueOf(rs.getString("kind")),
                        rs.getObject("created_at", OffsetDateTime.class)
                                .atZoneSameInstant(zone)
                                .toOffsetDateTime(),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("short_body"),
                        rs.getString("action"),
                        rs.getString("target"),
                        rs.getObject("read_at") != null))
                .list();
    }

    /** Marks the given notifications (or all) read. Returns how many changed. */
    public int markRead(UUID userId, List<UUID> ids, boolean all) {
        if (all) {
            return jdbc.sql("UPDATE vitallink.notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL")
                    .param("u", userId)
                    .update();
        }
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE vitallink.notifications SET read_at = now() "
                        + "WHERE user_id = :u AND id IN (:ids) AND read_at IS NULL")
                .param("u", userId)
                .param("ids", ids)
                .update();
    }

    /** Retention: notifications older than 90 days go (nightly job). */
    public int deleteOld() {
        return jdbc.sql("DELETE FROM vitallink.notifications WHERE created_at < now() - interval '90 days'")
                .update();
    }

    @Override
    public Map<String, String> exportQueries() {
        return Map.of(
                "notifications",
                "SELECT id, kind, title, body, short_body, action, target, created_at, read_at "
                        + "FROM vitallink.notifications WHERE user_id = :u ORDER BY created_at, id");
    }
}
