package com.vitallink.api.sharing;

import com.vitallink.api.account.AccountService;
import com.vitallink.api.account.AccountService.PersonInfo;
import com.vitallink.api.account.AccountService.Profile;
import com.vitallink.api.account.CurrentUser;
import com.vitallink.api.care.CareDtos.ConditionKind;
import com.vitallink.api.care.CareService;
import com.vitallink.api.core.Num;
import com.vitallink.api.core.audit.Audit.Scope;
import com.vitallink.api.core.consent.Consent.Viewer;
import com.vitallink.api.core.consent.ConsentGate;
import com.vitallink.api.core.consent.ConsentGates;
import com.vitallink.api.health.HealthService;
import com.vitallink.api.sharing.SharingDtos.GranteeKind;
import com.vitallink.api.sharing.SharingService.ConsentRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Family (decision D3): me, then everyone who shares with me. Each summary is read through the consent
 * gate, so it's audited in their access log and only holds what their share covers.
 */
@Service
public class FamilyService {

    public record ScopeAccess(String label, String shortLabel, boolean granted) {}

    public record Access(String level, OffsetDateTime grantedAt, List<ScopeAccess> scopes) {}

    public record ConditionSummary(String label, String severity) {}

    /** Fields outside the share's scopes are null (or empty). */
    public record Summary(
            Integer restingHr,
            List<ConditionSummary> conditions,
            Integer medications,
            OffsetDateTime nextAppointment) {}

    /** {@code FamilyMember} in types.ts. */
    public record FamilyMember(
            UUID id, String name, String relation, Integer age, String tone, Access access, Summary summary) {}

    static final List<Object[]> SCOPES = List.of(
            new Object[] {Scope.vitals, "Vitals and health readings", "Vitals and readings"},
            new Object[] {Scope.activity, "Activity and workouts", "Activity"},
            new Object[] {Scope.sleep, "Sleep", "Sleep"},
            new Object[] {Scope.nutrition, "Nutrition", "Nutrition"},
            new Object[] {Scope.records, "Records, medications and appointments", "Records and medications"});

    static final Map<GranteeKind, String> RELATION =
            Map.of(GranteeKind.family, "Family", GranteeKind.doctor, "Patient", GranteeKind.other, "Shares with you");

    private final SharingService sharing;
    private final AccountService accounts;
    private final ConsentGates gates;
    private final HealthService health;
    private final CareService care;

    public FamilyService(
            SharingService sharing,
            AccountService accounts,
            ConsentGates gates,
            HealthService health,
            CareService care) {
        this.sharing = sharing;
        this.accounts = accounts;
        this.gates = gates;
        this.health = health;
        this.care = care;
    }

    static Access access(ConsentRow c) {
        List<ScopeAccess> scopes = SCOPES.stream()
                .map(s ->
                        new ScopeAccess((String) s[1], (String) s[2], c.scopes().contains((Scope) s[0])))
                .toList();
        String level = scopes.stream().allMatch(ScopeAccess::granted) ? "full" : "partial";
        return new Access(level, c.startsAt(), scopes);
    }

    /** What the gate lets this viewer see of the owner; null when no summary scope is shared. */
    public Summary summary(ConsentGate gate, ConsentRow share, String ownerTz) {
        boolean vitals = share.scopes().contains(Scope.vitals) && gate.allows(Scope.vitals);
        boolean records = share.scopes().contains(Scope.records) && gate.allows(Scope.records);
        if (!vitals && !records) {
            return null;
        }
        UUID owner = gate.ownerId();
        Integer resting = vitals ? Num.roundInt(health.latestValue(owner, "resting_heart_rate")) : null;
        List<ConditionSummary> conditions = records
                ? care.conditions(owner).stream()
                        .map(c -> new ConditionSummary(
                                c.label()
                                        + (c.kind() == ConditionKind.allergy
                                                        && !c.label()
                                                                .toLowerCase()
                                                                .contains("allerg")
                                                ? " allergy"
                                                : ""),
                                c.kind() == ConditionKind.allergy ? "allergy" : "chronic"))
                        .toList()
                : List.of();
        Integer medications = records ? care.activeMedicationCount(owner) : null;
        OffsetDateTime next = records
                ? care.nextAppointment(owner, ownerTz).map(a -> a.startsAt()).orElse(null)
                : null;
        return new Summary(resting, conditions, medications, next);
    }

    public List<FamilyMember> family(CurrentUser me, String requestId) {
        LocalDate today = LocalDate.now(me.zone());
        Profile mine = accounts.profile(me.id());
        List<FamilyMember> out = new ArrayList<>();
        out.add(new FamilyMember(
                me.id(),
                mine.displayName() != null ? mine.displayName() : me.user().email(),
                "You",
                mine.ageOn(today),
                "brand",
                null,
                null));
        List<ConsentRow> received = sharing.receivedLive(me);
        Map<UUID, PersonInfo> owners =
                accounts.people(received.stream().map(ConsentRow::grantorId).collect(Collectors.toSet()));
        Viewer viewer = new Viewer(me.id(), me.user().email(), me.user().emailConfirmedAt() != null);
        for (ConsentRow c : received) {
            PersonInfo owner = owners.get(c.grantorId());
            ConsentGate gate = gates.open(viewer, c.grantorId(), requestId);
            Profile p = accounts.profile(c.grantorId());
            out.add(new FamilyMember(
                    c.grantorId(),
                    owner.name() != null ? owner.name() : owner.email(),
                    RELATION.get(c.granteeKind()),
                    p.ageOn(LocalDate.now(ZoneId.of(owner.timezone()))),
                    "purple",
                    access(c),
                    summary(gate, c, owner.timezone())));
        }
        return out;
    }

    /** One person's family card (shared view). */
    public Summary summaryFor(CurrentUser me, ConsentGate gate) {
        ConsentRow share = sharing.receivedLive(me).stream()
                .filter(c -> c.grantorId().equals(gate.ownerId()))
                .findFirst()
                .orElse(null);
        if (share == null) {
            gate.require(Scope.vitals); // refuses (404 or 403) and audits, exactly like any shared read
        }
        PersonInfo owner = accounts.person(gate.ownerId());
        Summary s = summary(gate, share, owner.timezone());
        if (s == null) {
            gate.require(Scope.records); // nothing a summary can show is shared: refuse the same way
        }
        return s;
    }
}
