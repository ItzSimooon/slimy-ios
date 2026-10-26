// Chat usage per day and provider (counts only; the server never stores messages).

import { api } from "../lib/api.js";
import { childAbort, h, icon, isAbort, replace } from "../lib/dom.js";
import { emptyState, errorState, group, iconButton, row, segmented, skeletonTiles } from "../lib/ui.js";
import { columnChart, dataTable, legend } from "../lib/chart.js";
import { fmtDayLong, fmtDayShort, fmtInt, fmtPercent, lastDays, plural, providerLabel, providerShort } from "../lib/format.js";

const RANGES = [["7", "7 days"], ["14", "14 days"], ["30", "30 days"]];
const validDays = (value) => (RANGES.some(([v]) => v === value) ? Number(value) : 30);
const SLOTS = ["viz-1", "viz-2", "viz-3"];

/** Stable provider order: as configured on the server, then any others by name. */
function providerOrder(usage, stats) {
  const order = (stats?.providers ?? []).map((p) => p.name);
  const extra = [...new Set(usage.map((u) => u.provider))].filter((name) => !order.includes(name)).sort();
  return order.concat(extra);
}

function tile(label, value, sub, tint, iconName) {
  return h("div", { class: "tile" },
    h("span", { class: "tile-label" }, h("span", { class: `tile-icon tint-${tint}` }, icon(iconName)), h("span", null, label)),
    h("span", { class: "tile-value" }, value),
    sub ? h("span", { class: "tile-sub" }, sub) : null);
}

function render(days, usage, stats, signal) {
  const keys = lastDays(days);
  const index = new Map(keys.map((k, i) => [k, i]));
  const order = providerOrder(usage, stats);
  const models = new Map((stats?.providers ?? []).map((p) => [p.name, p]));

  const matrix = new Map(order.map((name) => [name, keys.map(() => ({ requests: 0, failures: 0 }))]));
  for (const u of usage) {
    const i = index.get(u.day);
    if (i === undefined || !matrix.has(u.provider)) continue;
    const cell = matrix.get(u.provider)[i];
    cell.requests += Number(u.requests) || 0;
    cell.failures += Number(u.failures) || 0;
  }

  const sum = (name, key) => matrix.get(name).reduce((a, c) => a + c[key], 0);
  const perDay = keys.map((_, i) => order.reduce((a, name) => a + matrix.get(name)[i].requests, 0));
  const failPerDay = keys.map((_, i) => order.reduce((a, name) => a + matrix.get(name)[i].failures, 0));
  const total = perDay.reduce((a, b) => a + b, 0);
  const failed = failPerDay.reduce((a, b) => a + b, 0);
  const busiest = perDay.reduce((best, v, i) => (v > perDay[best] ? i : best), 0);

  // Colour follows the provider (its place in the stable order), never its rank.
  const slotOf = (name) => SLOTS[order.indexOf(name)] ?? "viz-other";
  const active = order.filter((name) => sum(name, "requests") > 0);
  const series = active.map((name) => ({ name: providerLabel(name), className: slotOf(name), values: matrix.get(name).map((c) => c.requests) }));

  const tiles = h("div", { class: "tiles" },
    tile("Requests", fmtInt(total), `Last ${days} days`, "orange", "bubble"),
    tile("Failed", fmtInt(failed), total ? `${fmtPercent(failed, total)} of requests` : "None", "red", "warning"),
    tile("Busiest day", total ? fmtInt(perDay[busiest]) : "0", total ? fmtDayLong(keys[busiest]) : "No requests yet", "blue", "sparkle"),
    tile("Per day", fmtInt(Math.round(total / days)), "On average", "teal", "clock"));

  const out = [tiles];

  if (total === 0) {
    out.push(h("section", { class: "card" }, emptyState({
      title: "No chat requests",
      message: `Nobody chatted with their slime in the last ${days} days.`,
      anim: "sleep",
      signal,
      compact: true,
    })));
  } else {
    const chart = columnChart({
      labels: keys.map(fmtDayShort),
      titles: keys.map(fmtDayLong),
      series,
      unit: "requests",
      label: `Chat requests per day and provider, last ${days} days: ${fmtInt(total)} in total. The table below lists each day.`,
      height: 240,
    });
    signal.addEventListener("abort", () => chart.dispose(), { once: true });
    out.push(h("section", { class: "card chart-card", "aria-labelledby": "usage-chart-title" },
      h("div", { class: "card-head" },
        h("div", null,
          h("h2", { class: "card-title", id: "usage-chart-title" }, "Requests per day"),
          h("p", { class: "card-sub" }, series.length > 1 ? "Stacked by provider" : providerLabel(active[0] ?? "")))),
      series.length > 1 ? legend(series) : null,
      chart.el));
  }

  const providerList = order.filter((name) => models.get(name)?.configured || sum(name, "requests") > 0);
  out.push(group({
    header: "By provider",
    footer: "Providers are tried in order; a request that fails with one moves on to the next. Only counts are stored, never messages.",
  }, ...(providerList.length ? providerList.map((name) => {
    const req = sum(name, "requests");
    const fail = sum(name, "failures");
    const info = models.get(name);
    return row({
      leading: h("span", { class: `legend-swatch swatch-lg ${slotOf(name)}`, "aria-hidden": "true" }),
      title: providerLabel(name),
      subtitle: [info?.model, fail ? `${fmtInt(fail)} failed (${fmtPercent(fail, req)})` : "No failures"].filter(Boolean).join(" · "),
      value: h("span", { class: "provider-usage" },
        h("span", null, plural(req, "request")),
        h("span", { class: "provider-share" }, total ? `${fmtPercent(req, total)} of all` : "")),
    });
  }) : [row({ title: h("span", { class: "muted" }, "No provider is configured") })])));

  if (total > 0) {
    const columns = [{ label: "Day" }, ...active.map((name) => ({ label: providerShort(name), numeric: true })), { label: "Total", numeric: true }];
    const cell = (c) => (c.failures
      ? h("span", { class: "cell-stack" }, fmtInt(c.requests), h("span", { class: "cell-fail" }, `${fmtInt(c.failures)} failed`))
      : fmtInt(c.requests));
    const rows = keys.map((key, i) => [
      fmtDayLong(key),
      ...active.map((name) => cell(matrix.get(name)[i])),
      cell({ requests: perDay[i], failures: failPerDay[i] }),
    ]).reverse();
    rows.push(["Total", ...active.map((name) => cell({ requests: sum(name, "requests"), failures: sum(name, "failures") })),
      cell({ requests: total, failures: failed })]);
    out.push(h("section", { class: "group", "aria-labelledby": "usage-table-title" },
      h("h2", { class: "group-header", id: "usage-table-title" }, "Daily numbers"),
      h("div", { class: "card card-flush" }, dataTable({ caption: `Chat requests per day and provider, last ${days} days`, columns, rows, className: "table-totals" }))));
  }
  return out;
}

export default function chatView(ctx) {
  let days = validDays(ctx.route.query.get("days"));
  const head = ctx.header({ title: "Chat usage", subtitle: "Requests per day and AI provider" });
  const refresh = iconButton("refresh", "Refresh", { onClick: () => load() });
  ctx.setActions(refresh);
  const seg = segmented({
    name: "days",
    label: "Time range",
    options: RANGES,
    value: String(days),
    onChange: (value) => ctx.navigate(`#/chat?days=${value}`, { replace: true }),
  });
  const body = h("div", { class: "usage", "aria-busy": "true" }, skeletonTiles(4));
  ctx.root.append(head.el, h("div", { class: "toolbar" }, seg.el), body);

  let inflight = null;
  let scope = childAbort(ctx.signal);

  async function load() {
    inflight?.abort();
    const ac = childAbort(ctx.signal);
    inflight = ac;
    refresh.classList.add("is-spinning");
    body.setAttribute("aria-busy", "true");
    try {
      const [usage, stats] = await Promise.all([
        api.chatUsage(days, { signal: ac.signal }),
        ctx.stats ? Promise.resolve(ctx.stats) : api.stats({ signal: ac.signal }).then((s) => {
          ctx.setStats(s);
          return s;
        }, (err) => {
          if (isAbort(err)) throw err;
          return null;
        }),
      ]);
      scope.abort();
      scope = childAbort(ctx.signal);
      replace(body, ...render(days, usage.days ?? [], stats, scope.signal));
      body.setAttribute("aria-busy", "false");
    } catch (err) {
      if (isAbort(err) || err.handled) return;
      body.setAttribute("aria-busy", "false");
      replace(body, errorState({ message: err.message, onRetry: () => load() }));
    } finally {
      if (inflight === ac) refresh.classList.remove("is-spinning");
    }
  }

  load();

  return {
    update(route) {
      const next = validDays(route.query.get("days"));
      if (next !== days) {
        days = next;
        seg.set(String(days));
        load();
      }
      return true;
    },
    refresh: () => load(),
  };
}
