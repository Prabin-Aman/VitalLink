// Messages — Desktop 1440 (Figma 72:206).
import { loadMessages } from "@vitallink/api";
import { useState } from "react";
import { Icon, tokenVar } from "../../components/Icon";
import { cx, PageHeader, StatusPill, Txt } from "../../components/kit";
import { Avatar } from "../../components/ui";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import s from "./Messages.module.css";

export function Messages() {
  const state = useScreen(loadMessages);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const open = v.conversations.find((c) => c.id === selected) ?? v.conversations[0];
  // Threads load on demand; until then the latest message is the preview.
  const thread = open.messages.length ? open.messages : [{ id: "preview", mine: false, body: open.preview, time: open.when, attachment: null }];

  return (
    <>
      <PageHeader title="Messages" subtitle="Secure, recorded communication with your care team. Not for emergencies: call 911." />
      <section className={s.shell} aria-label="Messages">
        <nav className={s.list} aria-label="Conversations">
          <Txt s="h5" as="h2" className={s.listHead}>Conversations</Txt>
          {v.conversations.map((c) => (
            <button key={c.id} type="button" className={cx(s.conv, c.id === open.id && s.active)} aria-current={c.id === open.id} onClick={() => setSelected(c.id)}>
              <Avatar initials={c.initials} tone={c.tone} size={36} fontSize={13} />
              <span className={s.convText}>
                <span className={s.convTop}>
                  <Txt s="bodySStrong">{c.name}</Txt>
                  <Txt s="caption" c="textTertiary">{c.when}</Txt>
                </span>
                <Txt s="caption" c="textTertiary">{c.role}</Txt>
                <Txt s="caption" c={c.unread ? "textPrimary" : "textSecondary"}>{c.preview}</Txt>
              </span>
            </button>
          ))}
        </nav>
        <div className={s.thread}>
          <header className={s.threadHead}>
            <Avatar initials={open.initials} tone={open.tone} size={36} fontSize={13} />
            <div className={s.convText}>
              <Txt s="h5" as="h2">{open.name}</Txt>
              <Txt s="caption" c="textTertiary">{open.roleLong}</Txt>
            </div>
            <StatusPill label="Encrypted · logged to your record" color="rangeNormal" bg="rangeNormalBg" />
          </header>
          <ol className={s.messages}>
            {thread.map((m) => (
              <li key={m.id} className={cx(s.msg, m.mine && s.mine)}>
                <div className={s.bubble}>
                  <Txt s="bodyM" c={m.mine ? "textOnBrand" : "textPrimary"} as="p">{m.body}</Txt>
                  {m.attachment && (
                    <div className={s.attachment}>
                      <Icon name="file-text-18" />
                      <Txt s="bodySStrong" className={s.grow}>{m.attachment}</Txt>
                      <a href="#" className="vl-label-m" style={{ color: tokenVar("textLink") }}>Download</a>
                    </div>
                  )}
                  <Txt s="caption" c={m.mine ? "textInverse" : "textTertiary"}>{m.time}</Txt>
                </div>
              </li>
            ))}
          </ol>
          <form className={s.composer} onSubmit={(e) => { e.preventDefault(); setDraft(""); }}>
            <label className={s.input}>
              <span className="sr-only">Message</span>
              <input className="vl-body-m" placeholder="Write a secure message…" value={draft} onChange={(e) => setDraft(e.target.value)} />
            </label>
            <button type="submit" className={cx("vl-label-l", s.send)} disabled={!draft.trim()}>Send</button>
          </form>
        </div>
      </section>
    </>
  );
}
