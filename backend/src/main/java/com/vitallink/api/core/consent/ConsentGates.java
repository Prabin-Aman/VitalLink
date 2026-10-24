package com.vitallink.api.core.consent;

import com.vitallink.api.core.audit.AuditLog;
import com.vitallink.api.core.consent.Consent.Viewer;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Makes consent gates. Shared views get theirs from the request ({@code /users/{user_id}/…}); views that
 * read several people's data at once (the family list) make one per person here.
 */
@Component
public class ConsentGates {

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final List<AccessListener> listeners;

    public ConsentGates(JdbcClient jdbc, AuditLog audit, List<AccessListener> listeners) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.listeners = listeners;
    }

    public ConsentGate open(Viewer viewer, UUID ownerId, String requestId) {
        return new ConsentGate(jdbc, audit, viewer, ownerId, requestId, listeners);
    }
}
