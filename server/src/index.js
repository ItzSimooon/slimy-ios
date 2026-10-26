import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { DEFAULT_HASH_FILE } from "./admin.js";
import { createApp } from "./app.js";
import { createChat } from "./chat.js";
import { openDatabase } from "./db.js";

// Configuration comes only from the environment; see server/README.md.
const port = Number(process.env.PORT ?? 8080);
// Only the reverse proxy on the same machine talks to the API.
const host = process.env.HOST || "127.0.0.1";
const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const db = openDatabase(process.env.SLIMY_DB ?? "data/slimy.db");
const chat = createChat();
const app = createApp({
  db,
  chat,
  trustProxy: process.env.TRUST_PROXY === "1",
  adminHashFile: process.env.ADMIN_HASH_FILE || DEFAULT_HASH_FILE,
  adminNetworks: process.env.ADMIN_NETWORKS || null,
  serverVersion: pkg.version,
});

app.cleanup();
const sweeper = setInterval(app.cleanup, 15 * 60_000);
sweeper.unref();

const server = createServer(app.listener);
server.requestTimeout = 45_000;
server.headersTimeout = 15_000;
server.listen(port, host, () => {
  const talk = chat.enabled ? `chat via ${chat.providers.join(", ")}` : "chat off: no provider configured";
  console.log(`slimy api ${pkg.version} on ${host}:${port} (${talk})`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close(() => {
      db.close();
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 10_000).unref();
  });
}
