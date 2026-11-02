// Health Library › Article — Desktop 1440 (Figma 208:3270, page 195:2).
import { loadArticle, type InlineVM } from "@vitallink/api";
import { useCallback } from "react";
import { Link, useParams } from "react-router-dom";
import { tokenVar } from "../../components/Icon";
import { cx, Txt } from "../../components/kit";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import l from "./Library.module.css";
import s from "./Article.module.css";

/** Bold and plain runs; text only, so article content can never inject markup. */
const Inline = ({ parts }: { parts: InlineVM }) => (
  <>
    {parts.map((p, i) => (p.bold ? <strong key={i}>{p.text}</strong> : p.italic ? <em key={i}>{p.text}</em> : <span key={i}>{p.text}</span>))}
  </>
);

export function Article() {
  const { slug = "" } = useParams();
  const state = useScreen(useCallback((api) => loadArticle(api, slug), [slug]));
  if (state.status !== "ready") return <ScreenState state={state} />;
  const a = state.data;
  return (
    <>
      <header className={s.heading}>
        <Link to="/library" className={cx("vl-label-l", s.back)}>‹ Health Library</Link>
        <h1 className="vl-h1">{a.title}</h1>
        <Txt s="bodyL" c="textSecondary" as="p">{a.meta}</Txt>
      </header>
      <div className={s.split}>
        <article className={s.body}>
          {a.summary && <p className={cx("vl-body-l", s.summary)}>{a.summary}</p>}
          <hr className={s.rule} />
          {a.blocks.map((b, i) =>
            b.kind === "heading" ? (
              <h2 key={i} className="vl-h3">{b.text}</h2>
            ) : b.kind === "list" ? (
              <ul key={i} className={cx("vl-body-l", s.list)}>
                {b.items.map((it, j) => (
                  <li key={j}><Inline parts={it} /></li>
                ))}
              </ul>
            ) : (
              <p key={i} className="vl-body-l"><Inline parts={b.parts} /></p>
            ),
          )}
        </article>
        <aside className={s.rail}>
          {a.related.length > 0 && <Txt s="h5" as="h2">More in {a.categoryLabel}</Txt>}
          {a.related.map((r) => (
            <Link key={r.slug} to={`/library/${r.slug}`} className={l.article}>
              <span className={cx("vl-label-s", l.tag)} style={{ color: tokenVar(r.tag.fg), background: tokenVar(r.tag.bg) }}>
                {r.tag.label}
              </span>
              <Txt s="h5">{r.title}</Txt>
              <Txt s="caption" c="textTertiary">{r.meta}</Txt>
            </Link>
          ))}
          <p className={cx("vl-body-s", s.note)}>For learning, not a diagnosis. Talk to your clinician about your own numbers.</p>
        </aside>
      </div>
    </>
  );
}
