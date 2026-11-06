// Health Library (Figma 71:401) and the article reader (208:3270).
import type { ColorToken } from "@vitallink/tokens";
import { need, type VitalLinkClient } from "../client";
import { dayMonth } from "../format";
import type { Article } from "../types";
import type { ChipVM } from "./shared";

// ---------------------------------------------------------------- library

export interface ArticleVM {
  id: string;
  /** Route param for the reader (/library/:slug); mock articles without a slug use their id. */
  slug: string;
  tag: ChipVM;
  title: string;
  meta: string;
}

export interface LibraryVM {
  categories: string[];
  featured: { slug: string; overline: string; title: string; summary: string; meta: string };
  trending: ArticleVM[];
}

const CATEGORY_TONE: Record<string, [ColorToken, ColorToken]> = {
  Fitness: ["textEnergy", "bgEnergySubtle"],
  "First Aid": ["textEnergy", "bgEnergySubtle"],
  Heart: ["textEnergy", "bgEnergySubtle"],
  Nutrition: ["rangeNormal", "rangeNormalBg"],
  Diabetes: ["rangeBorderline", "rangeBorderlineBg"],
  Sleep: ["textBrand", "bgBrandSubtle"],
  Medication: ["textBrand", "bgBrandSubtle"],
};
/** Tag colours [fg, bg] for a library category (Library cards and the article reader). */
export const categoryTone = (category: string): [ColorToken, ColorToken] => CATEGORY_TONE[category] ?? ["textSecondary", "bgSubtle"];

export async function loadLibrary(api: VitalLinkClient): Promise<LibraryVM> {
  const l = need(await api.library(), "Health Library");
  const article = (a: Article): ArticleVM => {
    const [fg, bg] = CATEGORY_TONE[a.category] ?? ["textSecondary", "bgSubtle"];
    return {
      id: a.id,
      slug: a.slug ?? a.id,
      tag: { label: a.category.toUpperCase(), fg, bg },
      title: a.title,
      meta: `Medically reviewed · ${a.minutes} min read`,
    };
  };
  const f = l.featured;
  return {
    categories: l.categories,
    featured: {
      slug: f.slug ?? f.id,
      overline: `FEATURED · ${f.category.toUpperCase()}`,
      title: f.title,
      summary: f.summary ?? "",
      meta: `Medically reviewed${f.reviewer ? ` by ${f.reviewer}` : ""} · ${f.minutes} min read`,
    },
    trending: l.trending.filter((a) => a.title !== f.title).slice(0, 6).map(article),
  };
}

// ---------------------------------------------------------------- Health Library › Article (Figma 208:3270)

export type InlineVM = { text: string; bold: boolean; italic?: boolean }[];
export type ArticleBlockVM = { kind: "heading"; text: string } | { kind: "paragraph"; parts: InlineVM } | { kind: "list"; items: InlineVM[] };

/** "**60 and 100** beats" and "*general information*" → bold, italic and plain runs; other marks show as written. */
const inline = (s: string): InlineVM =>
  s
    .split(/(\*\*[^*]+\*\*|\*[^*]+\*)/)
    .filter(Boolean)
    .map((t) =>
      t.startsWith("**") && t.endsWith("**") && t.length > 4
        ? { text: t.slice(2, -2), bold: true }
        : t.startsWith("*") && t.endsWith("*") && t.length > 2
          ? { text: t.slice(1, -1), bold: false, italic: true }
          : { text: t, bold: false },
    );

/** The library's Markdown subset: "## " headings, "- " lists, paragraphs split by blank lines. */
export function parseArticle(md: string): ArticleBlockVM[] {
  const blocks: ArticleBlockVM[] = [];
  let para: string[] = [];
  let list: InlineVM[] | null = null;
  const flush = () => {
    if (para.length) blocks.push({ kind: "paragraph", parts: inline(para.join(" ")) });
    if (list) blocks.push({ kind: "list", items: list });
    para = [];
    list = null;
  };
  for (const raw of md.replace(/\r/g, "").split("\n")) {
    const line = raw.trim();
    if (!line) flush();
    else if (/^#{1,6}\s/.test(line)) {
      flush();
      blocks.push({ kind: "heading", text: line.replace(/^#+\s*/, "") });
    } else if (/^[-*]\s/.test(line)) {
      if (para.length) flush();
      (list ??= []).push(inline(line.slice(2)));
    } else {
      if (list) flush();
      para.push(line);
    }
  }
  flush();
  return blocks;
}

export interface ArticleVM2 {
  title: string;
  meta: string;
  summary: string;
  blocks: ArticleBlockVM[];
  categoryLabel: string;
  related: { slug: string; tag: { label: string; fg: ColorToken; bg: ColorToken }; title: string; meta: string }[];
}
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
export async function loadArticle(api: VitalLinkClient, slug: string): Promise<ArticleVM2> {
  const [a, lib, me] = await Promise.all([api.article(slug), api.library().catch(() => null), api.me()]);
  const pool = lib ? [lib.featured, ...lib.trending] : [];
  const seen = new Set<string>([a.slug]);
  const related = [...pool.filter((x) => x.category === a.category), ...pool.filter((x) => x.category !== a.category)]
    .filter((x) => !seen.has(x.slug ?? x.id) && seen.add(x.slug ?? x.id))
    .slice(0, 3)
    .map((x) => {
      const [fg, bg] = categoryTone(x.category);
      return { slug: x.slug ?? x.id, tag: { label: x.category.toUpperCase(), fg, bg }, title: x.title, meta: `${x.minutes} min read` };
    });
  return {
    title: a.title,
    meta: [cap(a.category), `${a.minutes} min read`, a.published_on ? `updated ${dayMonth(`${a.published_on}T12:00:00Z`, me.timezone).replace(/^0/, "")} ${a.published_on.slice(0, 4)}` : null].filter(Boolean).join(" · "),
    summary: a.summary ?? "",
    blocks: parseArticle(a.body_md),
    categoryLabel: cap(a.category),
    related,
  };
}
