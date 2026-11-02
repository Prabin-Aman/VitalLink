// Emergency Medical ID — Mobile 390 (Figma 150:233).
import { loadEmergency } from "@vitallink/api";
import { useState } from "react";
import { Linking, Pressable, StyleSheet, Text, View } from "react-native";
import Svg, { Rect } from "react-native-svg";
import qr from "../../../../packages/assets/emergency-qr.json";
import { Icon } from "../components/Icon";
import { Avatar, Row, Screen, Section, SmallButton } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { EditEmergencySheet } from "../sheets/CareSheets";
import { c, t } from "../theme";

export function EmergencyScreen() {
  const state = useScreen(loadEmergency);
  const [showQr, setShowQr] = useState(false);
  const [editing, setEditing] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <Screen title="Emergency Medical ID" subtitle="Readable without unlocking your phone" back="/profile">
      <View style={st.card} accessibilityLabel="Emergency medical ID card">
        <View style={st.top}>
          <View style={st.alert}>
            <Icon name="emergency-26" color="textOnBrand" />
          </View>
          <View style={st.flex}>
            <Text style={t("labelS", "textEnergy")}>EMERGENCY MEDICAL ID</Text>
            <Text style={t("h2", "textInverse")}>{v.name}</Text>
            <Text style={t("caption", "textSecondary")}>{v.shortMeta}</Text>
          </View>
        </View>
        <View style={st.rule} />
        {v.fields
          .filter((f) => f.mobile)
          .map((f) => (
            <View key={f.key} style={st.field}>
              <Text style={t("labelS", "textTertiary")}>{f.label.toUpperCase()}</Text>
              <Text style={t("bodyMStrong", f.color)}>{f.shortValue}</Text>
            </View>
          ))}
      </View>

      <Section title="Emergency contacts" gap={8}>
        {/* Mobile lists family contacts; clinicians are on the web card. */}
        {v.contacts
          .filter((x) => !x.name.startsWith("Dr."))
          .map((x) => (
            <Row key={x.name} pad={12}>
              <Avatar initials={x.initials} tone={x.tone} size={34} />
              <View style={st.flex}>
                <Text style={t("bodySStrong", "textPrimary")}>{x.name}</Text>
                <Text style={t("caption", "textTertiary")}>{x.mobileCaption}</Text>
              </View>
              <Pressable accessibilityRole="button" accessibilityLabel={`Call ${x.name}`} onPress={() => Linking.openURL(`tel:${x.phone.replace(/\D/g, "")}`)} style={st.call}>
                <Text style={t("labelM", "textOnBrand")}>Call</Text>
              </Pressable>
            </Row>
          ))}
      </Section>

      {showQr && (
        <Section gap={12} style={st.qrCard}>
          <Svg width={104} height={104} viewBox="0 0 104 104" accessibilityLabel="Emergency QR code">
            <Rect width={104} height={104} rx={10} fill={c.bgSubtle} />
            {qr.cells.map(([x, y]) => (
              <Rect key={`${x}-${y}`} x={12 + x * 10} y={12 + y * 10} width={9} height={9} fill={c.textPrimary} />
            ))}
          </Svg>
          <Text style={[t("caption", "textSecondary"), st.center]}>Scanning shows this card only — no login, no access to the rest of your record.</Text>
        </Section>
      )}

      {editing && <EditEmergencySheet onClose={() => setEditing(false)} />}
      <View style={st.actions}>
        <SmallButton label={showQr ? "Hide QR" : "Show QR"} large style={st.flex} onPress={() => setShowQr(!showQr)} />
        <SmallButton label="Edit" large style={st.flex} onPress={() => setEditing(true)} />
      </View>
    </Screen>
  );
}

const st = StyleSheet.create({
  card: { gap: 16, paddingVertical: 22, paddingHorizontal: 20, borderRadius: 16, backgroundColor: c.bgInverse },
  top: { flexDirection: "row", alignItems: "center", gap: 14 },
  alert: { width: 52, height: 52, borderRadius: 14, alignItems: "center", justifyContent: "center", backgroundColor: c.bgEmergency },
  flex: { flex: 1, gap: 3 },
  rule: { height: 1, backgroundColor: c.borderStrong },
  field: { gap: 3 },
  call: { paddingHorizontal: 14, paddingVertical: 7, borderRadius: 8, backgroundColor: c.bgEmergency },
  qrCard: { alignItems: "center" },
  center: { textAlign: "center" },
  actions: { flexDirection: "row", gap: 8 },
});
