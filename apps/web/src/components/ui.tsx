import { TONE_BG, type AvatarTone, type ChipVM, type MetricCardVM } from "@vitallink/api";
import type { ColorToken } from "@vitallink/tokens";
import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";
import { Icon, tokenVar } from "./Icon";
import s from "./ui.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

export function Logo({ large, inverse }: { large?: boolean; inverse?: boolean }) {
  return (
    <div className={cx(s.logo, large && s.logoLg)}>
      <span className={cx(s.mark, large && s.markLg)}>
        <Icon name={large ? "health-20" : "health-17"} color="textOnBrand" />
      </span>
      <span
        className={large ? "vl-h3" : "vl-h4"}
        style={{ color: tokenVar(inverse ? "textInverse" : "textPrimary") }}
      >
        VitalLink
      </span>
    </div>
  );
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "tertiary" | "danger";
  size?: "block" | "md" | "cta" | "sm";
  children: ReactNode;
};

export function Button({ variant = "primary", size = "md", className, ...rest }: ButtonProps) {
  const sizeClass = size === "block" ? (variant === "primary" ? s.block : s.blockOutline) : s[size];
  return <button type="button" className={cx(s.btn, s[variant], sizeClass, className)} {...rest} />;
}

export function Chip({ chip, dot }: { chip: ChipVM; dot?: boolean }) {
  return (
    <span className={cx(s.chip, dot && s.chipDot)} style={{ background: tokenVar(chip.bg) }}>
      {dot && <span className={s.dot} style={{ background: tokenVar(chip.fg) }} />}
      <span className="vl-label-m" style={{ color: tokenVar(chip.fg) }}>
        {chip.label}
      </span>
    </span>
  );
}

export function Card({
  children,
  className,
  style,
  as: Tag = "section",
}: {
  children: ReactNode;
  className?: string;
  style?: CSSProperties;
  as?: "section" | "div" | "article";
}) {
  return (
    <Tag className={cx(s.card, className)} style={style}>
      {children}
    </Tag>
  );
}

export function AccentDot({ color, size = 8 }: { color: ColorToken; size?: number }) {
  return <span className={s.accent} style={{ background: tokenVar(color), width: size, height: size }} />;
}

export function MetricCard({ m, width }: { m: MetricCardVM; width?: number }) {
  return (
    <article className={s.metric} style={{ width }}>
      <div className={s.metricHead}>
        <AccentDot color={m.accent} />
        <span className="vl-label-m" style={{ color: tokenVar("textSecondary") }}>
          {m.label}
        </span>
      </div>
      <div className={s.valueRow}>
        <span className="vl-metric-l">{m.value}</span>
        <span className="vl-metric-unit" style={{ color: tokenVar("textTertiary") }}>
          {m.unit}
        </span>
      </div>
      <div className={s.metricFoot}>
        <Chip chip={m.chip} />
        <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
          {m.when}
        </span>
      </div>
    </article>
  );
}

/** Avatar/Tone=* — neutral uses secondary text on a muted fill, the rest white on colour. */
export function Avatar({ initials, size = 30, fontSize = 11, tone = "brand" }: { initials: string; size?: number; fontSize?: number; tone?: AvatarTone }) {
  return (
    <span
      className={s.avatar}
      style={{ width: size, height: size, fontSize, background: tokenVar(TONE_BG[tone]), color: tokenVar(tone === "neutral" ? "textSecondary" : "textOnBrand") }}
      aria-hidden
    >
      {initials}
    </span>
  );
}

export function Segmented({
  options,
  value,
  onChange,
  wide,
  label,
}: {
  options: string[];
  value: string;
  onChange: (v: string) => void;
  wide?: boolean;
  label: string;
}) {
  return (
    <div className={s.segmented} role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o}
          type="button"
          className={cx(s.segment, wide && s.segmentWide, "vl-label-m")}
          aria-pressed={o === value}
          onClick={() => onChange(o)}
        >
          {o}
        </button>
      ))}
    </div>
  );
}

export function Progress({ fraction, color, label }: { fraction: number; color: ColorToken; label: string }) {
  return (
    <div
      className={s.track}
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
