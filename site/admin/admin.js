// Slimy Admin: shell, routing and session. Screens live in ./views.
// Hash routes: #/overview, #/reports?status=open, #/devices?q=, #/devices/<id>,
// #/chat?days=30, #/about.

import { api, setHandlers, isNotSetUp } from "./lib/api.js";
import { h, icon, clear } from "./lib/dom.js";
import { iconSquare, pageHeader, toast } from "./lib/ui.js";
import { applyTheme } from "./lib/theme.js";
import { gateScreen } from "./views/gate.js";
import overviewView from "./views/overview.js";
import reportsView from "./views/reports.js";
import devicesView from "./views/devices.js";
import chatView from "./views/chat.js";
import aboutView from "./views/about.js";

const TABS = [
  { id: "overview", title: "Overview", icon: "grid", tint: "blue", view: overviewView },
  { id: "reports", title: "Reports", icon: "flag", tint: "red", view: reportsView },
  { id: "devices", title: "Devices", icon: "device", tint: "green", view: devicesView },
  { id: "chat", title: "Chat usage", short: "Chat", icon: "bubble", tint: "orange", view: chatView },
  { id: "about", title: "About", icon: "info", tint: "gray", view: aboutView },
];

const state = {
  signedIn: false,
  shell: null,
  view: null,
  viewTab: null,
  viewAbort: null,
  gate: null,
  stats: null,
  openReports: null,
  firstRoute: true,
  badgeTimer: 0,
  /** Set by the "/" shortcut from another section: focus the search once Devices is open. */
  wantSearch: false,
};

const root = h("div", { id: "root" });

/* ---------- Shell ---------- */

function wordmark() {
  return h("span", { class: "wordmark" },
    h("span", { class: "wordmark-name" }, "slimy"),
    h("span", { class: "wordmark-by" }, "by hyprnode"));
}

function badge() {
  const count = h("span", { class: "badge-count" });
  return h("span", { class: "badge", hidden: true }, count, h("span", { class: "sr-only" }, " open"));
}

function buildShell() {
  const sideItems = TABS.map((tab) => h("a", { class: "side-item", href: `#/${tab.id}`, dataset: { tab: tab.id } },
    iconSquare(tab.icon, tab.tint),
    h("span", { class: "side-label" }, tab.title),
    tab.id === "reports" ? badge() : null));

  const signOut = h("button", { type: "button", class: "side-signout" }, icon("signout"), h("span", null, "Sign out"));
  signOut.addEventListener("click", () => signOutNow());

  const sidebar = h("nav", { class: "sidebar", "aria-label": "Sections" },
    h("div", { class: "brand" }, wordmark(), h("span", { class: "brand-tag" }, "Admin")),
    h("div", { class: "side-list" }, ...sideItems),
    h("div", { class: "side-footer" }, signOut));

  const back = h("a", { class: "topbar-back", hidden: true }, icon("chevronLeft"), h("span", { class: "topbar-back-label" }));
  const title = h("div", { class: "topbar-title", "aria-hidden": "true" });
  const actions = h("div", { class: "topbar-actions" });
  const topbar = h("header", { class: "topbar" },
    h("div", { class: "topbar-inner" }, h("div", { class: "topbar-leading" }, back), title, actions));

  const view = h("main", { id: "main", class: "view", tabindex: "-1" });

  const tabItems = TABS.map((tab) => h("a", { class: "tab", href: `#/${tab.id}`, dataset: { tab: tab.id } },
    h("span", { class: "tab-icon" }, icon(tab.icon), tab.id === "reports" ? badge() : null),
    h("span", { class: "tab-label" }, tab.short ?? tab.title)));
  const tabbar = h("nav", { class: "tabbar", "aria-label": "Sections" }, ...tabItems);

  const skip = h("a", { class: "skip-link", href: "#main" }, "Skip to content");
  skip.addEventListener("click", (event) => {
    event.preventDefault();
    view.focus();
  });

  const el = h("div", { class: "shell" }, skip, sidebar, h("div", { class: "main-col" }, topbar, view), tabbar);

  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) topbar.classList.toggle("scrolled", !entry.isIntersecting);
  }, { rootMargin: "-56px 0px 0px 0px", threshold: 0 });

  return { el, view, topbar, back, title, actions, observer, navItems: [...sideItems, ...tabItems] };
}

function setActiveNav(tabId) {
  for (const item of state.shell.navItems) {
    if (item.dataset.tab === tabId) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
}

function setOpenReports(count) {
  if (typeof count !== "number" || !state.shell) return;
  state.openReports = Math.max(0, count);
  for (const el of state.shell.el.querySelectorAll(".badge")) {
    el.hidden = state.openReports === 0;
    el.querySelector(".badge-count").textContent = state.openReports > 99 ? "99+" : String(state.openReports);
  }
}

async function refreshBadge() {
  if (!state.signedIn || document.visibilityState !== "visible") return;
  try {
    const stats = await api.stats();
    state.stats = stats;
    setOpenReports(stats?.reports?.open);
  } catch {
    // The badge is a hint; screens report their own errors.
  }
}

/* ---------- Routing ---------- */

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, "");
  const [path = "", qs = ""] = raw.split("?");
  let parts;
  try {
    parts = path.split("/").filter(Boolean).map((p) => decodeURIComponent(p));
  } catch {
    parts = [];
  }
  return { tab: TABS.find((t) => t.id === parts[0]) ?? null, parts: parts.slice(1), query: new URLSearchParams(qs) };
}

function navigate(hash, { replace = false } = {}) {
  if (replace) {
    history.replaceState(null, "", hash);
    route();
  } else if (location.hash === hash) {
    route();
  } else {
    location.hash = hash;
  }
}

function route() {
  if (!state.signedIn) return;
  const r = parseHash();
  if (!r.tab) {
    history.replaceState(null, "", "#/overview");
    route();
    return;
  }
  setActiveNav(r.tab.id);
  if (state.view && state.viewTab === r.tab.id && state.view.update?.(r)) {
    if (state.wantSearch) focusSearch();
    state.wantSearch = false;
    return;
  }
  mountView(r);
}

function resetChrome() {
  const { back, title, actions, topbar } = state.shell;
  back.hidden = true;
  title.textContent = "";
  clear(actions);
  topbar.classList.remove("scrolled");
  state.shell.observer.disconnect();
}

function disposeView() {
  state.viewAbort?.abort();
  try {
    state.view?.dispose?.();
  } catch {
    // A screen failing to clean up must not block the next one.
  }
  state.view = null;
  state.viewTab = null;
  state.viewAbort = null;
}

function mountView(r) {
  disposeView();
  const { view } = state.shell;
  clear(view);
  view.className = "view";
  resetChrome();
  window.scrollTo(0, 0);
  const abort = new AbortController();
  state.viewAbort = abort;
  state.viewTab = r.tab.id;
  document.title = `${r.tab.title} · Slimy Admin`;
  const ctx = makeContext(r, abort.signal);
  try {
    state.view = r.tab.view(ctx) ?? {};
  } catch (err) {
    console.error(err);
    state.view = {};
    view.append(h("p", { class: "page-error" }, "This screen couldn't open. Reload the page."));
  }
  if (state.wantSearch && r.tab.id === "devices") focusSearch();
  else if (!state.firstRoute) ctx.focusTitle();
  state.wantSearch = false;
  state.firstRoute = false;
}

function focusSearch() {
  const search = state.shell?.view.querySelector("[data-search]");
  search?.focus();
  search?.select();
}

function makeContext(r, signal) {
  const shell = state.shell;
  let titleEl = null;
  const ctx = {
    route: r,
    signal,
    root: shell.view,
    navigate,
    get stats() {
      return state.stats;
    },
    setStats(stats) {
      state.stats = stats;
      setOpenReports(stats?.reports?.open);
    },
    get openReports() {
      return state.openReports;
    },
    setOpenReports,
    /** Re-reads the numbers after a change (badge, device total). */
    refreshStats: () => refreshBadge(),
    adjustOpenReports(delta) {
      if (typeof state.openReports === "number") setOpenReports(state.openReports + delta);
    },
    /** Large title for the screen; the top bar shows it small once it scrolls away. */
    header(opts) {
      const head = pageHeader(opts);
      ctx.observeTitle(head.titleEl, opts.title);
      return head;
    },
    observeTitle(el, text) {
      titleEl = el;
      shell.observer.disconnect();
      shell.title.textContent = text;
      shell.topbar.classList.remove("scrolled");
      shell.observer.observe(el);
    },
    setTitle(text, docTitle = text) {
      shell.title.textContent = text;
      document.title = `${docTitle} · Slimy Admin`;
    },
    setBack(back) {
      shell.back.hidden = !back;
      if (back) {
        shell.back.href = back.href;
        shell.back.querySelector(".topbar-back-label").textContent = back.label;
        shell.back.setAttribute("aria-label", `Back to ${back.label}`);
      }
    },
    setActions(...nodes) {
      clear(shell.actions).append(...nodes.filter(Boolean));
    },
    focusTitle() {
      (titleEl ?? shell.view).focus({ preventScroll: true });
    },
    signOut: () => signOutNow(),
  };
  return ctx;
}

/* ---------- Session ---------- */

function showGate(kind, opts = {}) {
  state.signedIn = false;
  clearInterval(state.badgeTimer);
  disposeView();
  state.gate?.dispose?.();
  const gate = gateScreen(kind, {
    ...opts,
    onSignedIn: () => startApp(),
    onRetry: () => boot(),
    onNotSetUp: () => showGate("not-set-up"),
    onSignIn: (notice) => showGate("sign-in", { notice }),
  });
  state.gate = gate;
  document.title = gate.title ? `${gate.title} · Slimy Admin` : "Slimy Admin";
  document.getElementById("boot")?.remove();
  clear(root).append(gate.el);
  gate.focus?.();
}

function startApp() {
  state.gate?.dispose?.();
  state.gate = null;
  state.signedIn = true;
  state.firstRoute = true;
  state.shell = buildShell();
  document.getElementById("boot")?.remove();
  clear(root).append(state.shell.el);
  if (state.openReports != null) setOpenReports(state.openReports);
  route();
  refreshBadge();
  clearInterval(state.badgeTimer);
  state.badgeTimer = setInterval(refreshBadge, 120000);
}

async function signOutNow() {
  try {
    await api.logout();
  } catch {
    // The cookie is cleared on the server when it can be reached; either way, leave.
  }
  state.stats = null;
  state.openReports = null;
  showGate("sign-in", { notice: { tone: "info", text: "You're signed out." } });
}

async function boot() {
  try {
    await api.session();
    startApp();
  } catch (err) {
    if (err?.status === 401) showGate("sign-in");
    // The api's not-set-up handler has already shown that screen.
    else if (isNotSetUp(err)) {
      if (state.gate?.kind !== "not-set-up") showGate("not-set-up");
    } else if (err?.status === 403) showGate("forbidden", { message: err.message });
    else if (err?.status === 404) showGate("unavailable");
    else showGate("unreachable", { message: err?.message });
  }
}

setHandlers({
  unauthorized: () => {
    if (!state.signedIn) return;
    showGate("sign-in", { notice: { tone: "warning", text: "Your session has ended. Sign in again." } });
  },
  notSetUp: () => {
    if (state.gate?.kind === "not-set-up") return;
    showGate("not-set-up");
  },
});

/* ---------- Keyboard ---------- */

document.addEventListener("keydown", (event) => {
  if (!state.signedIn || event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
  const target = event.target;
  if (target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))) return;
  if (document.querySelector("dialog[open]")) return;
  const index = Number(event.key) - 1;
  if (Number.isInteger(index) && index >= 0 && index < TABS.length) {
    event.preventDefault();
    navigate(`#/${TABS[index].id}`);
  } else if (event.key === "/") {
    event.preventDefault();
    if (state.viewTab === "devices" && state.shell.view.querySelector("[data-search]")) focusSearch();
    else {
      // The search exists once the Devices list mounts (on a narrow screen
      // with a device open, that is after going back to the list).
      state.wantSearch = true;
      navigate("#/devices");
    }
  } else if (event.key === "r" && !event.shiftKey) {
    if (state.view?.refresh) {
      event.preventDefault();
      state.view.refresh();
    }
  }
});

window.addEventListener("hashchange", route);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && state.signedIn) {
    refreshBadge();
    state.view?.onVisible?.();
  }
});
window.addEventListener("unhandledrejection", (event) => {
  if (event.reason?.name === "AbortError") event.preventDefault();
});

applyTheme();
document.body.prepend(root);
boot().catch(() => toast("Something went wrong. Reload the page.", { tone: "error" }));
