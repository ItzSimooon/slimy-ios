// DOM building without innerHTML: every string ends up as a text node or an
// attribute value, so names and report texts from users can't inject markup.

const SVG_NS = "http://www.w3.org/2000/svg";

function append(parent, children) {
  for (const child of children) {
    if (child == null || child === false || child === true) continue;
    if (Array.isArray(child)) append(parent, child);
    else if (child instanceof Node) parent.append(child);
    else parent.append(document.createTextNode(String(child)));
  }
}

function applyProps(el, props) {
  if (!props) return;
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === "class") el.setAttribute("class", value);
    else if (key === "text") el.textContent = value;
    else if (key === "dataset") Object.assign(el.dataset, value);
    else if (key === "style") Object.assign(el.style, value);
    else if (key === "value" || key === "checked") el[key] = value;
    else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value);
    else el.setAttribute(key, value === true ? "" : String(value));
  }
}

/** Creates an HTML element: `h("a", { href, class }, "text", child)`. */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  applyProps(el, props);
  append(el, children);
  return el;
}

/** Creates an SVG element with attributes. */
export function s(tag, attrs, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value == null || value === false) continue;
      if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value);
      else el.setAttribute(key, String(value));
    }
  }
  append(el, children);
  return el;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

export function replace(el, ...children) {
  clear(el);
  append(el, children);
  return el;
}

// 24 x 24 line icons, drawn for this page. Elements with `f` are filled dots.
const ICONS = {
  grid: [["rect", { x: 3.5, y: 3.5, width: 7, height: 7, rx: 2 }], ["rect", { x: 13.5, y: 3.5, width: 7, height: 7, rx: 2 }],
    ["rect", { x: 3.5, y: 13.5, width: 7, height: 7, rx: 2 }], ["rect", { x: 13.5, y: 13.5, width: 7, height: 7, rx: 2 }]],
  flag: [["path", { d: "M5.5 21V4.5" }], ["path", { d: "M5.5 4.5h12l-2.5 4.25 2.5 4.25h-12" }]],
  device: [["rect", { x: 6.5, y: 2.5, width: 11, height: 19, rx: 3 }], ["path", { d: "M10.5 18.5h3" }]],
  bubble: [["path", { d: "M20.5 11.5c0 4.4-3.8 8-8.5 8-1.3 0-2.6-.3-3.7-.8L3.5 20l1.3-3.9a7.6 7.6 0 0 1-1.3-4.6c0-4.4 3.8-8 8.5-8s8.5 3.6 8.5 8z" }]],
  info: [["circle", { cx: 12, cy: 12, r: 9 }], ["path", { d: "M12 11v5.5" }], ["circle", { cx: 12, cy: 7.6, r: 1.15, f: 1 }]],
  signout: [["path", { d: "M9.5 20.5h-3a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h3" }], ["path", { d: "M15.5 16.5 20 12l-4.5-4.5" }], ["path", { d: "M20 12H9.5" }]],
  refresh: [["path", { d: "M20 12a8 8 0 1 1-2.34-5.66" }], ["path", { d: "M20 4v4.5h-4.5" }]],
  chevronRight: [["path", { d: "m9.5 5.5 6.5 6.5-6.5 6.5" }]],
  chevronLeft: [["path", { d: "M15 4.5 7.5 12l7.5 7.5" }]],
  search: [["circle", { cx: 10.5, cy: 10.5, r: 6.5 }], ["path", { d: "m15.5 15.5 5 5" }]],
  xmark: [["path", { d: "M6.5 6.5l11 11M17.5 6.5l-11 11" }]],
  xcircle: [["circle", { cx: 12, cy: 12, r: 9.5, f: 1 }], ["path", { d: "M9 9l6 6M15 9l-6 6", class: "knock" }]],
  eye: [["path", { d: "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" }], ["circle", { cx: 12, cy: 12, r: 3 }]],
  eyeOff: [["path", { d: "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" }], ["circle", { cx: 12, cy: 12, r: 3 }],
    ["path", { d: "M4 3.5 20 20.5" }]],
  copy: [["rect", { x: 8.5, y: 8.5, width: 12, height: 12, rx: 2.5 }], ["path", { d: "M15.5 5.5v-.5a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h.5" }]],
  check: [["path", { d: "m5 12.5 4.5 4.5L19 7.5" }]],
  heart: [["path", { d: "M12 20C6.2 16.1 3 12.9 3 9.2 3 6.6 5 4.5 7.6 4.5c1.8 0 3.4 1 4.4 2.6 1-1.6 2.6-2.6 4.4-2.6C19 4.5 21 6.6 21 9.2c0 3.7-3.2 6.9-9 10.8z" }]],
  heartFill: [["path", { d: "M12 20C6.2 16.1 3 12.9 3 9.2 3 6.6 5 4.5 7.6 4.5c1.8 0 3.4 1 4.4 2.6 1-1.6 2.6-2.6 4.4-2.6C19 4.5 21 6.6 21 9.2c0 3.7-3.2 6.9-9 10.8z", f: 1 }]],
  ban: [["circle", { cx: 12, cy: 12, r: 9 }], ["path", { d: "M5.7 5.7l12.6 12.6" }]],
  trash: [["path", { d: "M4 6.5h16" }], ["path", { d: "M9 6.5V4.5h6v2" }], ["path", { d: "M6 6.5l1 13a1.5 1.5 0 0 0 1.5 1.5h7a1.5 1.5 0 0 0 1.5-1.5l1-13" }],
    ["path", { d: "M10 10.5v6.5M14 10.5v6.5" }]],
  reset: [["path", { d: "M4 12a8 8 0 1 0 2.34-5.66" }], ["path", { d: "M4 4v4.5h4.5" }]],
  shield: [["path", { d: "M12 3 19.5 6v5.5c0 4.3-3.1 8-7.5 9.5-4.4-1.5-7.5-5.2-7.5-9.5V6L12 3z" }], ["path", { d: "m8.75 12 2.25 2.25 4.25-4.5" }]],
  lock: [["rect", { x: 5, y: 10.5, width: 14, height: 10, rx: 2.5 }], ["path", { d: "M8 10.5V7.5a4 4 0 0 1 8 0v3" }]],
  home: [["path", { d: "M3.5 11 12 4l8.5 7" }], ["path", { d: "M5.5 9.5v10h13v-10" }], ["path", { d: "M10 19.5v-5h4v5" }]],
  key: [["circle", { cx: 8, cy: 15.5, r: 4.5 }], ["path", { d: "M11.2 12.3 20 3.5" }], ["path", { d: "m16.5 7 3 3M14.5 9l2 2" }]],
  clock: [["circle", { cx: 12, cy: 12, r: 9 }], ["path", { d: "M12 7v5l3.5 2" }]],
  server: [["rect", { x: 3.5, y: 4, width: 17, height: 7, rx: 2 }], ["rect", { x: 3.5, y: 13, width: 17, height: 7, rx: 2 }],
    ["circle", { cx: 7.5, cy: 7.5, r: 1, f: 1 }], ["circle", { cx: 7.5, cy: 16.5, r: 1, f: 1 }]],
  people: [["circle", { cx: 9, cy: 8, r: 3.5 }], ["path", { d: "M2.5 20c.6-3.6 3.2-6 6.5-6s5.9 2.4 6.5 6" }],
    ["path", { d: "M15.5 4.8a3.5 3.5 0 0 1 0 6.4" }], ["path", { d: "M17.5 14.3c2.2.6 3.6 2.7 4 5.7" }]],
  person: [["circle", { cx: 12, cy: 8, r: 4 }], ["path", { d: "M4.5 20.5c.8-4 3.8-6.5 7.5-6.5s6.7 2.5 7.5 6.5" }]],
  terminal: [["rect", { x: 3, y: 4, width: 18, height: 16, rx: 3 }], ["path", { d: "m7 9.5 3 2.5-3 2.5" }], ["path", { d: "M12.5 15h4.5" }]],
  sun: [["circle", { cx: 12, cy: 12, r: 4 }], ["path", { d: "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4" }]],
  moon: [["path", { d: "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" }]],
  bolt: [["path", { d: "M13 2.5 5 13.5h6l-1 8 8-11h-6l1-8z" }]],
  hash: [["path", { d: "M4.5 9h16M3.5 15h16M10 3.5 8 20.5M16 3.5l-2 17" }]],
  gamepad: [["path", { d: "M7.5 7h9a5 5 0 0 1 5 5.5l-.3 3a3 3 0 0 1-5.3 1.6L14.5 15h-5l-1.4 2.1a3 3 0 0 1-5.3-1.6l-.3-3A5 5 0 0 1 7.5 7z" }],
    ["path", { d: "M8 9.5v4M6 11.5h4" }], ["circle", { cx: 15.5, cy: 10.5, r: 1, f: 1 }], ["circle", { cx: 17.5, cy: 12.5, r: 1, f: 1 }]],
  database: [["ellipse", { cx: 12, cy: 5.5, rx: 7.5, ry: 2.75 }], ["path", { d: "M4.5 5.5v13c0 1.5 3.4 2.75 7.5 2.75s7.5-1.25 7.5-2.75v-13" }],
    ["path", { d: "M4.5 12c0 1.5 3.4 2.75 7.5 2.75s7.5-1.25 7.5-2.75" }]],
  warning: [["path", { d: "M10.3 4.3 2.8 17.5A2 2 0 0 0 4.5 20.5h15a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0z" }], ["path", { d: "M12 9.5v4.5" }],
    ["circle", { cx: 12, cy: 17.2, r: 1.1, f: 1 }]],
  checkCircle: [["circle", { cx: 12, cy: 12, r: 9 }], ["path", { d: "m8 12.25 2.75 2.75L16 9.5" }]],
  minusCircle: [["circle", { cx: 12, cy: 12, r: 9 }], ["path", { d: "M8 12h8" }]],
  wifiOff: [["path", { d: "M2.5 8.5a14 14 0 0 1 4.3-2.6M10.5 4.6A14 14 0 0 1 21.5 8.5" }], ["path", { d: "M5.5 12a9.5 9.5 0 0 1 3.4-2.1M14.5 9.7A9.5 9.5 0 0 1 18.5 12" }],
    ["path", { d: "M9 15.5a4.5 4.5 0 0 1 6 0" }], ["circle", { cx: 12, cy: 19, r: 1.1, f: 1 }], ["path", { d: "M3.5 3.5l17 17" }]],
  keyboard: [["rect", { x: 2.5, y: 6, width: 19, height: 12, rx: 2.5 }], ["path", { d: "M6.5 10h.01M10 10h.01M13.5 10h.01M17 10h.01M8 14h8" }]],
  doc: [["path", { d: "M6.5 3h7l4 4v13a1 1 0 0 1-1 1h-10a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" }], ["path", { d: "M13.5 3v4h4M8.5 12.5h7M8.5 16h5" }]],
  sparkle: [["path", { d: "M12 3.5c.6 4.4 2.1 5.9 6.5 6.5-4.4.6-5.9 2.1-6.5 6.5-.6-4.4-2.1-5.9-6.5-6.5 4.4-.6 5.9-2.1 6.5-6.5z" }]],
  quote: [["path", { d: "M5 18.5c2.5-1 3.5-3 3.5-6V8.5h-4v4h3.5M14 18.5c2.5-1 3.5-3 3.5-6V8.5h-4v4H17" }]],
};

/** Returns a decorative SVG icon. Pass `label` to make it meaningful to screen readers. */
export function icon(name, { label, className = "" } = {}) {
  const parts = ICONS[name] ?? ICONS.info;
  const svg = s("svg", {
    class: `icon ${className}`.trim(),
    viewBox: "0 0 24 24",
    width: 24,
    height: 24,
    "aria-hidden": label ? null : "true",
    role: label ? "img" : null,
    "aria-label": label ?? null,
    focusable: "false",
  });
  for (const [tag, attrs] of parts) {
    const { f, ...rest } = attrs;
    svg.append(s(tag, f ? { ...rest, class: `${rest.class ?? ""} filled`.trim() } : rest));
  }
  return svg;
}

/** Calls `fn` after `ms` of quiet. `cancel()` drops the pending call. */
export function debounce(fn, ms) {
  let timer = 0;
  const wrapped = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
  wrapped.cancel = () => clearTimeout(timer);
  return wrapped;
}

export const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");

/** True when an error came from aborting a request (leaving a screen). */
export function isAbort(err) {
  return err?.name === "AbortError";
}

/** An AbortController that also aborts when `parent` does. */
export function childAbort(parent) {
  const ac = new AbortController();
  if (parent?.aborted) ac.abort();
  else parent?.addEventListener("abort", () => ac.abort(), { once: true });
  return ac;
}

let uid = 0;
export const nextId = (prefix = "id") => `${prefix}-${++uid}`;
