// Entry sheets — Figma "15 — Proposed · Entry forms" › Mobile sheets (198:317): handle, overline, H4 title,
// Form / Field (46px input), Form / Choice chips, full-width primary action and a Cancel link.
// Rules about values live in packages/api/src/forms.ts; these parts only bind inputs.
import { addDays, dateLabel, dateOptions, timeLabel, timeOptions, todayIn, type Option } from "@vitallink/api";
import type { ColorToken } from "@vitallink/tokens";
import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  FlatList,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type KeyboardTypeOptions,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { notifyChanged, useApi } from "../data";
import { c, t } from "../theme";
import { Icon, type IconName } from "./Icon";
import { Button } from "./ui";

/** Thrown by a sheet's `save` to show a message instead of closing (validation or an API error). */
export class FormProblem extends Error {}

export function must<T>(r: { ok: true; body: T } | { ok: false; error: string }): T {
  if (!r.ok) throw new FormProblem(r.error);
  return r.body;
}

/** The account's time zone (dates and times in sheets are in it), or null until /me has loaded. */
export function useZone(): string | null {
  const api = useApi();
  const [tz, setTz] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api.me().then(
      (m) => live && setTz(m.timezone),
      () => live && setTz(Intl.DateTimeFormat().resolvedOptions().timeZone),
    );
    return () => {
      live = false;
    };
  }, [api]);
  return tz;
}

/** A bottom sheet over a scrim. Same contract as the web FormDialog: mock mode checks but saves nothing. */
export function FormSheet({
  overline,
  title,
  subtitle,
  action,
  onClose,
  save,
  children,
}: {
  overline: string;
  title: string;
  subtitle: string;
  action: string;
  onClose: () => void;
  save: () => Promise<void>;
  children: ReactNode;
}) {
  const api = useApi();
  const insets = useSafeAreaInsets();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [demoDone, setDemoDone] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await save();
      if (api.isMock) setDemoDone(true);
      else {
        notifyChanged();
        onClose();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const close = () => !busy && onClose();

  return (
    <Modal transparent animationType="slide" visible onRequestClose={close}>
      <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={st.backdrop}>
        <Pressable accessibilityLabel="Close" style={StyleSheet.absoluteFill} onPress={close} />
        <View style={[st.sheet, { paddingBottom: insets.bottom + 8 }]} accessibilityViewIsModal>
          <View style={st.handle} />
          <ScrollView contentContainerStyle={st.body} keyboardShouldPersistTaps="handled">
            <View style={st.head}>
              <Text style={[t("overline", "textTertiary"), st.upper]}>{overline}</Text>
              <Text style={t("h4", "textPrimary")} accessibilityRole="header">
                {title}
              </Text>
              <Text style={t("bodyS", "textSecondary")}>{subtitle}</Text>
            </View>
            {children}
            {error && (
              <Text accessibilityRole="alert" style={t("bodyS", "textCritical")}>
                {error}
              </Text>
            )}
            {demoDone && (
              <Text accessibilityLiveRegion="polite" style={t("bodyS", "textSecondary")}>
                Everything checks out, but this is demo data, so nothing was saved. Connect the app to your account to save.
              </Text>
            )}
            {!demoDone && <Button label={busy ? "Saving…" : action} onPress={busy ? undefined : submit} />}
            <Pressable accessibilityRole="button" onPress={close} style={st.cancel}>
              <Text style={t("labelL", "textLink")}>{demoDone ? "Close" : "Cancel"}</Text>
            </Pressable>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

// ---------------------------------------------------------------- fields

function Shell({ label, helper, children }: { label: string; helper?: string; children: ReactNode }) {
  return (
    <View style={st.field}>
      <Text style={t("labelL", "textPrimary")}>{label}</Text>
      {children}
      {helper ? <Text style={t("caption", "textTertiary")}>{helper}</Text> : null}
    </View>
  );
}

export function Field({
  label,
  value,
  onChange,
  placeholder,
  helper,
  icon,
  keyboard,
  autoFocus,
  multiline,
  max,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  helper?: string;
  icon?: IconName;
  keyboard?: KeyboardTypeOptions;
  autoFocus?: boolean;
  multiline?: boolean;
  max?: number;
}) {
  const [focus, setFocus] = useState(false);
  return (
    <Shell label={label} helper={helper}>
      <View style={[st.input, focus && st.focus, multiline && st.area]}>
        {icon && <Icon name={icon} color="iconSubtle" />}
        <TextInput
          accessibilityLabel={label}
          value={value}
          onChangeText={onChange}
          placeholder={placeholder}
          placeholderTextColor={c.textPlaceholder}
          keyboardType={keyboard}
          autoFocus={autoFocus}
          multiline={multiline}
          maxLength={max}
          onFocus={() => setFocus(true)}
          onBlur={() => setFocus(false)}
          style={[t("bodyM", "textPrimary"), st.control, multiline && st.areaControl]}
        />
      </View>
    </Shell>
  );
}

/** A field that opens a list to pick from (selects, dates and times — the app has no native pickers). */
export function PickField({
  label,
  value,
  options,
  onChange,
  icon,
  helper,
  placeholder,
  display,
}: {
  label: string;
  value: string;
  options: Option[];
  onChange: (v: string) => void;
  icon?: IconName;
  helper?: string;
  placeholder?: string;
  /** Text shown in the field; defaults to the picked option's label. */
  display?: string;
}) {
  const [open, setOpen] = useState(false);
  const shown = display ?? options.find((o) => o.value === value)?.label ?? "";
  return (
    <Shell label={label} helper={helper}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${label}: ${shown || placeholder || "not set"}`} onPress={() => setOpen(true)} style={st.input}>
        {icon && <Icon name={icon} color="iconSubtle" />}
        <Text style={[t("bodyM", shown ? "textPrimary" : "textPlaceholder"), st.control, st.pickText]} numberOfLines={1}>
          {shown || placeholder}
        </Text>
        {!icon && (
          <View style={st.chevron}>
            <Icon name="chevron-right-16" color="iconSubtle" />
          </View>
        )}
      </Pressable>
      {open && <OptionSheet title={label} options={options} value={value} onPick={onChange} onClose={() => setOpen(false)} />}
    </Shell>
  );
}

const ROW = 46;

/** The list a PickField (or "+ Add time") opens, scrolled to the current value. */
export function OptionSheet({ title, options, value, onPick, onClose }: { title: string; options: Option[]; value?: string; onPick: (v: string) => void; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const list = useRef<FlatList<Option>>(null);
  const at = Math.max(0, options.findIndex((o) => o.value === value));
  return (
    <Modal transparent animationType="fade" visible onRequestClose={onClose}>
      <View style={st.backdrop}>
        <Pressable accessibilityLabel="Close" style={StyleSheet.absoluteFill} onPress={onClose} />
        <View style={[st.sheet, st.options, { paddingBottom: insets.bottom + 8 }]}>
          <View style={st.handle} />
          <Text style={[t("h5", "textPrimary"), st.optionsTitle]}>{title}</Text>
          <FlatList
            ref={list}
            data={options}
            keyExtractor={(o) => o.value || "none"}
            initialScrollIndex={at > 3 ? at - 3 : 0}
            getItemLayout={(_, i) => ({ length: ROW, offset: ROW * i, index: i })}
            renderItem={({ item }) => {
              const on = item.value === value;
              return (
                <Pressable
                  accessibilityRole="button"
                  accessibilityState={{ selected: on }}
                  onPress={() => {
                    onPick(item.value);
                    onClose();
                  }}
                  style={({ pressed }) => [st.option, on && st.optionOn, pressed && !on && { backgroundColor: c.bgSubtle }]}
                >
                  <Text style={t(on ? "bodyMStrong" : "bodyM", on ? "textBrand" : "textPrimary")}>{item.label}</Text>
                </Pressable>
              );
            }}
          />
        </View>
      </View>
    </Modal>
  );
}

/** A date in the account's zone. `past` limits it to the last year, otherwise the next year from today. */
export function DateField({ label, value, onChange, tz, past, optional, from, helper }: { label: string; value: string; onChange: (v: string) => void; tz: string; past?: boolean; optional?: boolean; from?: string; helper?: string }) {
  const today = todayIn(tz);
  const start = from ?? (past ? addDays(today, -365) : today);
  const options = dateOptions(start, past ? today : addDays(start, 365), today);
  return (
    <PickField
      label={label}
      icon="appointments-16"
      value={value}
      options={optional ? [{ value: "", label: "No end date" }, ...options] : options}
      onChange={onChange}
      display={value ? dateLabel(value, !past) : ""}
      placeholder={optional ? "Optional" : "Pick a date"}
      helper={helper}
    />
  );
}

export function TimeField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return <PickField label={label} icon="clock-16" value={value} options={timeOptions(15, value)} onChange={onChange} display={value ? timeLabel(value) : ""} />;
}

// ---------------------------------------------------------------- choices, rows, notes, lists

/** Form / Choice chips; `round` draws the 36px day circles. */
export function Choices({
  label,
  options,
  selected,
  onChange,
  multiple,
  round,
  extra,
}: {
  label: string;
  options: Option[];
  selected: string[];
  onChange: (next: string[]) => void;
  multiple?: boolean;
  round?: boolean;
  extra?: ReactNode;
}) {
  const toggle = (v: string) => {
    if (!multiple) return onChange([v]);
    onChange(selected.includes(v) ? selected.filter((x) => x !== v) : [...selected, v]);
  };
  return (
    <Shell label={label}>
      <View style={st.choices} accessibilityRole={multiple ? undefined : "radiogroup"}>
        {options.map((o) => {
          const on = selected.includes(o.value);
          return (
            <Pressable
              key={o.value}
              accessibilityRole={multiple ? "checkbox" : "radio"}
              accessibilityState={multiple ? { checked: on } : { selected: on }}
              accessibilityLabel={o.label}
              onPress={() => toggle(o.value)}
              style={[st.choice, round && st.round, on && st.choiceOn]}
            >
              <Text style={t("labelL", on ? "textBrand" : "textSecondary")}>{round ? o.label.charAt(0) : o.label}</Text>
            </Pressable>
          );
        })}
        {extra}
      </View>
    </Shell>
  );
}

/** A chip-shaped action next to choices ("+ Add time"). */
export function ChipAction({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={st.choice}>
      <Text style={t("labelL", "textSecondary")}>{label}</Text>
    </Pressable>
  );
}

export function Row({ children }: { children: ReactNode }) {
  return <View style={st.row}>{children}</View>;
}
/** Takes one column of a Row when the other side is empty. */
export const Spacer = () => <View style={st.flex} />;

export function Note({ children, color = "textSecondary" }: { children: ReactNode; color?: ColorToken }) {
  return (
    <View style={st.note}>
      <Text style={t("bodyS", color)}>{children}</Text>
    </View>
  );
}

export function LinkAction({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} hitSlop={8} style={st.link}>
      <Text style={t("labelM", "textLink")}>{label}</Text>
    </Pressable>
  );
}

export function ItemList({ label, empty, items, add }: { label: string; empty: string; items: { key: string; title: string; caption: string; onRemove: () => void }[]; add?: ReactNode }) {
  return (
    <Shell label={label}>
      <View style={st.items}>
        {items.length === 0 ? (
          <Text style={[t("bodyS", "textTertiary"), st.item]}>{empty}</Text>
        ) : (
          items.map((x, i) => (
            <View key={x.key} style={[st.item, i > 0 && st.itemLine]}>
              <View style={st.flex}>
                <Text style={t("bodyMStrong", "textPrimary")}>{x.title}</Text>
                <Text style={t("caption", "textTertiary")}>{x.caption}</Text>
              </View>
              <Pressable accessibilityRole="button" accessibilityLabel={`Remove ${x.title}`} onPress={x.onRemove} hitSlop={8}>
                <Text style={t("labelM", "textLink")}>Remove</Text>
              </Pressable>
            </View>
          ))
        )}
      </View>
      {add}
    </Shell>
  );
}

const st = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: "flex-end", backgroundColor: c.bgOverlayScrim },
  sheet: { maxHeight: "92%", backgroundColor: c.bgSurface, borderTopLeftRadius: 16, borderTopRightRadius: 16 },
  handle: { alignSelf: "center", width: 36, height: 4, borderRadius: 999, backgroundColor: c.borderStrong, marginTop: 8 },
  body: { padding: 16, paddingTop: 16, gap: 16 },
  head: { gap: 4 },
  upper: { textTransform: "uppercase" },
  cancel: { alignItems: "center", paddingVertical: 4 },
  field: { gap: 7, flex: 1, minWidth: 0 },
  input: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    minHeight: 46,
    paddingHorizontal: 14,
    borderWidth: 1,
    borderColor: c.borderDefault,
    borderRadius: 8,
    backgroundColor: c.bgCanvas,
  },
  focus: { borderColor: c.borderFocus },
  control: { flex: 1, minWidth: 0, paddingVertical: 11 },
  pickText: { paddingVertical: 12 },
  area: { alignItems: "flex-start" },
  areaControl: { minHeight: 88, textAlignVertical: "top" },
  chevron: { transform: [{ rotate: "90deg" }] },
  options: { maxHeight: "70%" },
  optionsTitle: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 },
  option: { height: ROW, justifyContent: "center", paddingHorizontal: 16 },
  optionOn: { backgroundColor: c.bgBrandSubtle },
  choices: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  choice: { paddingVertical: 7, paddingHorizontal: 13, borderWidth: 1, borderColor: c.borderDefault, borderRadius: 999, backgroundColor: c.bgCanvas },
  round: { width: 36, height: 36, paddingVertical: 0, paddingHorizontal: 0, alignItems: "center", justifyContent: "center" },
  choiceOn: { borderColor: c.borderBrand, backgroundColor: c.bgBrandSubtle },
  row: { flexDirection: "row", gap: 12 },
  flex: { flex: 1, gap: 2 },
  note: { paddingVertical: 12, paddingHorizontal: 14, borderRadius: 8, backgroundColor: c.bgSubtle },
  link: { alignSelf: "flex-start", paddingHorizontal: 12, paddingVertical: 4 },
  items: { borderWidth: 1, borderColor: c.borderSubtle, borderRadius: 8, backgroundColor: c.bgCanvas },
  item: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 10, paddingHorizontal: 14 },
  itemLine: { borderTopWidth: 1, borderTopColor: c.borderSubtle },
});
