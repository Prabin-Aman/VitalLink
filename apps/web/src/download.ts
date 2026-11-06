// Saving files from the browser: data exports, CSVs and templates.
import type { VitalLinkClient } from "@vitallink/api";

/** Saves a blob as a file through a temporary link. */
export function saveFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement("a"), { href: url, download: filename });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** GET /me/export as a JSON file. Mock mode has no account to export, so it says so. */
export async function exportMyData(api: VitalLinkClient) {
  try {
    const file = await api.exportData();
    if (file) saveFile(file.blob, file.filename);
    else alert("Export works when the portal is connected to your account.");
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e));
  }
}
