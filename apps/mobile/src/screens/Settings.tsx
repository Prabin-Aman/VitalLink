// Settings — Mobile 390 (Figma 151:1098). Sub-pages without a mobile frame stay put.
import { loadDevices, loadSettings, type VitalLinkClient } from "@vitallink/api";
import { useRouter } from "expo-router";
import { Alert, Platform, Text } from "react-native";
import { MenuRow, Screen, Section } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useApi, useScreen } from "../data";
import { t } from "../theme";

async function load(api: VitalLinkClient) {
  const [settings, devices] = await Promise.all([loadSettings(api), loadDevices(api).catch(() => null)]);
  return { settings, devices };
}

/** GET /me/export. The web build saves the file; the phone apps need a share sheet that isn't built yet. */
async function exportData(api: VitalLinkClient) {
  if (Platform.OS !== "web") {
    Alert.alert("Export my data", "Download your export from the VitalLink web portal (My Health › Export Data) for now.");
    return;
  }
  try {
    const file = await api.exportData();
    if (!file) return window.alert("Export works when the app is connected to your account.");
    const url = URL.createObjectURL(file.blob);
    const a = Object.assign(document.createElement("a"), { href: url, download: file.filename });
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    window.alert(e instanceof Error ? e.message : String(e));
  }
}

export function SettingsScreen() {
  const api = useApi();
  const state = useScreen(load);
  const router = useRouter();
  if (state.status !== "ready") return <ScreenState state={state} />;
  const { settings: s, devices } = state.data;

  return (
    <Screen title="Settings" subtitle="Account, appearance and data" back="/profile">
      <Section gap={6}>
        <Text style={t("labelS", "textTertiary")}>Account</Text>
        <MenuRow icon="user-16" label="Profile" value={s.summary.profile} onPress={() => router.navigate("/profile" as never)} />
        <MenuRow icon="privacy-16" label="Security" value={s.summary.security} />
        <MenuRow icon="notifications-16" label="Notifications" onPress={() => router.push("/profile/notifications" as never)} />
      </Section>
      <Section gap={6}>
        <Text style={t("labelS", "textTertiary")}>Preferences</Text>
        <MenuRow icon="moon-16" label="Appearance" value={s.summary.appearance} />
        <MenuRow icon="library-16" label="Language" value={s.summary.language} />
        <MenuRow icon="scale-16" label="Units" value={s.summary.units} />
      </Section>
      <Section gap={6}>
        <Text style={t("labelS", "textTertiary")}>Data</Text>
        <MenuRow icon="download-16" label="Export my data" onPress={() => exportData(api)} />
        <MenuRow icon="devices-16" label="Connected Devices" value={devices?.shortSubtitle.split(" · ")[0]} onPress={() => router.push("/profile/devices" as never)} />
        <MenuRow icon="emergency-16" iconColor="textCritical" label="Delete account" danger />
      </Section>
    </Screen>
  );
}
