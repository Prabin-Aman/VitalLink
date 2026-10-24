package com.vitallink.api.account;

import com.vitallink.api.core.ApiError;
import com.vitallink.api.core.Clock;
import com.vitallink.api.core.security.IdentityAdmin;
import com.vitallink.api.core.userdata.UserData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Export everything an account owns, and delete the account (docs/06 privacy commitments). */
@Service
public class DataRightsService {

    private static final Logger log = LoggerFactory.getLogger(DataRightsService.class);

    /** Deleting an account needs a sign-in within the last 10 minutes. */
    static final long RECENT_SECONDS = 600;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final List<UserData> modules;
    private final IdentityAdmin identities;

    public DataRightsService(JdbcClient jdbc, ObjectMapper json, List<UserData> modules, IdentityAdmin identities) {
        this.jdbc = jdbc;
        this.json = json;
        this.modules = modules;
        this.identities = identities;
    }

    /** Every row the user owns, by section. Rows are exported by Postgres as JSON (no field is left out). */
    public Map<String, Object> export(CurrentUser me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "vitallink-export/1");
        out.put("exported_at", Clock.now());
        out.put("user_id", me.id());
        for (UserData module : modules) {
            for (var e : module.exportQueries().entrySet()) {
                String rows = jdbc.sql("SELECT coalesce(jsonb_agg(to_jsonb(x)), '[]'::jsonb)::text FROM ("
                                + e.getValue() + ") x")
                        .param("u", me.id())
                        .query(String.class)
                        .single();
                JsonNode node = json.readTree(rows);
                out.put(e.getKey(), node);
            }
        }
        return out;
    }

    /**
     * Deletes the account and everything it owns. Shares the user received are revoked and unbound first,
     * so a later sign-up with the same email can't inherit them. The audit log keeps its entries (it has
     * no personal free text). The sign-in identity is deleted after the database commit.
     */
    public void delete(CurrentUser me) {
        if (!me.claims().isRecent(RECENT_SECONDS)) {
            throw new ApiError(403, "reauth_required", "Sign in again to delete your account.");
        }
        UUID id = me.id();
        jdbc.sql("""
                        UPDATE vitallink.consents
                        SET grantee_user_id = NULL,
                            revoked_at = coalesce(revoked_at, now()),
                            revoke_reason = coalesce(revoke_reason, 'grantee_deleted')
                        WHERE grantee_user_id = :u
                        """).param("u", id).update();
        jdbc.sql("""
                        UPDATE vitallink.consents SET revoked_at = now(), revoke_reason = 'grantee_deleted'
                        WHERE grantee_user_id IS NULL AND grantee_email = :email AND revoked_at IS NULL
                        """).param("email", me.user().email()).update();
        for (UserData module : modules) {
            module.beforeDelete(id);
        }
        jdbc.sql("DELETE FROM vitallink.users WHERE id = :u").param("u", id).update();
        UUID subject = me.user().authSubject();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!identities.deleteIdentity(subject)) {
                    log.error("account {} deleted but its sign-in identity remains; delete it in Supabase", id);
                }
            }
        });
    }
}
