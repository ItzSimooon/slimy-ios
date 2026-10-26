// About: how access works, setting the passphrase, security notes, appearance.

import { api } from "../lib/api.js";
import { h, icon, isAbort } from "../lib/dom.js";
import { commandBlock, group, iconSquare, row, segmented } from "../lib/ui.js";
import { getTheme, setTheme } from "../lib/theme.js";
import { fmtDateTime } from "../lib/format.js";

function note(iconName, tint, title, text) {
  return row({ leading: iconSquare(iconName, tint), title, subtitle: text, className: "row-note" });
}

export default function aboutView(ctx) {
  const head = ctx.header({ title: "About", subtitle: "How the admin works and how to keep it safe" });

  const access = group({ header: "How access works" },
    note("home", "green", "Home network and Tailscale only",
      "Caddy lets only addresses on your home network and Tailscale reach /admin and /api/v1/admin. Everyone else is turned away."),
    note("key", "blue", "One passphrase",
      "There are no accounts. The server keeps a scrypt hash of the passphrase in /etc/slimy/admin.hash, or the file ADMIN_HASH_FILE names."),
    note("lock", "indigo", "A 12-hour session",
      "Signing in sets an HttpOnly cookie that only this site receives. It ends after 12 hours or when you sign out."),
    note("shield", "orange", "Protected changes",
      "Every change also sends the X-Slimy-Admin header, which other websites can't add, so they can't act for you."));

  const setup = h("section", { class: "group", "aria-labelledby": "about-passwd" },
    h("h2", { class: "group-header", id: "about-passwd" }, "Set or change the passphrase"),
    h("div", { class: "card prose" },
      h("p", null, "On the server, in the Slimy ", h("code", null, "server"), " folder, run:"),
      commandBlock("node src/admin-cli.js passwd"),
      h("p", null, "It asks twice and doesn't show what you type. Running it again replaces the old passphrase.")),
    h("p", { class: "group-footer" }, "Until a passphrase is set, the admin API answers “Admin isn't set up” and this page shows how to set it."));

  const security = group({ header: "Security notes" },
    note("warning", "red", "Wrong passphrases are limited",
      "After 5 wrong tries from one address within 15 minutes, sign-in from that address pauses."),
    note("ban", "orange", "Bans apply at once",
      "A banned device gets an error on every online feature, its friend code stops working and it disappears from its friends' lists."),
    note("trash", "red", "Deleting can't be undone",
      "It removes the device, its slime, friendships, requests, events and challenges, like deleting the data in the app."),
    note("bubble", "teal", "No chat content",
      "Chat usage is counted per day and provider. Messages are never stored or logged."),
    note("key", "gray", "Use a strong passphrase",
      "Pick a long passphrase you don't use anywhere else, and sign out on computers other people use."));

  const theme = segmented({
    name: "theme",
    label: "Appearance",
    options: [["system", "System"], ["light", "Light"], ["dark", "Dark"]],
    value: getTheme(),
    onChange: (value) => setTheme(value),
  });
  const appearance = h("section", { class: "group", "aria-labelledby": "about-look" },
    h("h2", { class: "group-header", id: "about-look" }, "Appearance"),
    h("div", { class: "list" }, h("div", { class: "row row-control", role: "listitem" },
      h("span", { class: "row-leading" }, iconSquare("sun", "blue")),
      h("span", { class: "row-body" }, h("span", { class: "row-title" }, "Theme")),
      theme.el)),
    h("p", { class: "group-footer" }, "Saved in this browser only."));

  const keys = group({ header: "Keyboard", className: "keyboard-group" },
    row({ title: "Switch sections", value: h("span", { class: "keys" }, h("kbd", null, "1"), "–", h("kbd", null, "5")) }),
    row({ title: "Search devices", value: h("kbd", null, "/") }),
    row({ title: "Refresh", value: h("kbd", null, "R") }));

  const until = h("span", null, "…");
  const untilRow = row({ title: "Signed in until", value: until });
  const session = group({ header: "Session", footer: " " },
    row({
      leading: h("span", { class: "row-icon tint-red" }, icon("signout")),
      title: "Sign out",
      tone: "danger",
      onClick: () => ctx.signOut(),
      chevron: false,
    }));

  ctx.root.append(head.el, h("div", { class: "about" }, access, setup, security, appearance, keys, session));

  const footer = session.querySelector(".group-footer");
  const showVersion = (stats) => {
    const version = stats?.server?.version;
    footer.textContent = version ? `Slimy server ${version}, Node.js ${stats.server.node ?? "unknown"}` : "";
    footer.hidden = !version;
  };
  showVersion(ctx.stats);
  api.session({ signal: ctx.signal }).then((info) => {
    if (!info?.expiresAt) return;
    until.textContent = fmtDateTime(info.expiresAt);
    session.querySelector(".list")?.prepend(untilRow);
  }, () => {
    // Only a hint; a lost session is handled by the app shell.
  });
  if (!ctx.stats) {
    api.stats({ signal: ctx.signal }).then((stats) => {
      ctx.setStats(stats);
      showVersion(stats);
    }, (err) => {
      if (!isAbort(err)) footer.hidden = true;
    });
  }
  return {};
}
