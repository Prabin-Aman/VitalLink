// Mobile building blocks shared by the 390-wide frames (e.g. Workouts 134:2,
// Profile 145:88). Everything takes token names; nothing is a raw colour.
import { TONE_BG, type AvatarTone, type BarRowVM, type ChipVM, type StatVM } from "@vitallink/api";
import type { ColorToken, TextStyle } from "@vitallink/tokens";
import { useRouter } from "expo-router";
import type { ReactNode } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type TextStyle as RNTextStyle, type ViewStyle } from "react-native";
import { c, shadow, t } from "../theme";
import { Header } from "./Header";
import { Icon, type IconName } from "./Icon";
import { Dot } from "./ui";

export function Txt({ s, c: color, style, children, lines }: { s: TextStyle; c?: ColorToken; style?: StyleProp<RNTextStyle>; children: ReactNode; lines?: number }) {
  return (
    <Text style={[t(s, color ?? "textPrimary"), style]} numberOfLines={lines}>
      {children}
    </Text>
  );
}

/** Horizontal chip row in a header (Categories frames: 7/14, Label/Medium, gap 8). */
export function ChipRow({ options, value, onChange, label }: { options: string[]; value: string; onChange: (v: string) => void; label: string }) {
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={st.chips} accessibilityLabel={label} style={st.chipScroll}>
      {options.map((o) => {
        const on = o === value;
        return (
          <Pressable key={o} accessibilityRole="tab" accessibilityState={{ selected: on }} onPress={() => onChange(o)} style={[st.chip, on && st.chipOn]}>
            <Text style={t("labelM", on ? "textOnBrand" : "textSecondary")}>{o}</Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

/** "Add" pill in the title row (52×32, brand-subtle). */
export function AddPill({ onPress }: { onPress?: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel="Add" onPress={onPress} style={st.add}>
      <Text style={t("labelM", "textBrand")}>Add</Text>
    </Pressable>
  );
}

/**
 * Screen scaffold: header (title, caption, optional back / Add / chips) over a
 * 16-padded scroll column with 16 gaps.
 */
export function Screen({
  title,
  subtitle,
  back,
  add,
  chips,
  children,
  gap = 16,
}: {
  title: string;
  subtitle?: string;
  back?: string;
  /** Shows the Add pill; a function makes it open that screen's entry sheet. */
  add?: boolean | (() => void);
  chips?: ReactNode;
  children: ReactNode;
  gap?: number;
}) {
  const router = useRouter();
  return (
    <View style={st.screen}>
      <Header
        title={title}
        subtitle={subtitle}
        onBack={back ? () => (router.canGoBack() ? router.back() : router.replace(back as never)) : undefined}
        trailing={add ? <AddPill onPress={typeof add === "function" ? add : undefined} /> : undefined}
      >
        {chips}
      </Header>
      <ScrollView contentContainerStyle={[st.content, { gap }]}>{children}</ScrollView>
    </View>
  );
}

/** Mobile stat card (Stat / * 173×104: 16 padding, gap 6, 7px dot, Metric/M). */
export function StatTile({ stat }: { stat: StatVM }) {
  return (
    <View style={[st.card, st.stat]} accessibilityLabel={`${stat.label}: ${stat.value} ${stat.unit}, ${stat.caption}`}>
      <View style={st.row6}>
        <Dot color={stat.accent} size={7} />
        <Text style={t("labelM", "textSecondary")}>{stat.label}</Text>
      </View>
      <View style={st.baseline}>
        <Text style={t("metricM", "textPrimary")}>{stat.value}</Text>
        {stat.unit ? <Text style={t("metricUnit", "textTertiary")}>{stat.unit}</Text> : null}
      </View>
      <Text style={t("caption", "textTertiary")}>{stat.caption}</Text>
    </View>
  );
}

/** Two-up wrap of stat tiles, 12 apart. */
export function StatGrid({ stats }: { stats: StatVM[] }) {
  return (
    <View style={st.grid}>
      {stats.map((s) => (
        <View key={s.key} style={st.cell}>
          <StatTile stat={s} />
        </View>
      ))}
    </View>
  );
}

/** Card with an H5 title (Recent sessions, Macros…). */
export function Section({ title, action, children, gap = 10, style }: { title?: string; action?: ReactNode; children?: ReactNode; gap?: number; style?: StyleProp<ViewStyle> }) {
  return (
    <View style={[st.card, st.section, { gap }, style]}>
      {title ? (
        <View style={st.sectionHead}>
          <Text style={[t("h5", "textPrimary"), st.flex]} accessibilityRole="header">
            {title}
          </Text>
          {action}
        </View>
      ) : null}
      {children}
    </View>
  );
}

/** A list row inside a Section: top rule, 10–11px vertical padding. */
export function Row({ children, onPress, style, pad = 10 }: { children: ReactNode; onPress?: () => void; style?: StyleProp<ViewStyle>; pad?: number }) {
  const body = <View style={[st.row, { paddingVertical: pad }, style]}>{children}</View>;
  return onPress ? (
    <Pressable accessibilityRole="button" onPress={onPress} style={({ pressed }) => pressed && st.pressed}>
      {body}
    </Pressable>
  ) : (
    body
  );
}

/** Icon tile (Type / Doc frames: subtle fill). */
export function Tile({ icon, color = "iconDefault", size = 34, radius = 9 }: { icon: IconName; color?: ColorToken; size?: number; radius?: number }) {
  return (
    <View style={[st.tile, { width: size, height: size, borderRadius: radius }]}>
      <Icon name={icon} color={color} />
    </View>
  );
}

/** Menu row (Profile / Settings 145:88): 32px tile, label, value, chevron. */
export function MenuRow({ icon, label, value, onPress, iconColor = "iconDefault", danger }: { icon: IconName; label: string; value?: string; onPress?: () => void; iconColor?: ColorToken; danger?: boolean }) {
  return (
    <Row onPress={onPress} pad={11} style={st.gap12}>
      <Tile icon={icon} color={iconColor} size={32} />
      <Text style={[t("bodyM", danger ? "textCritical" : "textPrimary"), st.flex]}>{label}</Text>
      {value ? <Text style={t("caption", "textTertiary")}>{value}</Text> : null}
      <Icon name="chevron-right-16" color="iconSubtle" />
    </Row>
  );
}

/** Label/value over a 7px track (B / * frames). */
export function BarRow({ row, short = true }: { row: BarRowVM; short?: boolean }) {
  return (
    <View style={st.bar} accessibilityLabel={`${row.label}: ${short ? row.shortValue : row.value}`}>
      <View style={st.barHead}>
        <Text style={[t("bodySStrong", "textPrimary"), st.flex]}>{row.label}</Text>
        <Text style={t("caption", "textTertiary")}>{short ? row.shortValue : row.value}</Text>
      </View>
      <Track fraction={row.fraction} color={row.color} />
    </View>
  );
}

export function Track({ fraction, color, height = 7 }: { fraction: number; color: ColorToken; height?: number }) {
  return (
    <View style={[st.track, { height }]}>
      <View style={[st.fill, { width: `${Math.max(0, Math.min(1, fraction)) * 100}%`, backgroundColor: c[color] }]} />
    </View>
  );
}

/** Status pill with dot (Pill frames: 3/10/3/9, Label/Medium). */
export function Pill({ chip, dot = true }: { chip: ChipVM; dot?: boolean }) {
  return (
    <View style={[st.pill, { backgroundColor: c[chip.bg] }]}>
      {dot ? <Dot color={chip.fg} size={6} /> : null}
      <Text style={t("labelM", chip.fg)}>{chip.label}</Text>
    </View>
  );
}

/** Small uppercase tag (Tag frames: 2/8, Label/Small). */
export function Tag({ chip }: { chip: ChipVM }) {
  return (
    <View style={[st.tag, { backgroundColor: c[chip.bg] }]}>
      <Text style={t("labelS", chip.fg)}>{chip.label}</Text>
    </View>
  );
}

export function Avatar({ initials, tone = "brand", size = 36 }: { initials: string; tone?: AvatarTone; size?: number }) {
  const font = size >= 50 ? 18 : size >= 34 ? 13 : 11;
  return (
    <View style={[st.avatar, { width: size, height: size, borderRadius: size / 2, backgroundColor: c[TONE_BG[tone]] }]} accessibilityElementsHidden>
      <Text style={{ fontFamily: "Inter_600SemiBold", fontSize: font, color: c[tone === "neutral" ? "textSecondary" : "textOnBrand"] }}>{initials}</Text>
    </View>
  );
}

/** Compact outlined / filled button (Action frames: 8–9px vertical, Label/Medium). */
export function SmallButton({ label, onPress, primary, style, large }: { label: string; onPress?: () => void; primary?: boolean; style?: StyleProp<ViewStyle>; large?: boolean }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [st.small, large && st.smallLarge, primary ? { backgroundColor: c[pressed ? "bgBrandHover" : "bgBrand"], borderColor: c.bgBrand } : pressed && { backgroundColor: c.bgSubtle }, style]}
    >
      <Text style={t(large ? "labelL" : "labelM", primary ? "textOnBrand" : "textPrimary")}>{label}</Text>
    </Pressable>
  );
}

/** Label / value lines (Mindfulness, Aerobic capacity…). */
export function KeyValues({ rows }: { rows: { label: string; value: string; color?: ColorToken }[] }) {
  return (
    <View style={st.kv}>
      {rows.map((r) => (
        <View key={r.label} style={st.kvRow}>
          <Text style={[t("bodyS", "textSecondary"), st.flex]}>{r.label}</Text>
          <Text style={t("bodySStrong", r.color ?? "textPrimary")}>{r.value}</Text>
        </View>
      ))}
    </View>
  );
}

export function Legend({ items }: { items: { label: string; color: ColorToken }[] }) {
  return (
    <View style={st.legend}>
      {items.map((l) => (
        <View key={l.label} style={st.row6}>
          <Dot color={l.color} size={7} />
          <Text style={t("caption", "textSecondary")}>{l.label}</Text>
        </View>
      ))}
    </View>
  );
}

export const st = StyleSheet.create({
  screen: { flex: 1, backgroundColor: c.bgCanvas },
  content: { padding: 16, paddingBottom: 24 },
  flex: { flex: 1 },
  chipScroll: { marginHorizontal: -16 },
  chips: { gap: 8, paddingHorizontal: 16 },
  chip: { paddingHorizontal: 13, paddingVertical: 6, borderRadius: 999, borderWidth: 1, borderColor: c.borderDefault, backgroundColor: c.bgSurface },
  chipOn: { backgroundColor: c.bgBrand, borderColor: c.bgBrand },
  add: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 999, backgroundColor: c.bgBrandSubtle },
  card: { backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle, borderRadius: 12, ...shadow.card },
  stat: { padding: 16, gap: 6 },
  row6: { flexDirection: "row", alignItems: "center", gap: 6 },
  baseline: { flexDirection: "row", alignItems: "baseline", gap: 4 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
  cell: { flexBasis: "40%", flexGrow: 1 },
  section: { padding: 16 },
  sectionHead: { flexDirection: "row", alignItems: "center", gap: 8 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, borderTopWidth: 1, borderTopColor: c.borderSubtle },
  gap12: { gap: 12 },
  pressed: { opacity: 0.7 },
  tile: { backgroundColor: c.bgSubtle, alignItems: "center", justifyContent: "center" },
  bar: { gap: 6 },
  barHead: { flexDirection: "row", alignItems: "baseline", gap: 8 },
  track: { borderRadius: 999, backgroundColor: c.goalTrackBg, overflow: "hidden" },
  fill: { height: "100%", borderRadius: 999 },
  pill: { flexDirection: "row", alignItems: "center", gap: 6, paddingLeft: 9, paddingRight: 10, paddingVertical: 3, borderRadius: 999, alignSelf: "flex-start" },
  tag: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 999, alignSelf: "flex-start" },
  avatar: { alignItems: "center", justifyContent: "center" },
  small: { alignItems: "center", justifyContent: "center", paddingVertical: 8, paddingHorizontal: 12, borderRadius: 8, borderWidth: 1, borderColor: c.borderDefault, backgroundColor: c.bgSurface },
  smallLarge: { paddingVertical: 12 },
  kv: { gap: 12 },
  kvRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  legend: { flexDirection: "row", flexWrap: "wrap", gap: 14 },
});
