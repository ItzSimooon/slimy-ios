#!/usr/bin/env node
import { readFileSync, statSync } from "node:fs";
import { providersFrom } from "./chat.js";
import { DEFAULT_HASH_FILE, groupId, hashPassphrase, parseHash, passphraseProblem, writeHashFile } from "./passphrase.js";

// The owner's tool for the admin passphrase. Run it on the server:
//   node src/admin-cli.js passwd    set or change the passphrase (asks twice, hidden)
//   node src/admin-cli.js status    is the admin set up, which chat providers are configured
// The file is ADMIN_HASH_FILE (default /etc/slimy/admin.hash). Load the
// service's settings with: node --env-file=/etc/slimy/slimy.env src/admin-cli.js status

const file = process.env.ADMIN_HASH_FILE || DEFAULT_HASH_FILE;
const GROUP = process.env.ADMIN_HASH_GROUP || "slimy";

function usage() {
  console.log([
    "Usage: node src/admin-cli.js <command>",
    "",
    "  passwd   Set or change the admin passphrase. Asks twice; nothing is shown while you type.",
    "  status   Show whether the admin is set up and which chat providers are configured.",
    "",
    `Passphrase file: ${file} (ADMIN_HASH_FILE)`,
  ].join("\n"));
}

/** Reads one line from the terminal without echoing it. */
function askHidden(question) {
  const { stdin, stdout } = process;
  if (!stdin.isTTY || typeof stdin.setRawMode !== "function") {
    return Promise.reject(new Error("Run this in an interactive terminal, so the passphrase never ends up in a pipe or your shell history."));
  }
  return new Promise((resolve, reject) => {
    let value = "";
    stdout.write(question);
    stdin.setRawMode(true);
    stdin.setEncoding("utf8");
    stdin.resume();
    const finish = (error) => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener("data", onData);
      stdout.write("\n");
      if (error) reject(error);
      else resolve(value);
    };
    function onData(chunk) {
      // Arrow keys and other escape sequences arrive as one chunk; ignore them.
      if (chunk.startsWith("\u001b")) return;
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n" || ch === "\u0004") return finish();
        if (ch === "\u0003") return finish(new Error("Cancelled. Nothing was changed."));
        if (ch === "\u007f" || ch === "\b") {
          value = [...value].slice(0, -1).join("");
          continue;
        }
        if (ch < " ") continue;
        value += ch;
      }
    }
    stdin.on("data", onData);
  });
}

async function passwd() {
  const first = await askHidden("New admin passphrase: ");
  const problem = passphraseProblem(first);
  if (problem) throw new Error(problem);
  const second = await askHidden("Type it again: ");
  if (first !== second) throw new Error("The two passphrases are different. Nothing was changed.");
  const { mode, gid } = writeHashFile(file, await hashPassphrase(first), GROUP);
  console.log(`Saved to ${file} (mode ${mode}). Everyone signed in to the admin is signed out.`);
  const wanted = groupId(GROUP);
  if (process.getuid && wanted !== null && gid !== wanted) {
    console.log(`The server runs as "${GROUP}" and must be able to read it: chown root:${GROUP} ${file}`);
  }
}

function status() {
  let line = null;
  let changed = null;
  let mode = null;
  try {
    line = readFileSync(file, "utf8").trim();
    const info = statSync(file);
    changed = info.mtime.toISOString().replace(/\.\d{3}Z$/, "Z");
    mode = (info.mode & 0o777).toString(8).padStart(4, "0");
  } catch (error) {
    if (error.code !== "ENOENT") console.log(`Admin passphrase: can't read ${file} (${error.code ?? error.message})`);
  }
  const parsed = line && parseHash(line);
  if (parsed) {
    console.log(`Admin passphrase: set (${file}, changed ${changed}, mode ${mode}, scrypt N=${parsed.N} r=${parsed.r} p=${parsed.p})`);
  } else if (line) {
    console.log(`Admin passphrase: ${file} exists but isn't a passphrase hash. Run: node src/admin-cli.js passwd`);
  } else {
    console.log(`Admin passphrase: not set. Run: node src/admin-cli.js passwd`);
  }
  console.log(`Admin networks: ${process.env.ADMIN_NETWORKS || "not limited here (the reverse proxy decides)"}`);
  console.log("Chat providers, tried in this order:");
  for (const p of providersFrom(process.env)) {
    console.log(`  ${p.name.padEnd(10)} ${p.configured ? `configured, model ${p.models[0]}` : "not configured"}`);
  }
}

const command = process.argv[2];
try {
  if (command === "passwd") await passwd();
  else if (command === "status") status();
  else {
    usage();
    process.exitCode = command && !["help", "--help", "-h"].includes(command) ? 1 : 0;
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
