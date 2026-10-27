// Document Viewer — Desktop 1440 (Figma 143:1955).
import { loadDocument } from "@vitallink/api";
import { useCallback } from "react";
import { useParams } from "react-router-dom";
import { Action, KeyValues, PageHeader, Panel, Txt } from "../components/kit";
import { Button } from "../components/ui";
import { useApi, useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./DocumentViewer.module.css";

export function DocumentViewer() {
  const { id = "" } = useParams();
  const api = useApi();
  const state = useScreen(useCallback((api) => loadDocument(api, id), [id]));
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader
        title={v.title}
        subtitle={v.subtitle}
        actions={
          <>
            <Action onClick={() => window.print()}>Print</Action>
            <Action>Share</Action>
            <Action
              primary
              onClick={() =>
                v.canDownload
                  ? api.recordDownloadUrl(id).then((url) => url && window.open(url, "_blank", "noopener"), (e: Error) => alert(e.message))
                  : alert("This record has no file attached. Print it to save a copy.")
              }
            >
              Download
            </Action>
          </>
        }
      />
      <div className={s.split}>
        <div className={s.preview}>
          <article className={s.page} aria-label={`${v.page.title} report`}>
            <Txt s="overline" c="textTertiary">{v.page.overline}</Txt>
            <Txt s="h2" as="h2">{v.page.title}</Txt>
            <Txt s="caption" c="textTertiary" as="p">{v.page.meta}</Txt>
            <hr className={s.rule} />
            <table className={s.results}>
              <tbody>
                {v.page.rows.map((r) => (
                  <tr key={r.name}>
                    <th scope="row"><Txt s="bodyM">{r.name}</Txt></th>
                    <td><Txt s="numeric" c={r.valueColor}>{r.value}</Txt></td>
                    <td><Txt s="table" c="textTertiary">{r.unit}</Txt></td>
                    <td><Txt s="numeric" c="textTertiary">{r.range}</Txt></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Txt s="caption" c="textTertiary" as="p">{v.page.footnote}</Txt>
          </article>
        </div>
        <aside className={s.rail}>
          <Panel title="Document details" level="h5" gap={12}>
            <KeyValues rows={v.details} table />
          </Panel>
          <Panel title="Sharing permissions" level="h5" gap={12}>
            {v.sharing.map((x) => (
              <div key={x.name} className={s.share}>
                <Txt s="bodySStrong">{x.name}</Txt>
                <Txt s="caption" c={x.color}>{x.caption}</Txt>
              </div>
            ))}
          </Panel>
          <Panel title="Your notes" level="h5" gap={12}>
            {v.note && <Txt s="bodyS" c="textSecondary" as="p">“{v.note}”</Txt>}
            <Button variant="secondary" size="block">Add a note</Button>
          </Panel>
        </aside>
      </div>
    </>
  );
}
