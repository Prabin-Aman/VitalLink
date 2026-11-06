// Building blocks shared by the portal screens. Measurements come from the
// Figma frames that use each pattern (e.g. Workouts 129:1142, Nutrition 131:100).
import type { BarRowVM, LegendVM, StatVM } from "@vitallink/api";
import type { ColorToken, TextStyle } from "@vitallink/tokens";
import type { CSSProperties, ElementType, ReactNode } from "react";
import { Link, NavLink } from "react-router-dom";
import { Icon, tokenVar, type IconName } from "./Icon";
import { AccentDot, Button } from "./ui";
import s from "./kit.module.css";

export const cx = (...c: (string | false | undefined | null)[]) => c.filter(Boolean).join(" ");

const kebab = (t: string) => t.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase());

/** Text in a Figma text style and colour token. */
export function Txt({
  s: style,
  c,
  as: Tag = "span",
  className,
  children,
  title,
}: {
  s: TextStyle;
  c?: ColorToken;
  as?: ElementType;
  className?: string;
  children: ReactNode;
  title?: string;
}) {
  return (
    <Tag className={cx(`vl-${kebab(style)}`, className)} style={c ? { color: tokenVar(c) } : undefined} title={title}>
      {children}
    </Tag>
  );
}

/** H1 + Body/Large subtitle, actions on the right (Heading frames, gap 16). */
export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className={s.header}>
      <div className={s.titles}>
        <h1 className="vl-h1">{title}</h1>
        {subtitle ? <Txt s="bodyL" c="textSecondary" as="p">{subtitle}</Txt> : null}
      </div>
      {actions ? <div className={s.actions}>{actions}</div> : null}
    </header>
  );
}

/** Header action: Label/Large, 10/20 padding (Browse Exercises / Log Workout). */
export function Action({ children, primary, compact, onClick }: { children: ReactNode; primary?: boolean; compact?: boolean; onClick?: () => void }) {
  return (
    <button type="button" className={cx("vl-label-l", s.action, primary ? s.actionPrimary : s.actionSecondary, compact && s.actionCompact)} onClick={onClick}>
      {children}
    </button>
  );
}

/** Pill tabs (Tabs frames: 9/16 padding, Label/Large, brand when active). */
export function PillTabs({
  tabs,
  value,
  onChange,
  label,
  small,
}: {
  tabs: string[];
  value: string;
  onChange: (t: string) => void;
  label: string;
  small?: boolean;
}) {
  return (
    <div className={s.tabs} role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button
          key={t}
          type="button"
          role="tab"
          aria-selected={t === value}
          className={cx(s.tab, small && s.tabSmall, t === value && s.tabActive)}
          onClick={() => onChange(t)}
        >
          {t}
        </button>
      ))}
    </div>
  );
}

/** Pill tabs that are routes (My Health tabs: 8/15, 36 tall). */
export function PillLinks({ tabs, label }: { tabs: { label: string; to: string }[]; label: string }) {
  return (
    <nav className={s.tabs} aria-label={label}>
      {tabs.map((t) => (
        <NavLink key={t.to} to={t.to} className={({ isActive }) => cx(s.tab, s.tabSmall, isActive && s.tabActive)}>
          {t.label}
        </NavLink>
      ))}
    </nav>
  );
}

/** Fixed-width card grid (Stats wraps of 224 or 228px cards). */
export function CardGrid({ width, children }: { width: number; children: ReactNode }) {
  return (
    <div className={s.cardGrid} style={{ gridTemplateColumns: `repeat(auto-fill, ${width}px)` }}>
      {children}
    </div>
  );
}

/** A row's title that opens its Edit dialog (medications, goals, records): text until hovered. */
export function EditTitle({ children, onClick, label }: { children: ReactNode; onClick: () => void; label: string }) {
  return (
    <button type="button" className={cx("vl-body-m-strong", s.editTitle)} onClick={onClick} aria-label={label}>
      {children}
    </button>
  );
}

/** Shown under a tab whose screen isn't in the Figma file yet. */

/** Stat card: dot + Label/Medium, Metric/L + unit, caption (Stat / * frames). */
export function StatCard({ stat }: { stat: StatVM }) {
  return (
    <article className={s.stat} aria-label={`${stat.label}: ${stat.value} ${stat.unit}, ${stat.caption}`}>
      <span className={s.statLabel}>
        <AccentDot color={stat.accent} />
        <Txt s="labelM" c="textSecondary">{stat.label}</Txt>
      </span>
      <span className={s.statValue}>
        <Txt s="metricL">{stat.value}</Txt>
        {stat.unit ? <Txt s="metricUnit" c="textTertiary">{stat.unit}</Txt> : null}
      </span>
      <Txt s="caption" c="textTertiary">{stat.caption}</Txt>
    </article>
  );
}

export function StatRow({ stats }: { stats: StatVM[] }) {
  return (
    <div className={s.statRow}>
      {stats.map((st) => (
        <StatCard key={st.key} stat={st} />
      ))}
    </div>
  );
}

/** Tinted count card (Medications / Lab Results summary row). */
export function TintCard({ label, value, bg, fg }: { label: string; value: string; bg: ColorToken; fg: ColorToken }) {
  return (
    <article className={s.tint} style={{ background: tokenVar(bg) }} aria-label={`${label}: ${value}`}>
      <Txt s="labelM" c="textSecondary">{label}</Txt>
      <Txt s="metricL" c={fg}>{value}</Txt>
    </article>
  );
}

/** Main column + 320px rail, 24 apart (Split frames). */
export function Split({ children, rail }: { children: ReactNode; rail: ReactNode }) {
  return (
    <div className={s.split}>
      <div className={s.main}>{children}</div>
      <aside className={s.rail}>{rail}</aside>
    </div>
  );
}

/** Card with a heading. `h3` for main-column cards, `h5` for rail cards. */
export function Panel({
  title,
  level = "h3",
  caption,
  action,
  gap = 12,
  children,
  className,
  style,
  titleColor,
}: {
  title?: string;
  /** Delete Account titles in text/critical. */
  titleColor?: ColorToken;
  level?: "h3" | "h5";
  caption?: ReactNode;
  action?: ReactNode;
  gap?: number;
  children?: ReactNode;
  className?: string;
  style?: CSSProperties;
}) {
  const Heading = level === "h3" ? "h2" : "h3";
  return (
    <section className={cx(s.panel, className)} style={{ gap, ...style }} aria-label={title}>
      {title ? (
        <div className={s.panelHead}>
          <div className={s.panelTitles}>
            <Heading className={`vl-${level}`} style={titleColor ? { color: tokenVar(titleColor) } : undefined}>
              {title}
            </Heading>
            {caption ? <Txt s="caption" c="textTertiary">{caption}</Txt> : null}
          </div>
          {action}
        </div>
      ) : null}
      {children}
    </section>
  );
}

/** Text link in a card header ("View all history"). */
export function HeadLink({ children, href = "#", onClick }: { children: ReactNode; href?: string; onClick?: () => void }) {
  if (onClick)
    return (
      <button type="button" className={cx("vl-label-l", s.link, s.linkButton)} onClick={onClick}>
        {children}
      </button>
    );
  return (
    <Link className={cx("vl-label-l", s.link)} to={href}>
      {children}
    </Link>
  );
}

/** Label + value over a progress track (Bar / *, G / *, Goal tracks). */
export function BarRow({ row, height = 8, strongLabel = true }: { row: BarRowVM; height?: number; strongLabel?: boolean }) {
  return (
    <div className={s.barRow}>
      <div className={s.barHead}>
        <Txt s={strongLabel ? "bodySStrong" : "bodyS"}>{row.label}</Txt>
        <Txt s="caption" c="textTertiary">{row.value}</Txt>
      </div>
      <Track fraction={row.fraction} color={row.color} height={height} label={row.label} />
    </div>
  );
}

export function Track({ fraction, color, height = 8, label }: { fraction: number; color: ColorToken; height?: number; label: string }) {
  return (
    <div
      className={s.track}
      style={{ height }}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(fraction * 100)}
    >
      <div className={s.fill} style={{ width: `${fraction * 100}%`, background: tokenVar(color) }} />
    </div>
  );
}

/** Label / value rows (Energy balance, Sleep consistency); `table` puts values in a left-aligned column (Document details). */
export function KeyValues({ rows, strong = true, table }: { rows: { label: string; value: string; color?: ColorToken }[]; strong?: boolean; table?: boolean }) {
  return (
    <dl className={cx(s.kv, table && s.kvTable)}>
      {rows.map((r) => (
        <div key={r.label}>
          <Txt s="bodyS" c="textSecondary" as="dt">{r.label}</Txt>
          <Txt s={strong ? "bodySStrong" : "bodyS"} c={r.color ?? "textPrimary"} as="dd">{r.value}</Txt>
        </div>
      ))}
    </dl>
  );
}

/** 40px rounded tile with a 20px icon (Type / Meal tiles). */
export function IconTile({ icon, color, size = 40, radius = 11 }: { icon: IconName; color: ColorToken; size?: number; radius?: number }) {
  return (
    <span className={s.tile} style={{ width: size, height: size, borderRadius: radius }}>
      <Icon name={icon} color={color} />
    </span>
  );
}

/** Neutral status pill with a coloured dot and label (Goal / * Pill). */
export function StatusPill({ label, color, bg = "bgSubtle", dot = true }: { label: string; color: ColorToken; bg?: ColorToken; dot?: boolean }) {
  return (
    <span className={s.pill} style={{ background: tokenVar(bg) }}>
      {dot ? <span className={s.pillDot} style={{ background: tokenVar(color) }} /> : null}
      <Txt s="labelM" c={color}>{label}</Txt>
    </span>
  );
}

export function Legend({ items }: { items: LegendVM[] }) {
  return (
    <ul className={s.legend}>
      {items.map((l) => (
        <li key={l.label}>
          <AccentDot color={l.color} />
          <Txt s="caption" c="textSecondary">{l.label}</Txt>
        </li>
      ))}
    </ul>
  );
}

/** Coloured left-rule insight (Insight frames: 3px bar, 10 gap). */
export function Insight({ children, color }: { children: ReactNode; color: ColorToken }) {
  return (
    <p className={cx("vl-body-s", s.insight)} style={{ borderColor: tokenVar(color), color: tokenVar("textSecondary") }}>
      {children}
    </p>
  );
}

/** Small outlined button used inside rows ("History", "Mark Taken", "View"). */
export function RowButton({ children, onClick }: { children: ReactNode; onClick?: () => void }) {
  return (
    <Button variant="secondary" size="sm" onClick={onClick}>
      {children}
    </Button>
  );
}
