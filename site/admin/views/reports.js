// Reports: open / resolved / all, newest first, 50 at a time.

import { api } from "../lib/api.js";
import { childAbort, h, isAbort, replace } from "../lib/dom.js";
import { button, emptyState, errorState, iconButton, segmented, withBusy } from "../lib/ui.js";
import { plural } from "../lib/format.js";
import { alsoResolves, reportCard } from "./report-card.js";

const STATUSES = [["open", "Open"], ["resolved", "Resolved"], ["all", "All"]];
const validStatus = (value) => (STATUSES.some(([v]) => v === value) ? value : "open");

const EMPTY = {
  open: { title: "No open reports", message: "When someone reports a name, a friend or a chat reply in the app, it shows up here." },
  resolved: { title: "Nothing resolved yet", message: "Reports you dismiss, rename or ban end up here." },
  all: { title: "No reports yet", message: "When someone reports a name, a friend or a chat reply in the app, it shows up here." },
};

export default function reportsView(ctx) {
  let status = validStatus(ctx.route.query.get("status"));
  let next = null;
  let items = [];
  let loading = null;
  let lastLoaded = 0;
  let scope = childAbort(ctx.signal);

  const head = ctx.header({ title: "Reports" });
  const refresh = iconButton("refresh", "Refresh", { onClick: () => load() });
  ctx.setActions(refresh);

  const seg = segmented({
    name: "status",
    label: "Show reports",
    options: STATUSES,
    value: status,
    onChange: (value) => ctx.navigate(`#/reports?status=${value}`, { replace: true }),
  });

  // The list itself isn't a live region (it would read every card aloud);
  // this short status is.
  const live = h("p", { class: "sr-only", role: "status" });
  const list = h("div", { class: "report-list", "aria-busy": "true" });
  const more = h("div", { class: "load-more reports-more" });
  ctx.root.append(head.el, live, h("div", { class: "toolbar" }, seg.el), list, more);

  function summary({ announce = false } = {}) {
    let text;
    if (status === "open") {
      text = items.length === 0 ? "Nothing to review" : `${plural(items.length, "report")}${next ? "+" : ""} to review`;
    } else {
      text = items.length === 0 ? "" : `${plural(items.length, "report")}${next ? "+" : ""}`;
    }
    head.setSubtitle(text);
    if (announce) say(text || EMPTY[status].title);
  }

  /** Announces `text` once; cleared afterwards so it isn't read twice when browsing. */
  let sayTimer = 0;
  function say(text) {
    live.textContent = text;
    clearTimeout(sayTimer);
    sayTimer = setTimeout(() => {
      live.textContent = "";
    }, 5000);
  }
  ctx.signal.addEventListener("abort", () => clearTimeout(sayTimer), { once: true });

  /** Report id → its card, so a change only touches the cards it affects. */
  let cards = new Map();

  function render({ announce = false } = {}) {
    scope.abort();
    scope = childAbort(ctx.signal);
    list.setAttribute("aria-busy", "false");
    cards = new Map();
    if (items.length === 0) {
      const empty = EMPTY[status];
      replace(list, emptyState({ ...empty, anim: status === "open" ? "sleep" : "idle", signal: scope.signal }));
    } else {
      replace(list, ...items.map(card));
    }
    replace(more, next ? moreButton() : null);
    summary({ announce });
  }

  /** Applies a resolve the way the server does: a ban or rename can close more reports than the one clicked. */
  function resolved(report, updated, action) {
    const key = (r) => String(r.id);
    const closed = new Set([report, ...alsoResolves(report, action, items)].map(key));
    const target = report.reported?.id;
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
    const patch = (r) => {
      let out = key(r) === key(report) ? updated : closed.has(key(r)) ? { ...r, status: "resolved", resolution: action, resolvedAt: now } : r;
      if (target && out.reported?.id === target) {
        if (action === "ban") out = { ...out, reported: { ...out.reported, banned: true } };
        if (action === "rename") out = { ...out, reported: { ...out.reported, ownerName: "Slime friend", slimeName: "Slime" } };
      }
      return out;
    };

    ctx.adjustOpenReports(-closed.size);
    ctx.refreshStats();

    if (status === "open") {
      const ordered = [...list.children];
      const at = ordered.indexOf(cards.get(key(report)));
      const stays = (node) => node.dataset.id != null && !closed.has(node.dataset.id);
      const neighbour = ordered.slice(at + 1).find(stays) ?? ordered.slice(0, at).reverse().find(stays);
      items = items.filter((r) => !closed.has(key(r))).map(patch);
      if (items.length === 0 && !next) {
        render({ announce: true });
        head.titleEl.focus({ preventScroll: true });
        return;
      }
      for (const id of closed) {
        cards.get(id)?.remove();
        cards.delete(id);
      }
      // Remaining cards about the same device show its new name or ban.
      for (const r of items) {
        if (target && r.reported?.id === target && action !== "dismiss") cards.get(key(r))?.replaceWith(card(r));
      }
      summary();
      say(closed.size > 1 ? `${plural(closed.size, "report")} resolved` : "Report resolved");
      (cards.get(neighbour?.dataset.id) ?? list.firstElementChild)?.focus({ preventScroll: false });
    } else {
      const before = new Map(items.map((r) => [r.id, r]));
      items = items.map(patch);
      for (const r of items) {
        if (before.get(r.id) !== r) cards.get(key(r))?.replaceWith(card(r));
      }
      say(closed.size > 1 ? `${plural(closed.size, "report")} resolved` : "Report resolved");
      cards.get(key(report))?.focus({ preventScroll: true });
    }
  }

  function card(report) {
    const el = reportCard(report, {
      onResolved: (updated, action) => resolved(report, updated, action),
      // Resolved elsewhere meanwhile: show the server's current list.
      onStale: () => {
        ctx.refreshStats();
        load().then(() => {
          if (!ctx.signal.aborted && !document.querySelector("dialog[open]")) head.titleEl.focus({ preventScroll: true });
        });
      },
    });
    el.dataset.id = String(report.id);
    cards.set(String(report.id), el);
    return el;
  }

  function moreButton() {
    const btn = button("Load more", { kind: "secondary", block: true });
    btn.addEventListener("click", () => withBusy(btn, async () => {
      try {
        const data = await api.reports({ status, before: next }, { signal: ctx.signal });
        const known = new Set(items.map((r) => r.id));
        const added = (data.reports ?? []).filter((r) => !known.has(r.id));
        items = items.concat(added);
        next = data.next ?? null;
        render({ announce: true });
        // The button is gone now; continue where the new cards start.
        if (added.length) cards.get(String(added[0].id))?.focus({ preventScroll: true });
      } catch (err) {
        if (isAbort(err) || err.handled) return;
        replace(more, errorState({ message: err.message, compact: true, onRetry: () => replace(more, moreButton()) }));
      }
    }));
    return btn;
  }

  async function load() {
    loading?.abort();
    const ac = new AbortController();
    loading = ac;
    ctx.signal.addEventListener("abort", () => ac.abort(), { once: true });
    list.setAttribute("aria-busy", "true");
    if (items.length === 0) replace(list, h("div", { class: "report-skeleton", "aria-hidden": "true" },
      ...[0, 1, 2].map(() => h("div", { class: "card report-card" },
        h("span", { class: "skeleton skeleton-line", style: { width: "30%" } }),
        h("span", { class: "skeleton skeleton-block" }),
        h("span", { class: "skeleton skeleton-line skeleton-line-small", style: { width: "60%" } })))));
    refresh.classList.add("is-spinning");
    try {
      const data = await api.reports({ status }, { signal: ac.signal });
      items = data.reports ?? [];
      next = data.next ?? null;
      lastLoaded = Date.now();
      if (status === "open" && !next) ctx.setOpenReports(items.length);
      render({ announce: true });
    } catch (err) {
      if (isAbort(err) || err.handled) return;
      list.setAttribute("aria-busy", "false");
      replace(list, errorState({ message: err.message, onRetry: () => load() }));
      replace(more);
      head.setSubtitle("");
    } finally {
      if (loading === ac) refresh.classList.remove("is-spinning");
    }
  }

  load();

  return {
    update(route) {
      const nextStatus = validStatus(route.query.get("status"));
      if (nextStatus !== status) {
        status = nextStatus;
        seg.set(status);
        items = [];
        next = null;
        load();
      }
      return true;
    },
    refresh: () => load(),
    onVisible: () => {
      if (Date.now() - lastLoaded > 60000) load();
    },
  };
}
