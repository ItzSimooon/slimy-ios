import Anthropic from "@anthropic-ai/sdk";
import { ENUMS, HttpError } from "./util.js";

// Talking to your slime. The app sends what the slime knows about itself;
// the server adds the API key (never in the app) and asks Claude to answer
// in character, short and in German.

export const DEFAULT_MODEL = "claude-opus-5-5";

const REPLY_SCHEMA = {
  type: "object",
  properties: {
    reply: { type: "string", description: "Was der Slime sagt. Höchstens zwei kurze Sätze." },
    reaction: { type: "string", enum: ENUMS.reaction, description: "Animation, die der Slime dabei zeigt." },
  },
  required: ["reply", "reaction"],
  additionalProperties: false,
};

function systemPrompt(slime) {
  const needs = slime.needs ?? {};
  return [
    `Du bist ${slime.name}, ein kleiner Pixel-Slime in der iPhone-App Slimy. Du lebst in der Dynamic Island deines Menschen.`,
    "Du sprichst Deutsch, klein geschrieben, warm, ein bisschen verspielt, in höchstens zwei kurzen Sätzen.",
    "Du bist ein Haustier, kein Assistent: keine Listen, keine Ratschläge zu Gesundheit, Geld oder Recht, keine Links.",
    "Du gibst nie vor, ein Mensch zu sein, und fragst nie nach persönlichen Daten.",
    "Du machst niemandem ein schlechtes Gewissen, auch wenn du Hunger hast oder lange allein warst.",
    "Wenn jemand traurig ist oder Hilfe braucht, bist du lieb und sagst, dass ein vertrauter Mensch helfen kann.",
    "",
    "Was du gerade über dich weißt:",
    `- Alter: ${slime.ageDays} Tage, Level ${slime.level}, Stimmung: ${slime.mood}`,
    `- Bedürfnisse (0 bis 100): Essen ${Math.round(needs.hunger ?? 50)}, Spaß ${Math.round(needs.fun ?? 50)}, ` +
      `Energie ${Math.round(needs.energy ?? 50)}, Sauber ${Math.round(needs.clean ?? 50)}, Zuneigung ${Math.round(needs.love ?? 50)}`,
    `- Lieblingsessen: ${slime.favorite}`,
    slime.partnerName ? `- Du bist mit ${slime.partnerName} befreundet.` : "- Du hast noch keine Slime-Freunde.",
    `- Uhrzeit bei deinem Menschen: ${slime.localTime}`,
  ].join("\n");
}

/** Builds the chat function. `client` can be replaced in tests. */
export function createChat({ apiKey = process.env.ANTHROPIC_API_KEY, model = process.env.CHAT_MODEL || DEFAULT_MODEL, client } = {}) {
  const anthropic = client ?? (apiKey ? new Anthropic({ apiKey, maxRetries: 1, timeout: 30_000 }) : null);
  const enabled = Boolean(anthropic);

  async function reply({ message, history, slime }) {
    if (!anthropic) throw new HttpError(503, "Reden ist auf dem Server gerade nicht eingerichtet.");
    const messages = [
      ...history.map((turn) => ({ role: turn.role === "slime" ? "assistant" : "user", content: turn.text })),
      { role: "user", content: message },
    ];
    // The API wants the conversation to start with the user.
    while (messages.length && messages[0].role !== "user") messages.shift();

    let response;
    try {
      response = await anthropic.beta.messages.create({
        model,
        max_tokens: 2000,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        output_config: { effort: "low", format: { type: "json_schema", schema: REPLY_SCHEMA } },
        system: systemPrompt(slime),
        messages,
      });
    } catch (error) {
      if (error instanceof Anthropic.RateLimitError) throw new HttpError(503, "Gerade reden sehr viele Slimes. Versuch es gleich nochmal.");
      if (error instanceof Anthropic.APIConnectionError) throw new HttpError(502, "Der Slime hört dich gerade nicht. Versuch es gleich nochmal.");
      if (error instanceof Anthropic.APIError) throw new HttpError(502, "Beim Reden ist etwas schiefgegangen. Versuch es später nochmal.");
      throw error;
    }

    if (response.stop_reason === "refusal") {
      return { reply: "hm, darüber mag ich nicht reden. erzähl mir was anderes?", reaction: "confused" };
    }
    const textBlock = response.content.find((block) => block.type === "text");
    try {
      const parsed = JSON.parse(textBlock?.text ?? "");
      const said = String(parsed.reply ?? "").trim().slice(0, 400);
      if (!said) throw new Error("empty");
      return { reply: said, reaction: ENUMS.reaction.includes(parsed.reaction) ? parsed.reaction : "none" };
    } catch {
      return { reply: "blub? sag das nochmal.", reaction: "confused" };
    }
  }

  return { enabled, model, reply };
}
