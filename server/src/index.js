import { createServer } from "node:http";
import { createApp } from "./app.js";
import { createChat } from "./chat.js";
import { openDatabase } from "./db.js";

// Configuration comes only from the environment; see server/README.md.
const port = Number(process.env.PORT ?? 8080);
const db = openDatabase(process.env.SLIMY_DB ?? "data/slimy.db");
const chat = createChat();
const app = createApp({ db, chat, trustProxy: process.env.TRUST_PROXY === "1" });

app.cleanup();
const sweeper = setInterval(app.cleanup, 15 * 60_000);
sweeper.unref();

const server = createServer(app.listener);
server.requestTimeout = 45_000;
server.headersTimeout = 15_000;
server.listen(port, () => {
  console.log(`slimy api on :${port} (chat ${chat.enabled ? `on, ${chat.model}` : "off: ANTHROPIC_API_KEY not set"})`);
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
