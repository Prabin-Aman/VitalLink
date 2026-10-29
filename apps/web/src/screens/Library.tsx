// Health Library — Desktop 1440 (Figma 71:104).
import { loadLibrary } from "@vitallink/api";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { tokenVar } from "../components/Icon";
import { cx, PageHeader, Txt } from "../components/kit";
import { useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./Library.module.css";

export function Library() {
  const nav = useNavigate();
  const state = useScreen(loadLibrary);
  const [category, setCategory] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const active = category ?? v.categories[0];
  const q = query.trim().toLowerCase();
  const trending = q ? v.trending.filter((a) => a.title.toLowerCase().includes(q) || a.tag.label.toLowerCase().includes(q)) : v.trending;

  return (
    <>
      <PageHeader title="Health Library" subtitle="Clinically reviewed guidance on the conditions and topics that matter to you." />
      <label className={s.search}>
        <span className="sr-only">Search health topics</span>
        <input className="vl-body-l" placeholder="Search health topics" value={query} onChange={(e) => setQuery(e.target.value)} />
      </label>
      <div className={s.split}>
        <nav className={s.cats} aria-label="Categories">
          <Txt s="overline" c="textTertiary">CATEGORIES</Txt>
          {v.categories.map((c) => (
            <button key={c} type="button" className={cx(c === active ? "vl-body-m-strong" : "vl-body-m", s.cat, c === active && s.catActive)} aria-current={c === active} onClick={() => setCategory(c)}>
              {c}
            </button>
          ))}
        </nav>
        <div className={s.main}>
          <section className={s.featured} aria-label="Featured article">
            <div className={s.copy}>
              <Txt s="overline" c="textBrand">{v.featured.overline}</Txt>
              <Txt s="h2" c="textInverse" as="h2">{v.featured.title}</Txt>
              <Txt s="bodyM" c="textSecondary" as="p">{v.featured.summary}</Txt>
              <Txt s="caption" c="textTertiary">{v.featured.meta}</Txt>
            </div>
            <button type="button" className={cx("vl-label-l", s.read)} onClick={() => nav(`/library/${v.featured.slug}`)}>
              Read Article
            </button>
          </section>
          <Txt s="h3" as="h2">Trending health topics</Txt>
          <div className={s.articles}>
            {trending.map((a) => (
              <Link key={a.id} to={`/library/${a.slug}`} className={s.article}>
                <span className={cx("vl-label-s", s.tag)} style={{ color: tokenVar(a.tag.fg), background: tokenVar(a.tag.bg) }}>
                  {a.tag.label}
                </span>
                <Txt s="h5">{a.title}</Txt>
                <Txt s="caption" c="textTertiary">{a.meta}</Txt>
              </Link>
            ))}
            {trending.length === 0 && <Txt s="bodyM" c="textTertiary" as="p">No topics match “{query}”.</Txt>}
          </div>
        </div>
      </div>
    </>
  );
}
