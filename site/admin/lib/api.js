// Talks to /api/v1/admin. Session is an HttpOnly cookie; every write carries
// the X-Slimy-Admin header the server requires against cross-site requests.

const BASE = "/api/v1/admin";
const TIMEOUT_MS = 15000;

const FALLBACK = {
  0: "Can't reach the server. Check your connection and try again.",
  400: "The server didn't accept that request.",
  401: "Your session has ended. Sign in again.",
  403: "The server refused that request.",
  404: "That doesn't exist anymore.",
  409: "That already changed. Reload and try again.",
  413: "That request is too large.",
  429: "Too many requests. Wait a moment and try again.",
  500: "Something went wrong on the server. Try again later.",
  502: "The server isn't answering right now. Try again in a moment.",
  503: "The server isn't available right now. Try again in a moment.",
  504: "The server took too long to answer. Try again.",
};

export class ApiError extends Error {
  constructor(status, message, kind = "http") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.kind = kind;
    /** Set when the app already reacted (sign-in shown), so screens stay quiet. */
    this.handled = false;
  }
}

let handlers = { unauthorized: null, notSetUp: null };

/** The shell decides what a lost session or a missing passphrase means. */
export function setHandlers(next) {
  handlers = { ...handlers, ...next };
}

/** 503 from the admin API means "no passphrase yet"; a 503 from the proxy doesn't. */
export function isNotSetUp(err) {
  return err instanceof ApiError && err.status === 503 && err.kind === "not-set-up";
}

export async function request(path, { method = "GET", body, signal, auth = true } = {}) {
  const ctrl = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    ctrl.abort();
  }, TIMEOUT_MS);
  const forward = () => ctrl.abort();
  if (signal?.aborted) ctrl.abort();
  signal?.addEventListener("abort", forward, { once: true });

  const headers = { Accept: "application/json" };
  if (method !== "GET" && method !== "HEAD") headers["X-Slimy-Admin"] = "1";
  if (body !== undefined) headers["Content-Type"] = "application/json";

  try {
    let res;
    try {
      res = await fetch(BASE + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        signal: ctrl.signal,
      });
    } catch (err) {
      if (signal?.aborted) throw new DOMException("Left the screen", "AbortError");
      if (timedOut) throw new ApiError(0, FALLBACK[504], "network");
      throw new ApiError(0, FALLBACK[0], "network");
    }

    if (res.status === 204) return null;
    let data = null;
    const type = res.headers.get("content-type") ?? "";
    if (type.includes("application/json")) {
      try {
        data = await res.json();
      } catch {
        data = null;
      }
    }
    if (res.ok) {
      if (data === null) throw new ApiError(res.status, "The server sent an answer this page can't read.", "format");
      return data;
    }

    const message = typeof data?.error === "string" && data.error.trim() ? data.error.trim() : FALLBACK[res.status] ?? FALLBACK[500];
    const notSetUp = res.status === 503 && /set up|admin-cli/i.test(data?.error ?? "");
    const err = new ApiError(res.status, message, notSetUp ? "not-set-up" : "http");
    if (notSetUp && handlers.notSetUp) {
      err.handled = true;
      handlers.notSetUp();
    } else if (res.status === 401 && auth && handlers.unauthorized) {
      err.handled = true;
      handlers.unauthorized();
    }
    throw err;
  } catch (err) {
    if (signal?.aborted && !(err instanceof ApiError)) throw new DOMException("Left the screen", "AbortError");
    throw err;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", forward);
  }
}

const enc = (value) => encodeURIComponent(String(value));

function query(params) {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") q.set(key, String(value));
  }
  const text = q.toString();
  return text ? `?${text}` : "";
}

export const api = {
  session: (opts) => request("/session", { ...opts, auth: false }),
  login: (password) => request("/login", { method: "POST", body: { password }, auth: false }),
  logout: () => request("/logout", { method: "POST", auth: false }),
  stats: (opts) => request("/stats", opts),
  reports: ({ status, limit = 50, before } = {}, opts) => request(`/reports${query({ status, limit, before })}`, opts),
  resolveReport: (id, action) => request(`/reports/${enc(id)}/resolve`, { method: "POST", body: { action } }),
  devices: ({ q, limit = 50, before, banned } = {}, opts) =>
    request(`/devices${query({ q, limit, before, banned: banned ? 1 : undefined })}`, opts),
  device: (id, opts) => request(`/devices/${enc(id)}`, opts),
  ban: (id) => request(`/devices/${enc(id)}/ban`, { method: "POST" }),
  unban: (id) => request(`/devices/${enc(id)}/unban`, { method: "POST" }),
  rename: (id) => request(`/devices/${enc(id)}/rename`, { method: "POST" }),
  deleteDevice: (id) => request(`/devices/${enc(id)}`, { method: "DELETE" }),
  chatUsage: (days, opts) => request(`/chat-usage${query({ days })}`, opts),
};
