package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitallink.api.support.ApiTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The schema's own guarantees: lockdown, row-level security, the append-only audit log. */
class DatabaseTest extends ApiTest {

    @Test
    void readyWhenDatabaseIsUp() {
        Res res = get(null, "/ready");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.at("/status").asString()).isEqualTo("ready");
        assertThat(get(null, "/health").at("/status").asString()).isEqualTo("ok");
    }

    private List<String> strings(String query) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var c = sql();
                var rs = c.createStatement().executeQuery(query)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void nothingIsCreatedInPublic() throws SQLException {
        assertThat(strings("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"))
                .isEmpty();
    }

    @Test
    void everyTableHasRowLevelSecurity() throws SQLException {
        assertThat(strings("""
                        SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'vitallink' AND c.relkind = 'r' AND NOT c.relrowsecurity
                          AND relname <> 'flyway_schema_history'
                        """)).isEmpty();
    }

    private long insertRead(String outcome, String reason) throws SQLException {
        try (var c = sql();
                var st = c.prepareStatement(
                        "INSERT INTO vitallink.audit_log (actor_user_id, subject_user_id, action, scope, outcome, reason) "
                                + "VALUES (?, ?, 'read', 'sleep', CAST(? AS vitallink.access_outcome), "
                                + "CAST(? AS vitallink.refusal_reason)) RETURNING id")) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, UUID.randomUUID());
            st.setString(3, outcome);
            st.setString(4, reason);
            var rs = st.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void auditLogAcceptsInserts() throws SQLException {
        assertThat(insertRead("authorised", null)).isPositive();
        assertThat(insertRead("blocked", "expired")).isPositive();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE vitallink.audit_log SET outcome = 'authorised' WHERE id = ?",
                "DELETE FROM vitallink.audit_log WHERE id = ?"
            })
    void auditLogIsAppendOnly(String query) throws SQLException {
        long entry = insertRead("blocked", "revoked");
        try (var c = sql();
                var st = c.prepareStatement(query)) {
            st.setLong(1, entry);
            assertThatThrownBy(st::executeUpdate)
                    .isInstanceOfSatisfying(
                            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"))
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    void auditLogCannotBeTruncated() throws SQLException {
        try (var c = sql();
                var st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("TRUNCATE vitallink.audit_log")).hasMessageContaining("append-only");
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"blocked,", "authorised,expired"})
    void refusalReasonOnlyOnBlocked(String outcome, String reason) {
        assertThatThrownBy(() -> insertRead(outcome, reason))
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
    }
}
