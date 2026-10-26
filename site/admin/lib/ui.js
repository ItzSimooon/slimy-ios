// Shared building blocks in the style of iOS: grouped lists, segmented
// controls, sheets, toasts, empty states.

import { h, icon, nextId, reducedMotion } from "./dom.js";
import { mountSlime } from "./slime.js";

/* ---------- Buttons ---------- */

/** A button. kind: primary | secondary | danger | danger-solid | plain | quiet. */
export function button(label, { kind = "secondary", iconName, onClick, type = "button", small = false, block = false, ...rest } = {}) {
  const el = h("button", {
    type,
    class: `btn btn-${kind}${small ? " btn-small" : ""}${block ? " btn-block" : ""}`,
    onclick: onClick,
    ...rest,
  }, iconName ? icon(iconName) : null, h("span", { class: "btn-label" }, label));
  return el;
}

/** Shows a spinner on a button while `task` runs. */
export async function withBusy(btn, task) {
  if (btn.getAttribute("aria-busy") === "true") return undefined;
  btn.setAttribute("aria-busy", "true");
  btn.disabled = true;
  const spinner = h("span", { class: "spinner spinner-small", "aria-hidden": "true" });
  btn.prepend(spinner);
  try {
    return await task();
  } finally {
    spinner.remove();
    btn.removeAttribute("aria-busy");
    btn.disabled = false;
  }
}

export function iconButton(name, label, { onClick, href, className = "" } = {}) {
  const props = { class: `icon-btn ${className}`.trim(), "aria-label": label, title: label };
  if (href) return h("a", { ...props, href }, icon(name));
  return h("button", { ...props, type: "button", onclick: onClick }, icon(name));
}

/* ---------- Page header ---------- */

export function pageHeader({ title, subtitle, accessory }) {
  const titleEl = h("h1", { class: "large-title", tabindex: "-1" }, title);
  const sub = h("p", { class: "page-subtitle" }, subtitle ?? "");
  if (!subtitle) sub.hidden = true;
  const el = h("div", { class: "page-head" },
    h("div", { class: "page-head-text" }, titleEl, sub),
    accessory ? h("div", { class: "page-head-accessory" }, accessory) : null);
  return {
    el,
    titleEl,
    setSubtitle(text) {
      sub.textContent = text ?? "";
      sub.hidden = !text;
    },
  };
}

/* ---------- Grouped lists ---------- */

export function group({ header, footer, className = "", id } = {}, ...rows) {
  const headerId = header ? nextId("grp") : null;
  const list = h("div", { class: "list", role: "list" }, ...rows);
  return h("section", { class: `group ${className}`.trim(), "aria-labelledby": headerId, id },
    header ? h("h2", { class: "group-header", id: headerId }, header) : null,
    list,
    footer ? h("p", { class: "group-footer" }, footer) : null);
}

/**
 * One row of a grouped list. `href` makes it a link with a chevron,
 * `onClick` a button. `value` sits on the trailing side.
 */
export function row({ title, subtitle, value, leading, trailing, href, onClick, chevron, tone, className = "", label } = {}) {
  const body = h("span", { class: "row-body" },
    h("span", { class: "row-title" }, title),
    subtitle != null && subtitle !== "" ? h("span", { class: "row-subtitle" }, subtitle) : null);
  const parts = [
    leading ? h("span", { class: "row-leading" }, leading) : null,
    body,
    value != null && value !== "" ? h("span", { class: "row-value" }, value) : null,
    trailing ? h("span", { class: "row-trailing" }, trailing) : null,
    (href || chevron) && chevron !== false ? icon("chevronRight", { className: "row-chevron" }) : null,
  ];
  const lead = leading ? " has-leading" : "";
  const cls = `row${lead}${tone ? ` row-${tone}` : ""} ${className}`.trim();
  if (href) {
    return h("div", { role: "listitem", class: `row-wrap${lead}` },
      h("a", { class: `${cls} row-link`, href, "aria-label": label }, ...parts));
  }
  if (onClick) {
    return h("div", { role: "listitem", class: `row-wrap${lead}` },
      h("button", { type: "button", class: `${cls} row-link row-button`, onclick: onClick, "aria-label": label }, ...parts));
  }
  return h("div", { class: cls, role: "listitem" }, ...parts);
}

/** A coloured rounded square with a white glyph, like the Settings app. */
export function iconSquare(name, tint) {
  return h("span", { class: `icon-square sq-${tint}` }, icon(name));
}

/* ---------- Small things ---------- */

export function pill(text, { tone = "neutral", iconName } = {}) {
  return h("span", { class: `pill pill-${tone}` }, iconName ? icon(iconName) : null, text);
}

export function mono(text) {
  return h("span", { class: "mono" }, text);
}

/** A copy-to-clipboard button that confirms with a check mark. */
export function copyButton(text, label = "Copy") {
  const btn = h("button", { type: "button", class: "icon-btn copy-btn", "aria-label": label, title: label }, icon("copy"));
  btn.addEventListener("click", async () => {
    const ok = await copyText(text);
    btn.replaceChildren(icon(ok ? "check" : "copy"));
    btn.classList.toggle("copied", ok);
    toast(ok ? "Copied." : "Couldn't copy. Select the text instead.", { tone: ok ? "neutral" : "error" });
    setTimeout(() => {
      btn.replaceChildren(icon("copy"));
      btn.classList.remove("copied");
    }, 1600);
  });
  return btn;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = h("textarea", { class: "sr-only", readonly: true });
    area.value = text;
    document.body.append(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {
      ok = false;
    }
    area.remove();
    return ok;
  }
}

/** A terminal command in a well with a copy button. */
export function commandBlock(command) {
  return h("div", { class: "command" },
    h("code", { class: "command-text" }, h("span", { class: "command-prompt", "aria-hidden": "true" }, "$ "), command),
    copyButton(command, "Copy command"));
}

/* ---------- Segmented control (native radios, so arrow keys work) ---------- */

export function segmented({ name, label, options, value, onChange, className = "" }) {
  const group = h("div", { class: `segmented ${className}`.trim(), role: "radiogroup", "aria-label": label });
  const inputs = new Map();
  const uidName = nextId(name);
  for (const [optValue, optLabel] of options) {
    const input = h("input", { type: "radio", name: uidName, value: optValue, checked: optValue === value });
    input.addEventListener("change", () => {
      if (input.checked) onChange?.(optValue);
    });
    inputs.set(optValue, input);
    group.append(h("label", { class: "segment" }, input, h("span", null, optLabel)));
  }
  return {
    el: group,
    set(next) {
      const input = inputs.get(next);
      if (input) input.checked = true;
    },
  };
}

/* ---------- Loading, empty and error states ---------- */

export function spinner(label = "Loading") {
  return h("div", { class: "loading", role: "status" },
    h("span", { class: "spinner", "aria-hidden": "true" }), h("span", { class: "sr-only" }, label));
}

export function skeletonList(rows = 5) {
  const items = [];
  for (let i = 0; i < rows; i++) {
    items.push(h("div", { class: "row skeleton-row" },
      h("span", { class: "skeleton skeleton-avatar" }),
      h("span", { class: "row-body" },
        h("span", { class: "skeleton skeleton-line", style: { width: `${42 + ((i * 17) % 30)}%` } }),
        h("span", { class: "skeleton skeleton-line skeleton-line-small", style: { width: `${28 + ((i * 23) % 26)}%` } }))));
  }
  return h("div", { class: "list skeleton-list", "aria-hidden": "true" }, ...items);
}

export function skeletonTiles(count = 8) {
  const tiles = [];
  for (let i = 0; i < count; i++) {
    tiles.push(h("div", { class: "tile" },
      h("span", { class: "skeleton skeleton-line skeleton-line-small", style: { width: "55%" } }),
      h("span", { class: "skeleton skeleton-value" }),
      h("span", { class: "skeleton skeleton-line skeleton-line-small", style: { width: "70%" } })));
  }
  return h("div", { class: "tiles", "aria-hidden": "true" }, ...tiles);
}

/**
 * The slime and a short message. Returns the element; the slime's timer
 * stops when `signal` aborts.
 */
export function emptyState({ title, message, action, anim = "idle", skin = "lime", stage = "young", signal, compact = false }) {
  const art = h("div", { class: "empty-art" });
  const slime = mountSlime(art, { skin, stage, anim, maxScale: compact ? 3 : 4 });
  signal?.addEventListener("abort", () => slime.dispose(), { once: true });
  return h("div", { class: `empty${compact ? " empty-compact" : ""}` },
    art,
    h("h2", { class: "empty-title" }, title),
    message ? h("p", { class: "empty-message" }, message) : null,
    action ?? null);
}

/** An error with a retry button, for a whole screen or a card. */
export function errorState({ message, onRetry, compact = false }) {
  return h("div", { class: `empty error-state${compact ? " empty-compact" : ""}`, role: "alert" },
    h("span", { class: "error-icon" }, icon("warning")),
    h("h2", { class: "empty-title" }, "Couldn't load this"),
    h("p", { class: "empty-message" }, message),
    onRetry ? button("Try again", { kind: "secondary", iconName: "refresh", onClick: onRetry }) : null);
}

/* ---------- Toasts ---------- */

let toastHost;
export function toast(message, { tone = "neutral", duration } = {}) {
  if (!toastHost) {
    toastHost = h("div", { class: "toasts" });
    document.body.append(toastHost);
  }
  const el = h("div", { class: `toast toast-${tone}`, role: tone === "error" ? "alert" : "status" },
    icon(tone === "error" ? "warning" : tone === "success" ? "checkCircle" : "info"),
    h("span", null, message));
  toastHost.append(el);
  while (toastHost.children.length > 3) toastHost.firstElementChild.remove();
  requestAnimationFrame(() => el.classList.add("shown"));
  const remove = () => {
    el.classList.remove("shown");
    setTimeout(() => el.remove(), reducedMotion.matches ? 0 : 220);
  };
  setTimeout(remove, duration ?? (tone === "error" ? 6000 : 3200));
  return remove;
}

/* ---------- Confirm sheet ---------- */

/**
 * A modal sheet that asks before an action and runs it while showing
 * progress. Resolves with the action's result, or null when cancelled.
 * `typed` asks the person to type a word first.
 */
export function confirmSheet({ title, message, details, confirmLabel, destructive = false, typed, action }) {
  return new Promise((resolve) => {
    const titleId = nextId("sheet-title");
    const msgId = nextId("sheet-msg");
    const errorEl = h("p", { class: "sheet-error", role: "alert", hidden: true });
    let busy = false;

    const confirm = button(confirmLabel, { kind: destructive ? "danger-solid" : "primary", block: true });
    const cancel = button("Cancel", { kind: "quiet", block: true });

    let typedInput = null;
    if (typed) {
      const inputId = nextId("typed");
      typedInput = h("input", {
        id: inputId, class: "field-input", type: "text", autocomplete: "off", autocapitalize: "characters",
        spellcheck: "false", "aria-describedby": msgId,
      });
      confirm.disabled = true;
      typedInput.addEventListener("input", () => {
        confirm.disabled = typedInput.value.trim().toUpperCase() !== typed.toUpperCase();
        errorEl.hidden = true;
      });
    }

    const dialog = h("dialog", { class: "sheet", "aria-labelledby": titleId, "aria-describedby": msgId },
      h("div", { class: "sheet-body" },
        h("span", { class: "sheet-grabber", "aria-hidden": "true" }),
        h("h2", { class: "sheet-title", id: titleId }, title),
        h("div", { class: "sheet-message", id: msgId }, ...(Array.isArray(message) ? message : [h("p", null, message)])),
        details ?? null,
        typed ? h("label", { class: "typed-field" },
          h("span", { class: "field-label" }, "To confirm, type ", h("strong", { class: "mono" }, typed), " below."),
          h("span", { class: "field" }, typedInput)) : null,
        errorEl,
        h("div", { class: "sheet-actions" }, confirm, cancel)));

    const finish = (value) => {
      dialog.close();
      dialog.remove();
      resolve(value);
    };

    cancel.addEventListener("click", () => {
      if (!busy) finish(null);
    });
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      if (!busy) finish(null);
    });
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog && !busy) finish(null);
    });
    dialog.addEventListener("submit", (event) => event.preventDefault());
    typedInput?.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !confirm.disabled) confirm.click();
    });

    confirm.addEventListener("click", async () => {
      if (busy) return;
      busy = true;
      errorEl.hidden = true;
      cancel.disabled = true;
      try {
        const result = await withBusy(confirm, () => action());
        finish(result ?? true);
      } catch (err) {
        busy = false;
        cancel.disabled = false;
        if (err?.handled) {
          finish(null);
          return;
        }
        errorEl.textContent = err?.message ?? "Something went wrong. Try again.";
        errorEl.hidden = false;
        confirm.focus();
      }
    });

    document.body.append(dialog);
    dialog.showModal();
    (typedInput ?? cancel).focus();
  });
}
