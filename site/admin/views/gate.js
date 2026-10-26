// Screens before the admin opens: sign in, "isn't set up", can't reach server.

import { api, isNotSetUp } from "../lib/api.js";
import { h, icon } from "../lib/dom.js";
import { button, commandBlock, withBusy, toast } from "../lib/ui.js";
import { mountSlime } from "../lib/slime.js";

const SETUP_COMMAND = "node src/admin-cli.js passwd";

function wordmark() {
  return h("p", { class: "wordmark wordmark-large" },
    h("span", { class: "wordmark-name" }, "slimy"),
    h("span", { class: "wordmark-by" }, "by hyprnode"));
}

function speech(text) {
  return h("p", { class: "speech", "aria-hidden": "true" }, text);
}

function frame(kind, ...children) {
  return h("main", { class: `gate gate-${kind}` }, h("div", { class: "gate-card" }, ...children));
}

/** Builds the gate screen. Returns { el, kind, title, focus, dispose }. */
export function gateScreen(kind, opts) {
  if (kind === "not-set-up") return notSetUp(opts);
  if (kind === "forbidden") return forbidden(opts);
  if (kind === "unreachable") return unreachable(opts);
  if (kind === "unavailable") return unavailable(opts);
  return signIn(opts);
}

function signIn({ notice, onSignedIn, onNotSetUp }) {
  const art = h("div", { class: "gate-art" });
  const slime = mountSlime(art, { skin: "lime", stage: "young", anim: "idle", maxScale: 5 });
  slime.play("wave", 1);
  const bubble = speech("Hi! Who's there?");

  const input = h("input", {
    id: "passphrase", class: "field-input", type: "password", name: "password", autocomplete: "current-password",
    autocapitalize: "off", spellcheck: "false", required: true, "aria-describedby": "signin-error signin-caps",
  });
  const toggle = h("button", { type: "button", class: "field-accessory", "aria-label": "Show passphrase", "aria-pressed": "false" }, icon("eye"));
  toggle.addEventListener("click", () => {
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    toggle.setAttribute("aria-pressed", String(show));
    toggle.setAttribute("aria-label", show ? "Hide passphrase" : "Show passphrase");
    toggle.replaceChildren(icon(show ? "eyeOff" : "eye"));
    input.focus();
  });

  const caps = h("p", { id: "signin-caps", class: "field-hint", hidden: true }, icon("warning"), "Caps Lock is on.");
  const updateCaps = (event) => {
    if (typeof event.getModifierState === "function") caps.hidden = !event.getModifierState("CapsLock");
  };
  input.addEventListener("keydown", updateCaps);
  input.addEventListener("keyup", updateCaps);
  input.addEventListener("blur", () => {
    caps.hidden = true;
  });

  const error = h("p", { id: "signin-error", class: "field-error", role: "alert", hidden: true });
  const showError = (text) => {
    error.textContent = text;
    error.hidden = false;
    input.setAttribute("aria-invalid", "true");
  };
  input.addEventListener("input", () => {
    error.hidden = true;
    input.removeAttribute("aria-invalid");
  });

  const submit = button("Sign in", { kind: "primary", type: "submit", block: true });
  // Lets password managers file the passphrase under a stable name.
  const username = h("input", {
    type: "text", name: "username", autocomplete: "username", value: "slimy-admin", readonly: true,
    tabindex: "-1", "aria-hidden": "true", class: "sr-only",
  });

  const form = h("form", { class: "gate-form", novalidate: true, "aria-labelledby": "signin-title" },
    username,
    h("label", { class: "field-label", for: "passphrase" }, "Passphrase"),
    h("div", { class: "field" }, input, toggle),
    caps,
    error,
    submit);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const password = input.value;
    if (!password) {
      showError("Enter the admin passphrase.");
      input.focus();
      return;
    }
    try {
      await withBusy(submit, () => api.login(password));
      input.value = "";
      onSignedIn();
    } catch (err) {
      if (isNotSetUp(err)) {
        if (!err.handled) onNotSetUp();
        return;
      }
      showError(err?.message ?? "Couldn't sign in. Try again.");
      bubble.textContent = err?.status === 401 ? "Hmm, that's not it." : err?.status === 429 ? "Let's take a little break." : "Uh-oh.";
      slime.play(err?.status === 401 ? "confused" : "sad", 2);
      input.focus();
      input.select();
    }
  });

  const noticeEl = notice
    ? h("p", { class: `gate-notice gate-notice-${notice.tone ?? "info"}`, role: "status" },
      icon(notice.tone === "warning" ? "clock" : "checkCircle"), notice.text)
    : null;

  const el = frame("signin",
    h("div", { class: "gate-hero" }, bubble, art),
    wordmark(),
    h("h1", { class: "gate-title", id: "signin-title" }, "Admin"),
    h("p", { class: "gate-text" }, "Sign in with the admin passphrase."),
    noticeEl,
    form,
    h("p", { class: "gate-foot" }, icon("home"), "Only reachable from your home network and Tailscale."));

  return {
    el,
    kind: "sign-in",
    title: "Sign in",
    focus: () => input.focus(),
    dispose: () => slime.dispose(),
  };
}

function notSetUp({ onSignIn, onSignedIn }) {
  const art = h("div", { class: "gate-art" });
  const slime = mountSlime(art, { skin: "lime", stage: "young", anim: "sleep", maxScale: 5 });
  const check = button("Check again", { kind: "primary", iconName: "refresh", block: true });
  check.addEventListener("click", () => withBusy(check, async () => {
    try {
      await api.session();
      onSignedIn();
    } catch (err) {
      if (err?.status === 401) onSignIn({ tone: "info", text: "The passphrase is set. Sign in to continue." });
      else if (isNotSetUp(err)) toast("Still not set up. Run the command on the server first.");
      else toast(err?.message ?? "Couldn't check. Try again.", { tone: "error" });
    }
  }));

  const el = frame("setup",
    h("div", { class: "gate-hero" }, speech("Zzz…"), art),
    wordmark(),
    h("h1", { class: "gate-title", tabindex: "-1" }, "Admin isn't set up yet"),
    h("p", { class: "gate-text" }, "Set a passphrase on the server to turn on this page."),
    h("ol", { class: "steps" },
      h("li", null, h("span", { class: "step-text" }, "Open a shell on the server and go to the Slimy ", h("code", null, "server"), " folder.")),
      h("li", null, h("span", { class: "step-text" }, "Run this command and enter a long passphrase twice. What you type stays hidden."),
        commandBlock(SETUP_COMMAND)),
      h("li", null, h("span", { class: "step-text" }, "Come back here and sign in."))),
    check,
    h("p", { class: "gate-foot" }, icon("lock"),
      "The server keeps only a scrypt hash of the passphrase, in /etc/slimy/admin.hash or the file ADMIN_HASH_FILE names."));

  return {
    el,
    kind: "not-set-up",
    title: "Set up",
    focus: () => el.querySelector(".gate-title")?.focus({ preventScroll: true }),
    dispose: () => slime.dispose(),
  };
}

function problem(kind, { iconName, title, text, onRetry }) {
  const retry = button("Try again", { kind: "primary", iconName: "refresh", block: true });
  retry.addEventListener("click", () => withBusy(retry, async () => onRetry()));
  const el = frame(kind,
    h("span", { class: "gate-icon" }, icon(iconName)),
    h("h1", { class: "gate-title", tabindex: "-1" }, title),
    h("p", { class: "gate-text" }, text),
    retry);
  return {
    el,
    kind,
    title,
    focus: () => el.querySelector(".gate-title")?.focus({ preventScroll: true }),
  };
}

function unreachable({ message, onRetry }) {
  return problem("unreachable", {
    iconName: "wifiOff",
    title: "Can't reach the server",
    text: message ?? "Check your connection and try again. The admin only answers on your home network and Tailscale.",
    onRetry,
  });
}

/** 403 before signing in: this address is outside ADMIN_NETWORKS (or a proxy dropped the header). */
function forbidden({ message, onRetry }) {
  return problem("forbidden", {
    iconName: "lock",
    title: "Not available from here",
    text: message ?? "The admin is only reachable from the home network and Tailscale. Connect to one of them and try again.",
    onRetry,
  });
}

function unavailable({ onRetry }) {
  return problem("unavailable", {
    iconName: "server",
    title: "No admin API on this server",
    text: "This server doesn't answer on /api/v1/admin. Update the Slimy server to 1.0 or later, then try again.",
    onRetry,
  });
}
