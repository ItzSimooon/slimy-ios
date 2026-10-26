// Overview: the numbers from GET /admin/stats, chat requests of the last
// seven days, AI providers and the server.

import { api } from "../lib/api.js";
import { childAbort, h, icon, isAbort, replace } from "../lib/dom.js";
import { emptyState, errorState, group, iconButton, row, segmented, skeletonTiles } from "../lib/ui.js";
import { columnChart, dataTable, legend } from "../lib/chart.js";
import {
  fmtBytes, fmtDayLong, fmtDuration, fmtInt, fmtPercent, fmtTime, fmtWeekday, lastDays, plural, providerLabel,
} from "../lib/format.js";

const REFRESH_MS = 60000;
const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), [tabindex]:not([tabindex="-1"])';

function tile({ label, iconName, tint, value, sub, href, alert = false }) {
  const content = [
    h("span", { class: "tile-label" }, h("span", { class: `tile-icon tint-${tint}` }, icon(iconName)), h("span", null, label)),
    h("span", { class: "tile-value" }, value),
    sub ? h("span", { class: "tile-sub" }, sub) : null,
  ];
  const cls = `tile${alert ? " tile-alert" : ""}${href ? " tile-link" : ""}`;
  if (href) return h("a", { class: cls, href }, ...content, icon("chevronRight", { className: "tile-chevron" }));
  return h("div", { class: cls }, ...content);
}

function chatCard(stats, signal, mode, onMode) {
  const days = lastDays(7);
  const byDay = new Map((stats.chat?.last7 ?? []).map((d) => [d.day, d]));
  const requests = days.map((day) => Number(byDay.get(day)?.requests) || 0);
  const failures = days.map((day, i) => Math.min(requests[i], Number(byDay.get(day)?.failures) || 0));
  const answered = requests.map((r, i) => r - failures[i]);
  const total = requests.reduce((a, b) => a + b, 0);
  const failed = failures.reduce((a, b) => a + b, 0);

  const head = (accessory) => h("div", { class: "card-head" },
    h("div", null,
      h("h2", { class: "card-title", id: "chat7-title" }, "Chat requests"),
      h("p", { class: "card-sub" }, `Last 7 days · ${fmtInt(total)} total${total ? ` · ${fmtPercent(failed, total)} failed` : ""}`)),
    accessory);
  const link = h("a", { class: "card-link", href: "#/chat" }, "Chat usage for 30 days", icon("chevronRight"));

  // An empty axis says nothing; the sleeping slime does.
  if (total === 0) {
    return h("section", { class: "card chart-card", "aria-labelledby": "chat7-title" },
      head(null),
      emptyState({
        title: "No chats this week",
        message: "Nobody chatted with their slime in the last 7 days.",
        anim: "sleep",
        signal,
        compact: true,
      }),
      link);
  }

  const series = [
    { name: "Answered", className: "viz-ok", values: answered },
    { name: "Failed", className: "viz-fail", values: failures },
  ];
  const chart = columnChart({
    labels: days.map(fmtWeekday),
    titles: days.map(fmtDayLong),
    series,
    unit: "requests",
    label: `Chat requests over the last 7 days: ${fmtInt(total)} in total, ${fmtInt(failed)} failed. The table view lists each day.`,
    height: 200,
  });
  signal.addEventListener("abort", () => chart.dispose(), { once: true });

  const table = dataTable({
    caption: "Chat requests per day, last 7 days",
    columns: [{ label: "Day" }, { label: "Requests", numeric: true }, { label: "Answered", numeric: true }, { label: "Failed", numeric: true }],
    rows: days.map((day, i) => [fmtDayLong(day), fmtInt(requests[i]), fmtInt(answered[i]), fmtInt(failures[i])]).reverse(),
  });

  const chartView = h("div", { class: "chart-view" }, legend(series), chart.el);
  const show = (value) => {
    chartView.hidden = value !== "chart";
    table.hidden = value !== "table";
  };
  show(mode);
  const toggle = segmented({
    name: "chat7-view",
    label: "Show as",
    options: [["chart", "Chart"], ["table", "Table"]],
    value: mode,
    className: "segmented-small",
    onChange: (value) => {
      show(value);
      onMode(value);
    },
  });

  return h("section", { class: "card chart-card", "aria-labelledby": "chat7-title" },
    head(toggle.el),
    chartView,
    table,
    link);
}

function providerRows(stats) {
  const providers = stats.providers ?? [];
  const today = stats.chat?.today?.byProvider ?? {};
  if (providers.length === 0) {
    return [row({ title: h("span", { class: "muted" }, "No providers reported by the server") })];
  }
  return providers.map((p) => {
    const usage = today[p.name] ?? { requests: 0, failures: 0 };
    const status = p.configured
      ? h("span", { class: "status status-on" }, icon("checkCircle"), h("span", { class: "sr-only" }, "Configured"))
      : h("span", { class: "status status-off" }, icon("minusCircle"), h("span", { class: "sr-only" }, "Not configured"));
    return row({
      leading: status,
      title: providerLabel(p.name),
      subtitle: p.configured ? (p.model || "Default model") : "Not configured",
      value: p.configured
        ? h("span", { class: "provider-usage" },
          h("span", null, `${fmtInt(usage.requests)} today`),
          usage.failures ? h("span", { class: "provider-fail" }, `${fmtInt(usage.failures)} failed`) : null)
        : null,
      className: p.configured ? "" : "row-dim",
    });
  });
}

function render(stats, signal, chatMode, onChatMode) {
  const d = stats.devices ?? {};
  const f = stats.friendships ?? {};
  const r = stats.reports ?? {};
  const today = stats.chat?.today ?? {};
  const server = stats.server ?? {};
  const providers = stats.providers ?? [];
  const configured = providers.filter((p) => p.configured).length;
  const plays = stats.games?.plays7d;

  const tiles = h("div", { class: "tiles" },
    tile({ label: "Devices", iconName: "device", tint: "green", value: fmtInt(d.total), sub: d.new7d ? `+${fmtInt(d.new7d)} this week` : "None new this week" }),
    tile({ label: "Active today", iconName: "sparkle", tint: "blue", value: fmtInt(d.active1d), sub: `${fmtInt(d.active7d)} this week` }),
    tile({ label: "Active this month", iconName: "clock", tint: "teal", value: fmtInt(d.active30d), sub: d.total ? `${fmtPercent(d.active30d ?? 0, d.total)} of all devices` : "No devices yet" }),
    tile({ label: "Open reports", iconName: "flag", tint: "red", value: fmtInt(r.open), sub: `${fmtInt(r.total)} all time`, href: "#/reports", alert: (r.open ?? 0) > 0 }),
    tile({ label: "Friendships", iconName: "people", tint: "pink", value: fmtInt(f.total), sub: `${fmtInt(f.inLove)} in love` }),
    tile({ label: "Chat today", iconName: "bubble", tint: "orange", value: fmtInt(today.requests), sub: today.failures ? `${fmtInt(today.failures)} failed` : "No failures", href: "#/chat" }),
    tile({ label: "Events (24 h)", iconName: "bolt", tint: "indigo", value: fmtInt(stats.events24h), sub: "Gifts, visits, greetings" }),
    tile({ label: "Banned", iconName: "ban", tint: "gray", value: fmtInt(d.banned), sub: d.banned === 1 ? "Device" : "Devices", href: "#/devices?banned=1" }));

  const activity = group({ header: "Activity" },
    row({ leading: h("span", { class: "row-icon tint-teal" }, icon("hash")), title: "Friend codes created", subtitle: "Last 24 hours", value: fmtInt(stats.pairCodes24h) }),
    row({ leading: h("span", { class: "row-icon tint-green" }, icon("device")), title: "New devices", subtitle: "Last 7 days", value: fmtInt(d.new7d) }),
    row({ leading: h("span", { class: "row-icon tint-blue" }, icon("sparkle")), title: "Active devices", subtitle: "Last 7 days", value: fmtInt(d.active7d) }),
    typeof plays === "number"
      ? row({ leading: h("span", { class: "row-icon tint-blue" }, icon("gamepad")), title: "Game plays", subtitle: "Last 7 days", value: fmtInt(plays) })
      : null);

  const providerGroup = group({
    header: "AI providers",
    footer: configured
      ? "Tried in this order. Set them with environment variables on the server. Only counts are kept, never messages."
      : "No provider is configured, so the app shows “Talking isn't set up on the server right now.” Set one with environment variables on the server.",
  }, ...providerRows(stats));

  const serverGroup = group({ header: "Server" },
    row({ title: "Version", value: server.version ?? "Unknown" }),
    row({ title: "Running for", value: fmtDuration(server.uptimeSec) }),
    row({ title: "Node.js", value: server.node ?? "Unknown" }),
    row({ title: "Database", value: fmtBytes(stats.db?.bytes) }),
    row({ title: "Chat", value: configured ? `On, ${plural(configured, "provider")}` : "Off", tone: configured ? null : "danger" }));

  return [
    tiles,
    h("div", { class: "columns" },
      h("div", { class: "column" }, chatCard(stats, signal, chatMode, onChatMode), activity),
      h("div", { class: "column" }, providerGroup, serverGroup)),
  ];
}

export default function overviewView(ctx) {
  const head = ctx.header({ title: "Overview", subtitle: "Loading…" });
  const refresh = iconButton("refresh", "Refresh", { onClick: () => load() });
  ctx.setActions(refresh);
  const body = h("div", { class: "overview", "aria-busy": "true" }, skeletonTiles(8));
  ctx.root.append(head.el, body);

  let loaded = false;
  let inflight = null;
  let renderScope = childAbort(ctx.signal);
  let chatMode = "chart";

  /** Re-renders the numbers; keeps the chart/table choice and keyboard focus across the 60 s refresh. */
  function show(stats) {
    const focusables = () => [...body.querySelectorAll(FOCUSABLE)];
    const active = document.activeElement;
    const index = active && active !== body && body.contains(active) ? focusables().indexOf(active) : -1;
    renderScope.abort();
    renderScope = childAbort(ctx.signal);
    replace(body, ...render(stats, renderScope.signal, chatMode, (mode) => {
      chatMode = mode;
    }));
    body.setAttribute("aria-busy", "false");
    loaded = true;
    if (index >= 0) (focusables()[index] ?? head.titleEl).focus({ preventScroll: true });
  }

  async function load({ quiet = false } = {}) {
    inflight?.abort();
    const ac = childAbort(ctx.signal);
    inflight = ac;
    refresh.classList.add("is-spinning");
    try {
      const stats = await api.stats({ signal: ac.signal });
      ctx.setStats(stats);
      show(stats);
      head.setSubtitle(`Updated ${fmtTime(new Date())}`);
    } catch (err) {
      if (isAbort(err) || err.handled) return;
      if (quiet && loaded) {
        head.setSubtitle(`Couldn't refresh at ${fmtTime(new Date())}`);
        return;
      }
      body.setAttribute("aria-busy", "false");
      head.setSubtitle("");
      replace(body, errorState({ message: err.message, onRetry: () => load() }));
    } finally {
      if (inflight === ac) refresh.classList.remove("is-spinning");
    }
  }

  const timer = setInterval(() => {
    if (document.visibilityState === "visible") load({ quiet: true });
  }, REFRESH_MS);
  ctx.signal.addEventListener("abort", () => clearInterval(timer), { once: true });

  if (ctx.stats) show(ctx.stats);
  load({ quiet: loaded });

  return {
    refresh: () => load(),
    onVisible: () => load({ quiet: true }),
  };
}
