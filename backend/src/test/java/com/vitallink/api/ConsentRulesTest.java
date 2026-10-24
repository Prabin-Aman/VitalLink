package com.vitallink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.core.audit.Audit.RefusalReason;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.consent.Consent;
import com.vitallink.api.core.consent.Consent.Decision;
import com.vitallink.api.core.consent.Consent.Grant;
import com.vitallink.api.core.consent.Consent.Viewer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** decide(): every rule in docs/06-security-and-consent.md §6.1, without a database. */
class ConsentRulesTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 13, 15, 0, 0, 0, ZoneOffset.UTC);
    static final UUID OWNER = UUID.randomUUID();
    static final Viewer VIEWER = new Viewer(UUID.randomUUID(), "dr.bennett@example.com", true);

    /** A live vitals grant to VIEWER's email; change one field with the with-methods. */
    record G(
            UUID granteeUserId,
            String email,
            Set<Scope> scopes,
            OffsetDateTime starts,
            OffsetDateTime expires,
            OffsetDateTime revoked,
            OffsetDateTime created) {
        static G base() {
            return new G(
                    null, VIEWER.email(), EnumSet.of(Scope.vitals), NOW.minusDays(1), null, null, NOW.minusDays(1));
        }

        Grant grant() {
            return new Grant(UUID.randomUUID(), granteeUserId, email, scopes, starts, expires, revoked, created);
        }
    }

    static Decision decide(Viewer v, Scope scope, Grant... grants) {
        return Consent.decide(v, OWNER, scope, List.of(grants), NOW);
    }

    @Test
    void rule1OwnerReadsOwnData() {
        assertThat(decide(new Viewer(OWNER, "me@example.com", false), Scope.records)
                        .allowed())
                .isTrue();
    }

    @Test
    void rule2UnconfirmedEmailMatchesNothing() {
        Decision d = decide(
                new Viewer(VIEWER.userId(), VIEWER.email(), false),
                Scope.vitals,
                G.base().grant());
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(RefusalReason.email_unconfirmed);
    }

    @Test
    void rule3NoGrant() {
        assertThat(decide(VIEWER, Scope.vitals).reason()).isEqualTo(RefusalReason.no_grant);
    }

    @Test
    void rule3GrantToSomeoneElseDoesNotMatch() {
        G b = G.base();
        Grant other = new G(null, "someone@example.com", b.scopes(), b.starts(), null, null, b.created()).grant();
        assertThat(decide(VIEWER, Scope.vitals, other).reason()).isEqualTo(RefusalReason.no_grant);
    }

    @Test
    void rule3BoundGrantNeverMatchesByEmailAgain() {
        G b = G.base();
        Grant boundElsewhere =
                new G(UUID.randomUUID(), b.email(), b.scopes(), b.starts(), null, null, b.created()).grant();
        assertThat(decide(VIEWER, Scope.vitals, boundElsewhere).reason()).isEqualTo(RefusalReason.no_grant);
    }

    @Test
    void rule3BoundGrantFollowsTheAccountNotTheEmail() {
        G b = G.base();
        Grant bound = new G(VIEWER.userId(), "old.address@example.com", b.scopes(), b.starts(), null, null, b.created())
                .grant();
        assertThat(decide(VIEWER, Scope.vitals, bound).allowed()).isTrue();
    }

    @Test
    void rule4ScopeNotGranted() {
        assertThat(decide(VIEWER, Scope.records, G.base().grant()).reason()).isEqualTo(RefusalReason.scope_not_granted);
    }

    @Test
    void rule5Revoked() {
        G b = G.base();
        Grant g = new G(null, b.email(), b.scopes(), b.starts(), null, NOW, b.created()).grant();
        assertThat(decide(VIEWER, Scope.vitals, g).reason()).isEqualTo(RefusalReason.revoked);
    }

    @Test
    void rule6NotYetValid() {
        G b = G.base();
        Grant g = new G(null, b.email(), b.scopes(), NOW.plusSeconds(1), null, null, b.created()).grant();
        assertThat(decide(VIEWER, Scope.vitals, g).reason()).isEqualTo(RefusalReason.not_yet_valid);
    }

    @ParameterizedTest(name = "expires {0}s from now")
    @ValueSource(longs = {0, -1})
    void rule7ExpiredAndExpiryIsExclusive(long offset) {
        G b = G.base();
        Grant g = new G(null, b.email(), b.scopes(), b.starts(), NOW.plusSeconds(offset), null, b.created()).grant();
        assertThat(decide(VIEWER, Scope.vitals, g).reason()).isEqualTo(RefusalReason.expired);
    }

    @Test
    void rule8LiveGrantAllowsAndAsksForBinding() {
        G b = G.base();
        Grant g = new G(null, b.email(), b.scopes(), b.starts(), NOW.plusSeconds(1), null, b.created()).grant();
        Decision d = decide(VIEWER, Scope.vitals, g);
        assertThat(d.allowed()).isTrue();
        assertThat(d.grant()).isEqualTo(g);
        assertThat(d.needsBinding()).isTrue();
    }

    @Test
    void rule8AlreadyBoundGrantNeedsNoBinding() {
        G b = G.base();
        Grant g = new G(VIEWER.userId(), b.email(), b.scopes(), b.starts(), null, null, b.created()).grant();
        Decision d = decide(VIEWER, Scope.vitals, g);
        assertThat(d.allowed()).isTrue();
        assertThat(d.needsBinding()).isFalse();
    }

    @Test
    void anyLiveGrantWinsOverDeadOnes() {
        G b = G.base();
        Grant dead = new G(null, b.email(), b.scopes(), b.starts(), null, NOW, NOW).grant();
        Grant live = new G(null, b.email(), b.scopes(), b.starts(), null, null, NOW.minusDays(5)).grant();
        assertThat(decide(VIEWER, Scope.vitals, dead, live).allowed()).isTrue();
    }

    @Test
    void vitalsNeverOpensRecords() {
        G b = G.base();
        Set<Scope> everyOther = EnumSet.complementOf(EnumSet.of(Scope.records));
        Grant g = new G(null, b.email(), everyOther, b.starts(), null, null, b.created()).grant();
        assertThat(decide(VIEWER, Scope.records, g).allowed()).isFalse();
    }
}
