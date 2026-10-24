package com.vitallink.api.core.audit;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit log (docs/06-security-and-consent.md §6.1): append-only, no foreign keys on purpose.
 *
 * <p>{@link #write} is the only code that inserts into it. It runs in its own transaction, so a refused
 * read (which fails and rolls back the request) still leaves its entry behind.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final String INSERT = """
            INSERT INTO vitallink.audit_log
              (actor_user_id, subject_user_id, action, scope, outcome, reason, consent_id, request_id)
            VALUES (:actor, :subject, CAST(:action AS vitallink.audit_action),
                    CAST(:scope AS vitallink.share_scope), CAST(:outcome AS vitallink.access_outcome),
                    CAST(:reason AS vitallink.refusal_reason), :consent_id, :request_id)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;

    public AuditLog(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(manager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Commits one entry. A failure is logged, never shown to the user: the access decision stands
     * either way (docs/08 failure handling).
     */
    public void write(Audit.Event e) {
        Map<String, Object> params = new HashMap<>();
        params.put("actor", e.actorUserId());
        params.put("subject", e.subjectUserId());
        params.put("action", e.action().name());
        params.put("scope", e.scope() == null ? null : e.scope().name());
        params.put("outcome", e.outcome().name());
        params.put("reason", e.reason() == null ? null : e.reason().name());
        params.put("consent_id", e.consentId());
        params.put("request_id", e.requestId());
        try {
            ownTransaction.executeWithoutResult(
                    s -> jdbc.sql(INSERT).params(params).update());
        } catch (RuntimeException ex) {
            log.error("audit write failed for {} on {}", e.action(), e.subjectUserId(), ex);
        }
    }
}
