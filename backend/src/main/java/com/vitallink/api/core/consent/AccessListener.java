package com.vitallink.api.core.consent;

import com.vitallink.api.core.audit.Audit.Scope;
import java.util.UUID;

/** Told about every allowed shared read, after it's audited (e.g. to tell the owner records were opened). */
public interface AccessListener {

    void allowed(UUID ownerId, UUID viewerId, Scope scope, UUID consentId);
}
