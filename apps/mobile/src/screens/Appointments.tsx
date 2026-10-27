// Appointments — Mobile 390 (Figma 150:124).
import { loadAppointments } from "@vitallink/api";
import { useState } from "react";
import { Linking, StyleSheet, Text, View } from "react-native";
import { ChipRow, Pill, Screen, Section, SmallButton } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { c, t } from "../theme";

const CHIPS = ["Upcoming", "Past", "Cancelled"];

export function AppointmentsScreen() {
  const state = useScreen(loadAppointments);
  const [tab, setTab] = useState(CHIPS[0]);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const items = v.items.filter((a) => a.group === tab);

  return (
    <Screen title="Appointments" subtitle={v.shortSubtitle} back="/profile" chips={<ChipRow label="Appointment filter" options={CHIPS} value={tab} onChange={setTab} />}>
      {items.length === 0 && <Text style={t("bodyM", "textTertiary")}>No {tab.toLowerCase()} appointments.</Text>}
      {items.map((a) => (
        <Section key={a.id} gap={12}>
          <View style={st.top}>
            <View style={st.date}>
              <Text style={t("labelS", "textBrand")}>{a.weekday}</Text>
              <Text style={t("h4", "textBrand")}>{a.day}</Text>
              <Text style={t("caption", "textBrand")}>{a.month}</Text>
            </View>
            <View style={st.flex}>
              <Text style={t("bodyMStrong", "textPrimary")}>{a.name}</Text>
              <Text style={t("caption", "textSecondary")}>{a.line1}</Text>
              <Text style={t("caption", "textTertiary")} numberOfLines={1}>{a.line2}</Text>
            </View>
          </View>
          <Pill chip={{ ...a.status, label: a.shortStatus }} />
          {a.group === "Upcoming" && (
            <View style={st.actions}>
              <SmallButton label={a.mobilePrimary} primary style={st.flex} onPress={a.mapsUrl ? () => Linking.openURL(a.mapsUrl!) : undefined} />
              <SmallButton label="Reschedule" style={st.flex} />
            </View>
          )}
        </Section>
      ))}
    </Screen>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: "row", alignItems: "center", gap: 12 },
  date: { width: 58, height: 58, borderRadius: 12, alignItems: "center", justifyContent: "center", gap: 1, backgroundColor: c.bgBrandSubtle },
  flex: { flex: 1, gap: 4 },
  actions: { flexDirection: "row", gap: 8 },
});
