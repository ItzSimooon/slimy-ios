// Devices: search, list, details with ban / unban / reset names / delete.
// Wide screens show list and details side by side; narrow screens push the
// details as their own page, like a navigation stack.

import { api } from "../lib/api.js";
import { childAbort, debounce, h, icon, isAbort, replace } from "../lib/dom.js";
import {
  button, confirmSheet, copyButton, emptyState, errorState, group, iconButton, iconSquare, mono, pill,
  row, segmented, skeletonList, toast, withBusy,
} from "../lib/ui.js";
import { mountSlime, skinInfo, slimeAvatar } from "../lib/slime.js";
import {
  capitalize, fmtAgoShort, fmtDateTime, fmtInt, fmtRelative, gameLabel, nameOr, plural, stageLabel,
} from "../lib/format.js";
import { alsoResolves, reportCard } from "./report-card.js";

const WIDE = matchMedia("(min-width: 1100px)");

/** The last list, so going back from a device restores it without a reload. */
let listCache = null;

const ownerOf = (d) => nameOr(d?.ownerName, "No name");
/** fmtRelative inside a sentence: "Seen just now", "Banned 3 hours ago". */
const relInline = (value) => fmtRelative(value).replace(/^Just now$/, "just now");

function slimeLine(d) {
  const parts = [];
  if (d.slimeName) parts.push(nameOr(d.slimeName, "Slime"));
  if (d.stage) parts.push(stageLabel(d.stage));
  if (typeof d.level === "number") parts.push(`Level ${fmtInt(d.level)}`);
  return parts.length ? parts.join(" · ") : "No slime uploaded yet";
}

export default function devicesView(ctx) {
  let q = (ctx.route.query.get("q") ?? "").trim();
  let bannedOnly = ctx.route.query.get("banned") === "1";
  let selected = ctx.route.parts[0] ?? null;
  const mode = WIDE.matches ? "split" : selected ? "detail" : "list";

  const onWideChange = () => ctx.navigate(location.hash || "#/devices", { replace: true });
  WIDE.addEventListener("change", onWideChange);
  ctx.signal.addEventListener("abort", () => WIDE.removeEventListener("change", onWideChange), { once: true });

  const query = () => {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (bannedOnly) params.set("banned", "1");
    const text = params.toString();
    return text ? `?${text}` : "";
  };
  /** Where the list lives in the URL right now (keeps the open device on wide screens). */
  const listPath = () => (selected && mode === "split" ? `#/devices/${encodeURIComponent(selected)}` : "#/devices");
  const hrefFor = (id) => `#/devices/${encodeURIComponent(id)}${query()}`;

  /* ---------- List ---------- */

  let devices = [];
  let next = null;
  let listLoad = null;
  const listEl = h("div", { class: "list device-list", role: "list", "aria-label": "Devices", "aria-busy": "true" });
  const caption = h("p", { class: "list-caption", "aria-live": "polite" });
  const more = h("div", { class: "load-more" });
  let listScope = childAbort(ctx.signal);

  const input = h("input", {
    type: "search", class: "search-input", placeholder: "Name, slime or device ID", "aria-label": "Search devices",
    enterkeyhint: "search", autocomplete: "off", autocapitalize: "off", spellcheck: "false", "data-search": "", value: q,
  });
  const clearBtn = h("button", { type: "button", class: "search-clear", "aria-label": "Clear search", hidden: !q }, icon("xcircle"));
  const searchEl = h("div", { class: "search", role: "search" }, icon("search", { className: "search-icon" }), input, clearBtn);

  const runSearch = () => {
    const value = input.value.trim();
    clearBtn.hidden = !input.value;
    if (value === q) return;
    q = value;
    history.replaceState(null, "", listPath() + query());
    loadList();
  };
  const debounced = debounce(runSearch, 250);
  input.addEventListener("input", () => {
    clearBtn.hidden = !input.value;
    debounced();
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      debounced.cancel();
      runSearch();
    } else if (event.key === "Escape" && input.value) {
      event.preventDefault();
      input.value = "";
      debounced.cancel();
      runSearch();
    }
  });
  clearBtn.addEventListener("click", () => {
    input.value = "";
    debounced.cancel();
    runSearch();
    input.focus();
  });
  ctx.signal.addEventListener("abort", () => debounced.cancel(), { once: true });

  const filter = segmented({
    name: "device-filter",
    label: "Show devices",
    options: [["all", "All"], ["banned", "Banned"]],
    value: bannedOnly ? "banned" : "all",
    className: "segmented-small",
    onChange: (value) => {
      bannedOnly = value === "banned";
      history.replaceState(null, "", listPath() + query());
      loadList();
    },
  });

  function deviceRow(d) {
    const reports = Number(d.reportsAgainst) || 0;
    const el = row({
      leading: slimeAvatar(d.skin, d.stage),
      title: [h("span", { class: "row-title-text" }, ownerOf(d)),
        d.banned ? pill("Banned", { tone: "danger" }) : null],
      subtitle: slimeLine(d),
      value: h("span", { title: d.lastSeenAt ? `Last seen ${fmtDateTime(d.lastSeenAt)}` : "Never seen" }, fmtAgoShort(d.lastSeenAt)),
      trailing: reports > 0 ? h("span", { class: "count-flag", title: plural(reports, "report") },
        icon("flag"), h("span", null, fmtInt(reports)), h("span", { class: "sr-only" }, reports === 1 ? " report" : " reports")) : null,
      href: hrefFor(d.id),
      className: "device-row",
    });
    el.dataset.id = d.id;
    if (d.id === selected && mode === "split") el.querySelector("a")?.setAttribute("aria-current", "true");
    return el;
  }

  const cacheKey = () => `${bannedOnly ? "banned" : "all"}|${q}`;

  function saveCache() {
    const key = cacheKey();
    listCache = { key, devices, next, at: Date.now(), scrollY: listCache?.key === key ? listCache.scrollY : 0 };
  }

  function updateCaption() {
    const total = bannedOnly ? ctx.stats?.devices?.banned : ctx.stats?.devices?.total;
    const noun = bannedOnly ? "banned device" : "device";
    if (q) caption.textContent = devices.length === 0 ? "" : `${fmtInt(devices.length)}${next ? "+" : ""} ${devices.length === 1 && !next ? "match" : "matches"}`;
    else if (typeof total === "number" && total > devices.length) caption.textContent = `Showing ${fmtInt(devices.length)} of ${plural(total, noun)}`;
    else caption.textContent = devices.length ? plural(devices.length, noun) : "";
  }

  function renderList() {
    listScope.abort();
    listScope = childAbort(ctx.signal);
    listEl.setAttribute("aria-busy", "false");
    listEl.classList.remove("is-stale");
    if (devices.length === 0) {
      listEl.classList.add("is-empty");
      const compact = mode === "split";
      replace(listEl, q
        ? emptyState({
          title: bannedOnly ? `No banned devices match “${q}”` : `No devices match “${q}”`,
          message: "Search finds owner names, slime names and the start of a device ID.",
          anim: "confused", signal: listScope.signal, compact,
        })
        : bannedOnly
          ? emptyState({ title: "No banned devices", message: "Devices you ban show up here, so you can unban them later.", anim: "idle", signal: listScope.signal, compact })
          : emptyState({ title: "No devices yet", message: "A device appears here once someone goes online in the app.", anim: "sleep", signal: listScope.signal, compact }));
    } else {
      listEl.classList.remove("is-empty");
      replace(listEl, ...devices.map(deviceRow));
    }
    replace(more, next ? moreButton() : null);
    updateCaption();
  }

  function moreButton() {
    const btn = button("Load more", { kind: "secondary", block: true });
    btn.addEventListener("click", () => withBusy(btn, async () => {
      try {
        const data = await api.devices({ q: q || undefined, banned: bannedOnly, before: next }, { signal: ctx.signal });
        const known = new Set(devices.map((d) => d.id));
        devices = devices.concat((data.devices ?? []).filter((d) => !known.has(d.id)));
        next = data.next ?? null;
        saveCache();
        renderList();
      } catch (err) {
        if (isAbort(err) || err.handled) return;
        toast(err.message, { tone: "error" });
      }
    }));
    return btn;
  }

  async function loadList() {
    listLoad?.abort();
    const ac = childAbort(ctx.signal);
    listLoad = ac;
    listEl.setAttribute("aria-busy", "true");
    if (devices.length === 0 && !listEl.classList.contains("is-empty")) replace(listEl, skeletonList(6));
    else listEl.classList.add("is-stale");
    try {
      const data = await api.devices({ q: q || undefined, banned: bannedOnly }, { signal: ac.signal });
      if (listLoad !== ac) return;
      devices = data.devices ?? [];
      next = data.next ?? null;
      saveCache();
      renderList();
    } catch (err) {
      if (isAbort(err) || err.handled || listLoad !== ac) return;
      listEl.setAttribute("aria-busy", "false");
      listEl.classList.remove("is-stale");
      replace(listEl, errorState({ message: err.message, onRetry: () => loadList(), compact: true }));
      replace(more);
      caption.textContent = "";
    }
  }

  function patchDevice(device) {
    if (!device?.id) return;
    devices = devices.map((d) => (d.id === device.id ? { ...d, ...device } : d));
    if (listCache) listCache.devices = listCache.devices.map((d) => (d.id === device.id ? { ...d, ...device } : d));
    const old = listEl.querySelector(`[data-id="${CSS.escape(device.id)}"]`);
    const fresh = devices.find((d) => d.id === device.id);
    if (old && fresh) {
      const hadFocus = old.contains(document.activeElement);
      const replacement = deviceRow(fresh);
      old.replaceWith(replacement);
      if (hadFocus) replacement.querySelector("a")?.focus({ preventScroll: true });
    }
  }

  function dropDevice(id) {
    const total = ctx.stats?.devices?.total;
    if (typeof total === "number") ctx.stats.devices.total = Math.max(0, total - 1);
    devices = devices.filter((d) => d.id !== id);
    if (listCache) listCache.devices = listCache.devices.filter((d) => d.id !== id);
    if (mode !== "detail") renderList();
  }

  function useCache() {
    if (!listCache || listCache.key !== cacheKey() || Date.now() - listCache.at > 120000) return false;
    devices = listCache.devices;
    next = listCache.next;
    renderList();
    return true;
  }

  /* ---------- Details ---------- */

  let detailScope = childAbort(ctx.signal);

  function confirmAndRun(kind, device, onDone, onGone) {
    const owner = ownerOf(device);
    const copy = {
      ban: {
        title: `Ban ${owner}'s device?`,
        message: [h("p", null, "The device can't use friends, chat or any other online feature anymore. Its friend code stops working, it disappears from its friends' lists, and caring together with it ends."),
          h("p", null, "You can unban it at any time.")],
        confirmLabel: "Ban device", destructive: true, run: () => api.ban(device.id), done: "Device banned.",
      },
      unban: {
        title: `Unban ${owner}'s device?`,
        message: [h("p", null, "The device can use online features again.")],
        confirmLabel: "Unban device", destructive: false, run: () => api.unban(device.id), done: "Device unbanned.",
      },
      rename: {
        title: `Reset ${owner}'s names?`,
        message: [h("p", null, "The owner name becomes “Slime friend” and the slime's name becomes “Slime”. They can choose new names in the app.")],
        confirmLabel: "Reset names", destructive: false, run: () => api.rename(device.id), done: "Names reset.",
      },
      delete: {
        title: `Delete ${owner}'s device?`,
        message: [h("p", null, "This removes the device, its slime, friendships, friend requests, events and challenges, the same as deleting the data in the app. If it cares for a slime together with someone, that ends for both."),
          h("p", { class: "sheet-warning" }, icon("warning"), "This can't be undone.")],
        confirmLabel: "Delete device", destructive: true, typed: "DELETE", run: () => api.deleteDevice(device.id), done: "Device deleted.",
      },
    }[kind];
    // 404: the owner (or another admin tab) deleted the device meanwhile.
    let gone = false;
    const action = async () => {
      try {
        return await copy.run();
      } catch (err) {
        if (err?.status !== 404) throw err;
        gone = true;
        return true;
      }
    };
    return confirmSheet({
      title: copy.title, message: copy.message, confirmLabel: copy.confirmLabel, destructive: copy.destructive,
      typed: copy.typed, action,
    }).then((result) => {
      if (!result) return;
      ctx.refreshStats();
      if (gone && kind === "delete") {
        toast("This device was already deleted.");
        onDone(null);
      } else if (gone) {
        toast("Nothing changed. The device was deleted in the meantime.");
        onGone?.();
      } else {
        toast(copy.done, { tone: "success" });
        onDone(result);
      }
    });
  }

  /** The person this device cares for one slime with (GET /admin/devices/:id, carePartner). */
  function careGroup(data) {
    // Older servers leave carePartner out: say nothing rather than "not sharing".
    if (!("carePartner" in data)) return null;
    const p = data.carePartner;
    return group({
      header: "Caring together",
      footer: p ? "Both iPhones share one slime. Banning or deleting either device ends it for both." : null,
    }, p?.id
      ? row({
        leading: h("span", { class: "row-icon tint-indigo" }, icon("heart")),
        title: ownerOf(p),
        subtitle: "Shares one slime with this device",
        href: hrefFor(p.id),
      })
      : row({ title: h("span", { class: "muted" }, "Not sharing a slime") }));
  }

  function friendRow(f) {
    const love = Math.max(0, Math.min(100, Number(f.love) || 0));
    return row({
      leading: h("span", { class: `love-icon${f.inLove ? " in-love" : ""}` }, icon(f.inLove ? "heartFill" : "heart")),
      title: [h("span", { class: "row-title-text" }, ownerOf(f)), f.banned ? pill("Banned", { tone: "danger" }) : null],
      subtitle: f.slimeName ? nameOr(f.slimeName, "") : null,
      value: h("span", { class: "love" },
        f.inLove ? h("span", { class: "love-tag" }, "In love") : null,
        h("span", { class: "meter", role: "meter", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(love), "aria-label": "Love" },
          h("span", { class: "meter-fill", style: { width: `${love}%` } })),
        h("span", { class: "love-value" }, fmtInt(love))),
      href: hrefFor(f.id),
    });
  }

  /** Renders `data` into `host`, replacing what was there. */
  async function showDetail(host, data, opts) {
    const node = await buildDetail(host, data, opts);
    if (!node) return;
    // After an action the button that started it is replaced; keep keyboard
    // focus on the page instead of dropping it to the document.
    const active = document.activeElement;
    const refocus = active === document.body || (active && host.contains(active) && !host.querySelector(".detail-loading"));
    replace(host, node);
    host.setAttribute("aria-busy", "false");
    opts.onTitle?.(ownerOf(data.device), node);
    if (refocus && !document.querySelector("dialog[open]")) node.querySelector(".hero-title")?.focus({ preventScroll: true });
  }

  async function buildDetail(host, data, opts) {
    const { headingLevel = 2 } = opts;
    detailScope.abort();
    detailScope = childAbort(ctx.signal);
    const scope = detailScope.signal;
    const d = data.device;
    const friends = data.friends ?? [];
    const reports = data.reports ?? [];
    const openReports = reports.filter((r) => r.status !== "resolved");

    const art = h("div", { class: "hero-art" });
    const slime = mountSlime(art, { skin: d.skin ?? "lime", stage: d.stage ?? "young", anim: d.banned ? "sad" : "idle", maxScale: 6 });
    scope.addEventListener("abort", () => slime.dispose(), { once: true });

    const heading = h(`h${headingLevel}`, { class: headingLevel === 1 ? "large-title hero-title" : "hero-title", tabindex: "-1" }, ownerOf(d));
    const hero = h("section", { class: "card hero", "aria-label": "Device summary" },
      art,
      h("div", { class: "hero-text" },
        heading,
        h("p", { class: "hero-sub" }, slimeLine(d)),
        h("div", { class: "hero-pills" },
          d.banned
            ? pill(d.bannedAt ? `Banned ${relInline(d.bannedAt)}` : "Banned", { tone: "danger", iconName: "ban" })
            : pill("Active", { tone: "success", iconName: "checkCircle" }),
          openReports.length ? pill(plural(openReports.length, "open report"), { tone: "warning", iconName: "flag" }) : null,
          pill(d.lastSeenAt ? `Seen ${relInline(d.lastSeenAt)}` : "Never seen", { tone: "neutral", iconName: "clock" }))));

    const skin = await skinInfo(d.skin);
    if (scope.aborted) return null;
    const swatch = skin.color ? h("span", { class: "swatch", style: { background: skin.color, borderColor: skin.shade } }) : null;

    const slimeGroup = group({ header: "Slime" },
      row({ title: "Name", value: d.slimeName ? nameOr(d.slimeName, "Slime") : "Not set" }),
      row({ title: "Skin", value: h("span", { class: "value-with-swatch" }, swatch, skin.title ?? "Unknown") }),
      row({ title: "Stage", value: stageLabel(d.stage) }),
      row({ title: "Level", value: typeof d.level === "number" ? fmtInt(d.level) : "Unknown" }));

    const deviceGroup = group({ header: "Device" },
      row({ title: "ID", value: mono(d.id), trailing: copyButton(d.id, "Copy device ID"), className: "row-id" }),
      row({ title: "Owner name", value: ownerOf(d) }),
      row({ title: "Created", value: fmtDateTime(d.createdAt) }),
      row({ title: "Last seen", value: d.lastSeenAt ? capitalize(fmtRelative(d.lastSeenAt)) : "Never",
        subtitle: d.lastSeenAt ? fmtDateTime(d.lastSeenAt) : null }),
      row({ title: "Status", value: d.banned ? "Banned" : "Active", tone: d.banned ? "danger" : null,
        subtitle: d.banned && d.bannedAt ? `Since ${fmtDateTime(d.bannedAt)}` : null }),
      typeof data.reportsFiled === "number" ? row({ title: "Reports filed", value: fmtInt(data.reportsFiled) }) : null);

    const friendCount = typeof d.friends === "number" ? d.friends : friends.length;
    const friendsGroup = group({
      header: `Friends (${fmtInt(friendCount)})`,
      footer: friends.some((f) => Number(f.love) > 0 || f.inLove)
        ? "Love is a Slimy 1.0 feature. The server still counts it (hugs, visits and gifts from any app raise it) so older apps keep working; Slimy 1.1 doesn't show it."
        : null,
    }, ...(friends.length ? friends.map(friendRow) : [row({ title: h("span", { class: "muted" }, "No friends yet") })]));

    const reportsSection = h("section", { class: "group", "aria-labelledby": "dev-reports-h" },
      h("h2", { class: "group-header", id: "dev-reports-h" }, `Reports against this device (${fmtInt(reports.length)})`),
      reports.length
        ? h("div", { class: "report-list report-list-compact" }, ...reports.map((r) => reportCard(r, {
          showReported: false,
          onResolved: (updated, action) => {
            ctx.adjustOpenReports(-(1 + alsoResolves(r, action, reports).length));
            ctx.refreshStats();
            reloadDetail(host, d.id, opts);
          },
          onStale: () => {
            ctx.refreshStats();
            reloadDetail(host, d.id, opts);
          },
        })))
        : h("div", { class: "list" }, row({ title: h("span", { class: "muted" }, "No reports") })));

    // Deleted meanwhile: drop it from the list and show the "doesn't exist" page.
    const afterGone = () => {
      dropDevice(d.id);
      loadDetail(host, d.id, opts);
    };
    const afterChange = (result) => {
      const updated = result?.device ? { ...d, ...result.device } : null;
      if (updated) {
        patchDevice(updated);
        showDetail(host, { ...data, device: updated }, opts);
      } else {
        reloadDetail(host, d.id, opts);
      }
    };

    const actionsGroup = group({
      header: "Actions",
      footer: "Changes apply right away on the live server.",
    },
    row({ leading: iconSquare("reset", "blue"), title: "Reset names", subtitle: "Owner “Slime friend”, slime “Slime”", onClick: () => confirmAndRun("rename", d, afterChange, afterGone) }),
    d.banned
      ? row({ leading: iconSquare("checkCircle", "green"), title: "Unban device", subtitle: "Allow online features again", onClick: () => confirmAndRun("unban", d, afterChange, afterGone) })
      : row({ leading: iconSquare("ban", "orange"), title: "Ban device", subtitle: "Block friends, chat and every online feature", onClick: () => confirmAndRun("ban", d, afterChange, afterGone) }),
    row({
      leading: iconSquare("trash", "red"), title: "Delete device", subtitle: "Remove the device and all its online data", tone: "danger",
      onClick: () => confirmAndRun("delete", d, () => {
        dropDevice(d.id);
        if (mode === "split") {
          selected = null;
          history.replaceState(null, "", `#/devices${query()}`);
          showPlaceholder();
          (listEl.querySelector("a") ?? input).focus({ preventScroll: true });
        } else {
          ctx.navigate(`#/devices${query()}`);
        }
      }),
    }));

    // Best scores the app uploaded (PUT /me/records); older servers don't send them.
    const records = Array.isArray(data.records) ? data.records.filter((r) => r && typeof r.best === "number") : null;
    const recordsGroup = records
      ? group({ header: `Best scores (${fmtInt(records.length)})` },
        ...(records.length
          ? [...records].sort((a, b) => gameLabel(a.game).localeCompare(gameLabel(b.game)))
            .map((r) => row({ title: gameLabel(r.game), value: fmtInt(r.best) }))
          : [row({ title: h("span", { class: "muted" }, "No scores uploaded yet") })]))
      : null;

    return h("div", { class: "detail" }, hero, slimeGroup, deviceGroup, careGroup(data), friendsGroup, recordsGroup, reportsSection, actionsGroup);
  }

  async function reloadDetail(host, id, opts) {
    try {
      const data = await api.device(id, { signal: detailScope.signal });
      patchDevice(data.device);
      await showDetail(host, data, opts);
    } catch (err) {
      if (isAbort(err) || err.handled) return;
      toast(err.message, { tone: "error" });
    }
  }

  async function loadDetail(host, id, opts) {
    detailScope.abort();
    detailScope = childAbort(ctx.signal);
    const signal = detailScope.signal;
    host.setAttribute("aria-busy", "true");
    replace(host, h("div", { class: "detail detail-loading" },
      h("div", { class: "card hero" }, h("span", { class: "skeleton skeleton-hero" }),
        h("div", { class: "hero-text" }, h("span", { class: "skeleton skeleton-line", style: { width: "50%" } }),
          h("span", { class: "skeleton skeleton-line skeleton-line-small", style: { width: "70%" } }))),
      skeletonList(4)));
    try {
      const data = await api.device(id, { signal });
      if (!signal.aborted) await showDetail(host, data, opts);
    } catch (err) {
      if (isAbort(err) || err.handled) return;
      host.setAttribute("aria-busy", "false");
      opts?.onTitle?.("Device");
      if (err.status === 404) {
        replace(host, emptyState({
          title: "This device doesn't exist anymore",
          message: "It may have been deleted, by its owner in the app or here.",
          anim: "confused",
          signal: detailScope.signal,
          action: h("a", { class: "btn btn-secondary", href: `#/devices${query()}` }, icon("chevronLeft"), h("span", { class: "btn-label" }, "All devices")),
        }));
      } else {
        replace(host, errorState({ message: err.message, onRetry: () => loadDetail(host, id, opts) }));
      }
    }
  }

  /* ---------- Layout per mode ---------- */

  const refresh = iconButton("refresh", "Refresh", {
    onClick: () => {
      if (mode !== "detail") loadList();
      if (selected && detailHost) loadDetail(detailHost, selected, detailOpts);
    },
  });
  ctx.setActions(refresh);

  let detailHost = null;
  let detailOpts = {};

  function showPlaceholder() {
    ctx.setTitle("Devices");
    detailScope.abort();
    detailScope = childAbort(ctx.signal);
    for (const el of listEl.querySelectorAll("[aria-current]")) el.removeAttribute("aria-current");
    replace(detailHost, h("div", { class: "detail-placeholder" }, emptyState({
      title: "Choose a device",
      message: "Pick one from the list to see its slime, friends and reports.",
      signal: detailScope.signal,
    })));
  }

  if (mode === "detail") {
    ctx.setBack({ href: `#/devices${query()}`, label: "Devices" });
    detailHost = h("div", { class: "detail-host" });
    detailOpts = {
      headingLevel: 1,
      onTitle: (title, node) => {
        const heading = node?.querySelector(".hero-title");
        if (heading) ctx.observeTitle(heading, title);
        ctx.setTitle(title, `${title} · Devices`);
      },
    };
    ctx.root.append(detailHost);
    ctx.setTitle("Device", "Device · Devices");
    loadDetail(detailHost, selected, detailOpts);
  } else {
    const head = ctx.header({ title: "Devices" });
    const listPane = h("div", { class: "list-pane" }, searchEl, h("div", { class: "list-tools" }, caption, filter.el), listEl, more);
    if (mode === "split") {
      ctx.root.classList.add("view-wide");
      detailHost = h("div", { class: "detail-pane" });
      detailOpts = { headingLevel: 2, onTitle: (title) => ctx.setTitle("Devices", `${title} · Devices`) };
      ctx.root.append(head.el, h("div", { class: "split" }, listPane, detailHost));
      if (selected) loadDetail(detailHost, selected, detailOpts);
      else showPlaceholder();
    } else {
      ctx.root.append(head.el, listPane);
    }
    if (!useCache()) loadList();
    else if (mode === "list" && listCache?.scrollY) {
      const y = listCache.scrollY;
      requestAnimationFrame(() => window.scrollTo(0, y));
    }
  }

  return {
    update(route) {
      const nextSel = route.parts[0] ?? null;
      const nextQ = (route.query.get("q") ?? "").trim();
      const nextBanned = route.query.get("banned") === "1";
      const nextMode = WIDE.matches ? "split" : nextSel ? "detail" : "list";
      if (nextMode !== mode) return false;
      if (mode === "detail") return nextSel === selected;
      if (nextQ !== q || nextBanned !== bannedOnly) {
        q = nextQ;
        bannedOnly = nextBanned;
        input.value = q;
        clearBtn.hidden = !q;
        filter.set(bannedOnly ? "banned" : "all");
        loadList();
      }
      if (mode === "split" && nextSel !== selected) {
        selected = nextSel;
        for (const el of listEl.querySelectorAll("[data-id]")) {
          const link = el.querySelector("a");
          if (el.dataset.id === selected) link?.setAttribute("aria-current", "true");
          else link?.removeAttribute("aria-current");
        }
        if (selected) {
          loadDetail(detailHost, selected, detailOpts);
          detailHost.scrollTop = 0;
        } else {
          showPlaceholder();
        }
      }
      return true;
    },
    refresh: () => refresh.click(),
    dispose() {
      if (mode === "list" && listCache) listCache.scrollY = window.scrollY;
    },
  };
}
