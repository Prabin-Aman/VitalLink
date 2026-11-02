// Top bar search results (Figma 207:1493) and profile menu (207:1541), on page 195:2.
import { buildSearchIndex, searchIndex, type SearchIcon, type SearchIndex } from "@vitallink/api";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { endSession, useApi } from "../data";
import { Icon, type IconName } from "./Icon";
import { Avatar } from "./ui";
import s from "./TopBar.module.css";

const ICON: Record<SearchIcon, IconName> = {
  records: "records-16",
  medications: "medications-16",
  appointments: "appointments-16",
  library: "library-16",
  page: "health-16",
};

/** Closes a popover on a click outside `ref` or Escape. */
function useDismiss(open: boolean, ref: React.RefObject<HTMLElement | null>, close: () => void) {
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && close();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, ref, close]);
}

export function TopSearch({ className }: { className: string }) {
  const api = useApi();
  const nav = useNavigate();
  const box = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState<SearchIndex | null>(null);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = useId();
  const groups = useMemo(() => (index ? searchIndex(index, q) : []), [index, q]);
  const flat = groups.flatMap((g) => g.hits);
  useDismiss(open, box, () => setOpen(false));
  useEffect(() => setActive(0), [q]);

  const load = () => {
    if (!index) buildSearchIndex(api).then(setIndex, () => setIndex({ entries: [] }));
  };
  const go = (to: string) => {
    setOpen(false);
    setQ("");
    nav(to);
  };
  const showing = open && q.trim().length > 0;

  return (
    <div ref={box} className={s.searchBox}>
      <label className={className}>
        <Icon name="search-16" color="iconSubtle" />
        <span className="sr-only">Search</span>
        <input
          role="combobox"
          aria-expanded={showing}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showing && flat[active] ? `${listId}-${active}` : undefined}
          placeholder="Search workouts, health data, records…"
          value={q}
          onFocus={() => {
            load();
            setOpen(true);
          }}
          onChange={(e) => {
            setQ(e.target.value);
            setOpen(true);
          }}
          onKeyDown={(e) => {
            if (!showing) return;
            if (e.key === "ArrowDown") {
              e.preventDefault();
              setActive((i) => Math.min(i + 1, flat.length - 1));
            } else if (e.key === "ArrowUp") {
              e.preventDefault();
              setActive((i) => Math.max(i - 1, 0));
            } else if (e.key === "Enter" && flat[active]) {
              e.preventDefault();
              go(flat[active].to);
            }
          }}
        />
      </label>
      {showing && (
        <div className={s.popover}>
          <ul id={listId} role="listbox" aria-label="Search results" className={s.list}>
            {!index && <li className={`vl-body-s ${s.empty}`}>Searching…</li>}
            {index && flat.length === 0 && <li className={`vl-body-s ${s.empty}`}>Nothing matches “{q.trim()}”.</li>}
            {groups.map((g) => (
              <li key={g.group} role="presentation">
                <span className={`vl-overline ${s.group}`}>{g.group}</span>
                <ul role="presentation" className={s.list}>
                  {g.hits.map((h) => {
                    const i = flat.indexOf(h);
                    return (
                      <li
                        key={`${h.to}-${h.title}`}
                        id={`${listId}-${i}`}
                        role="option"
                        aria-selected={i === active}
                        className={s.item}
                        onMouseEnter={() => setActive(i)}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => go(h.to)}
                      >
                        <Icon name={ICON[h.icon]} color="iconSubtle" />
                        <span className={s.text}>
                          <span className="vl-body-m">{h.title}</span>
                          <span className={`vl-caption ${s.caption}`}>{h.caption}</span>
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </li>
            ))}
          </ul>
          {flat.length > 0 && <p className={`vl-caption ${s.hint}`}>↑ ↓ to move · Enter to open · Esc to close</p>}
        </div>
      )}
    </div>
  );
}

export function ProfileMenu({ name, initials, email, photo, className, labelClass }: { name: string; initials: string; email: string; photo: string | null; className: string; labelClass: string }) {
  const nav = useNavigate();
  const box = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const menuId = useId();
  useDismiss(open, box, () => setOpen(false));
  const go = (to: string) => {
    setOpen(false);
    nav(to);
  };
  const signOut = () => {
    // Real sign-out is Supabase's once sign-in exists; for now this drops the tab's dev token.
    endSession();
    go("/welcome");
  };
  const items: { label: string; icon: IconName; onClick: () => void; danger?: boolean }[] = [
    { label: "Profile & settings", icon: "user-16", onClick: () => go("/settings") },
    { label: "Privacy Centre", icon: "privacy-16", onClick: () => go("/privacy") },
    { label: "Emergency Medical ID", icon: "emergency-16", onClick: () => go("/emergency") },
  ];
  return (
    <div ref={box} className={s.menuBox}>
      <button type="button" className={className} aria-label={`Profile: ${name}`} aria-haspopup="menu" aria-expanded={open} aria-controls={menuId} onClick={() => setOpen((o) => !o)}>
        <Avatar initials={initials} photo={photo} size={26} fontSize={10} />
        <span className={labelClass}>{name}</span>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label="Profile" className={`${s.popover} ${s.menu}`}>
          <div className={s.who}>
            <span className="vl-body-m-strong">{name}</span>
            <span className={`vl-caption ${s.caption}`}>{email}</span>
          </div>
          <hr className={s.divider} />
          {items.map((it) => (
            <button key={it.label} type="button" role="menuitem" className={s.item} onClick={it.onClick}>
              <Icon name={it.icon} color="iconSubtle" />
              <span className="vl-body-m">{it.label}</span>
            </button>
          ))}
          <hr className={s.divider} />
          <button type="button" role="menuitem" className={`${s.item} ${s.danger}`} onClick={signOut}>
            <Icon name="settings-16" color="iconCritical" />
            <span className="vl-body-m">Sign out</span>
          </button>
        </div>
      )}
    </div>
  );
}
