package com.vitallink.api.care;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.api.care.CareDtos.DoseForm;
import com.vitallink.api.care.CareDtos.DoseLogOut;
import com.vitallink.api.care.CareDtos.DoseStatus;
import com.vitallink.api.care.CareService.Med;
import com.vitallink.api.care.CareService.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The dose state rules, with a fixed clock: Sun 13 Sep 2026, 8:45 AM in Chicago (the design's "now"). */
class DoseStateTest {

    static final ZoneId TZ = ZoneId.of("America/Chicago");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    static final OffsetDateTime NOW = TODAY.atTime(8, 45).atZone(TZ).toOffsetDateTime();

    static Med med(LocalTime... times) {
        List<Slot> slots =
                java.util.Arrays.stream(times).map(t -> new Slot(t, 127)).toList();
        return new Med(
                UUID.randomUUID(),
                "Metformin",
                DoseForm.tablet,
                "500 mg",
                "",
                "",
                3,
                null,
                "active",
                TODAY.minusDays(30),
                null,
                slots);
    }

    static OffsetDateTime at(LocalDate day, int h, int m) {
        return day.atTime(h, m).atZone(TZ).toOffsetDateTime();
    }

    static Map<Instant, DoseLogOut> logged(OffsetDateTime... taken) {
        Map<Instant, DoseLogOut> m = new HashMap<>();
        for (OffsetDateTime t : taken) {
            m.put(t.toInstant(), new DoseLogOut(t, DoseStatus.taken, t));
        }
        return m;
    }

    @Test
    void takenThisMorningShowsTaken() {
        Med m = med(LocalTime.of(8, 0), LocalTime.of(20, 0));
        var logs = logged(at(TODAY, 8, 0), at(TODAY.minusDays(1), 20, 0));
        var state = CareService.doseState(m, logs, NOW, TZ);
        assertThat(state.status()).isEqualTo("taken");
        assertThat(state.at().toLocalTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void notYetTakenWithinTheGracePeriodIsDue() {
        Med m = med(LocalTime.of(8, 0));
        var state = CareService.doseState(m, logged(at(TODAY.minusDays(1), 8, 0)), NOW, TZ);
        assertThat(state.status()).isEqualTo("due");
        assertThat(state.at().toLocalDate()).isEqualTo(TODAY);
        assertThat(state.at().toLocalTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void laterDoseIsDue() {
        Med m = med(LocalTime.of(14, 0));
        var state = CareService.doseState(m, logged(at(TODAY.minusDays(1), 14, 0)), NOW, TZ);
        assertThat(state.status()).isEqualTo("due");
        assertThat(state.at().toLocalTime()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    void lastNightsDoseWithoutALogIsMissed() {
        Med m = med(LocalTime.of(21, 0));
        var state = CareService.doseState(m, Map.of(), NOW, TZ);
        assertThat(state.status()).isEqualTo("missed");
        assertThat(state.at().toLocalDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void aMissedDoseOlderThanADayNoLongerShows() {
        Med m = med(LocalTime.of(6, 0));
        // yesterday 06:00 is more than 24 h ago; today's 06:00 is past grace → missed today
        var state = CareService.doseState(m, logged(at(TODAY.minusDays(1), 6, 0)), NOW, TZ);
        assertThat(state.status()).isEqualTo("missed");
        assertThat(state.at().toLocalDate()).isEqualTo(TODAY);
        var taken = CareService.doseState(m, logged(at(TODAY, 6, 0)), NOW, TZ);
        assertThat(taken.status()).isEqualTo("taken");
    }

    @Test
    void weeklyMedicationIsDueOnItsDay() {
        // Wednesdays only (bit 2)
        Med m = new Med(
                UUID.randomUUID(),
                "Vitamin B12",
                DoseForm.injection,
                "1000 mcg",
                "",
                "",
                0,
                null,
                "active",
                TODAY.minusDays(30),
                null,
                List.of(new Slot(LocalTime.of(9, 0), 1 << 2)));
        var state = CareService.doseState(m, logged(at(TODAY.minusDays(4), 9, 0)), NOW, TZ);
        assertThat(state.status()).isEqualTo("due");
        assertThat(state.at().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    void frequencies() {
        assertThat(CareService.frequency(List.of(new Slot(LocalTime.NOON, 127))))
                .isEqualTo("Once daily");
        assertThat(CareService.frequency(
                        List.of(new Slot(LocalTime.of(8, 0), 127), new Slot(LocalTime.of(20, 0), 127))))
                .isEqualTo("Twice daily");
        assertThat(CareService.frequency(List.of(new Slot(LocalTime.NOON, 1)))).isEqualTo("Once weekly on Mon");
        assertThat(CareService.frequency(List.of(new Slot(LocalTime.NOON, 0b10101))))
                .isEqualTo("Once a day on Mon, Wed, Fri");
    }

    @Test
    void refillDueWithinAWeek() {
        Med soon = new Med(
                UUID.randomUUID(),
                "Ferrous Sulfate",
                DoseForm.tablet,
                "325 mg",
                "",
                "",
                0,
                TODAY.plusDays(3),
                "active",
                TODAY,
                null,
                List.of());
        Med later = new Med(
                UUID.randomUUID(),
                "Ferrous Sulfate",
                DoseForm.tablet,
                "325 mg",
                "",
                "",
                0,
                TODAY.plusDays(20),
                "active",
                TODAY,
                null,
                List.of());
        assertThat(CareService.refillDue(soon, TODAY)).isTrue();
        assertThat(CareService.refillDue(later, TODAY)).isFalse();
    }
}
