// One report as a card with its actions. Used by Reports and device details.

import { api } from "../lib/api.js";
import { h, icon } from "../lib/dom.js";
import { button, confirmSheet, pill, toast } from "../lib/ui.js";
import {
  capitalize, fmtDateTime, fmtRelative, nameOr, reasonIcon, reasonLabel, resolutionLabel,
} from "../lib/format.js";

const deviceHref = (id) => `#/devices/${encodeURIComponent(id)}`;

function personLink(person, { withSlime = true } = {}) {
  // The server keeps reports when a device is deleted and sets its id to null.
  if (!person?.id) return h("span", { class: "person-none" }, "Deleted device");
  const owner = nameOr(person.ownerName, "No name");
  return h("a", { class: "person-link", href: deviceHref(person.id) },
    h("span", { class: "person-names" },
      h("span", { class: "person-name" }, owner),
      withSlime && person.slimeName ? h("span", { class: "person-slime" }, nameOr(person.slimeName, "")) : null),
    h("span", { class: "person-id mono" }, person.id),
    icon("chevronRight", { className: "person-chevron" }));
}

function copyFor(action, report) {
  const reported = report.reported;
  const owner = nameOr(reported?.ownerName, "this person");
  if (action === "ban") {
    return {
      title: `Ban ${owner}'s device?`,
      message: [
        h("p", null, "The device can't use friends, chat or any other online feature anymore. Its friend code stops working and it disappears from its friends' lists."),
        h("p", null, "You can unban it later in Devices. This report and every other open report against the device move to Resolved."),
      ],
      confirmLabel: "Ban device",
      destructive: true,
      done: "Device banned. Its open reports are resolved.",
    };
  }
  if (action === "rename") {
    return {
      title: `Reset ${owner}'s names?`,
      message: [
        h("p", null, "The owner name becomes “Slime friend” and the slime's name becomes “Slime”. They can choose new names in the app."),
        h("p", null, "This report and any other open name reports against the device move to Resolved."),
      ],
      confirmLabel: "Reset names",
      destructive: false,
      done: "Names reset. Report resolved.",
    };
  }
  return {
    title: "Dismiss this report?",
    message: [h("p", null, "Nothing changes for anyone. The report moves to Resolved.")],
    confirmLabel: "Dismiss",
    destructive: false,
    done: "Report dismissed.",
  };
}

/**
 * The open reports the server closes together with `report` when it is
 * resolved with `action`: a ban closes every open report against the device,
 * a rename its open name reports (server/src/admin.js resolveOpen).
 */
export function alsoResolves(report, action, others) {
  const target = report.reported?.id;
  if (!target || action === "dismiss") return [];
  return others.filter((r) => r.id !== report.id && r.status === "open" && r.reported?.id === target
    && (action === "ban" || r.reason === "name"));
}

/**
 * @param {object} report  one item of GET /admin/reports
 * @param {object} opts
 * @param {(report: object, action: string) => void} [opts.onResolved]
 * @param {() => void} [opts.onStale]  the server says the report changed meanwhile; reload
 * @param {boolean} [opts.showReported]  false inside the reported device's own page
 */
export function reportCard(report, { onResolved, onStale, showReported = true } = {}) {
  const isOpen = report.status !== "resolved";
  const reported = report.reported ?? null;
  const reason = report.reason;
  const headingId = `report-${String(report.id).replace(/[^\w-]/g, "")}`;

  const head = h("div", { class: "report-head" },
    h("h3", { class: "report-reason", id: headingId },
      pill(reasonLabel(reason), { iconName: reasonIcon(reason), tone: "neutral" }),
      h("span", { class: "sr-only" }, " report")),
    h("time", { class: "report-time", datetime: report.createdAt ?? "", title: fmtDateTime(report.createdAt) },
      capitalize(fmtRelative(report.createdAt))),
    h("span", { class: "report-id mono", title: "Report ID" }, `#${report.id}`));

  const sections = [];
  if (report.note) {
    sections.push(h("div", { class: "report-section" },
      h("h4", { class: "report-label" }, "Note from the reporter"),
      h("p", { class: "report-note" }, report.note)));
  }
  if (report.text) {
    sections.push(h("div", { class: "report-section" },
      h("h4", { class: "report-label" }, reason === "chat" ? "Reported AI reply" : "Reported text"),
      h("blockquote", { class: "report-quote" }, report.text)));
  }
  if (!report.note && !report.text) {
    sections.push(h("p", { class: "report-empty" }, "The reporter didn't add a note."));
  }

  const people = h("dl", { class: "report-people" });
  if (showReported) {
    people.append(h("div", { class: "report-person" },
      h("dt", null, "Reported"),
      h("dd", null, reported
        ? h("div", { class: "person-wrap" }, personLink(reported), reported.banned ? pill("Banned", { tone: "danger", iconName: "ban" }) : null)
        : h("span", { class: "person-none" }, reason === "chat" ? "The slime's AI reply, no device" : "Deleted device"))));
  }
  if (report.reporter) {
    people.append(h("div", { class: "report-person" },
      h("dt", null, "Reporter"),
      h("dd", null, personLink(report.reporter, { withSlime: false }))));
  }

  const card = h("article", { class: `card report-card${isOpen ? "" : " is-resolved"}`, "aria-labelledby": headingId, tabindex: "-1" },
    head, ...sections, people);

  if (!isOpen) {
    card.append(h("div", { class: "report-status" },
      icon("checkCircle"), h("span", null, `Resolved: ${resolutionLabel(report.resolution)}`),
      report.resolvedAt
        ? h("time", { class: "report-status-time", datetime: report.resolvedAt, title: fmtDateTime(report.resolvedAt) }, capitalize(fmtRelative(report.resolvedAt)))
        : null));
    return card;
  }

  const act = async (action) => {
    const copy = copyFor(action, report);
    // 409: someone already resolved it (or a ban closed it), or there's no device
    // to act on anymore. 404: it was cleaned up. Either way this card is out of date.
    let stale = null;
    const result = await confirmSheet({
      title: copy.title,
      message: copy.message,
      confirmLabel: copy.confirmLabel,
      destructive: copy.destructive,
      action: async () => {
        try {
          return await api.resolveReport(report.id, action);
        } catch (err) {
          if (err?.status !== 409 && err?.status !== 404) throw err;
          stale = err;
          return true;
        }
      },
    });
    if (!result) return;
    if (stale) {
      toast(stale.message);
      onStale?.();
      return;
    }
    const next = result?.report ?? { ...report, status: "resolved", resolution: action };
    toast(copy.done, { tone: "success" });
    onResolved?.(next, action);
  };

  const canActOnDevice = Boolean(reported);
  const actions = h("div", { class: "report-actions" },
    button("Dismiss", { kind: "secondary", small: true, iconName: "check", onClick: () => act("dismiss") }),
    canActOnDevice ? button("Reset names", { kind: "secondary", small: true, iconName: "reset", onClick: () => act("rename") }) : null,
    canActOnDevice ? button(reported.banned ? "Already banned" : "Ban", {
      kind: "danger", small: true, iconName: "ban", disabled: reported.banned ? true : null, onClick: () => act("ban"),
    }) : null);
  card.append(actions);
  return card;
}
