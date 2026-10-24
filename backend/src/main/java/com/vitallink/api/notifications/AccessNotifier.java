package com.vitallink.api.notifications;

import com.vitallink.api.core.Clock;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.consent.AccessListener;
import com.vitallink.api.notifications.NotificationService.Kind;
import com.vitallink.api.notifications.NotificationService.Message;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Tells owners when someone opens their shared records: at most once per share per day. */
@Component
public class AccessNotifier implements AccessListener {

    private final JdbcClient jdbc;
    private final NotificationService notifications;

    public AccessNotifier(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Override
    public void allowed(UUID ownerId, UUID viewerId, Scope scope, UUID consentId) {
        if (scope != Scope.records || consentId == null) {
            return;
        }
        var row = jdbc.sql("""
                        SELECT c.grantee_label, u.timezone FROM vitallink.consents c
                        JOIN vitallink.users u ON u.id = c.grantor_id WHERE c.id = :id
                        """)
                .param("id", consentId)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
                .optional();
        if (row.isEmpty()) {
            return;
        }
        String day = Clock.now()
                .atZoneSameInstant(ZoneId.of(row.get()[1]))
                .toLocalDate()
                .toString();
        notifications.notify(
                ownerId,
                "records-access:" + consentId + ":" + day,
                new Message(
                        Kind.records,
                        "Medical record accessed",
                        row.get()[0] + " viewed your shared records.",
                        null,
                        "View log",
                        "/privacy"));
    }
}
