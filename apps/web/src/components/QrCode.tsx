// A QR code drawn from its modules as one SVG path (no Figma frame; used by Connected Devices › iPhone).
// Always dark on light, even in dark mode, because phone cameras read that most reliably.
import { create } from "qrcode";
import { useMemo } from "react";
import s from "./QrCode.module.css";

/** Modules of blank margin around the code, as the QR standard asks. */
const QUIET = 4;

export function QrCode({ value, label, size = 184 }: { value: string; label: string; size?: number }) {
  const { path, n } = useMemo(() => {
    const m = create(value, { errorCorrectionLevel: "M" }).modules;
    let d = "";
    for (let y = 0; y < m.size; y++) {
      for (let x = 0; x < m.size; x++) {
        if (m.get(y, x)) d += `M${x + QUIET} ${y + QUIET}h1v1h-1z`;
      }
    }
    return { path: d, n: m.size + QUIET * 2 };
  }, [value]);
  return (
    <figure className={`vl-light ${s.qr}`}>
      <svg width={size} height={size} viewBox={`0 0 ${n} ${n}`} role="img" aria-label={label} shapeRendering="crispEdges">
        <rect width={n} height={n} className={s.paper} />
        <path d={path} className={s.ink} />
      </svg>
      <figcaption className="vl-caption">{label}</figcaption>
    </figure>
  );
}
