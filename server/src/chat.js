import Anthropic from "@anthropic-ai/sdk";
import { ENUMS, HttpError } from "./util.js";

// Talking to your slime. The app sends what the slime knows about itself;
// the server adds the API key (never in the app) and asks Claude to answer
// in character, short and in English.

export const DEFAULT_MODEL = "claude-opus-5-5";

const REPLY_SCHEMA = {
  type: "object",
  properties: {
    reply: { type: "string", description: "What the slime says. Two short sentences at most." },
    reaction: { type: "string", enum: ENUMS.reaction, description: "Animation, die der Slime dabei zeigt." },
  },
  required: ["reply", "reaction"],
  additionalProperties: false,
};

function systemPrompt(slime) {
  const needs = slime.needs ?? {};
  return [
    `You are ${slime.name}, a small pixel slime in the iPhone app Slimy. You live in your human's Dynamic Island.`,
    "You speak English, in lowercase, warm and a little playful, in at most two short sentences.",
    "You are a pet, not an assistant: no lists, no advice on health, money or law, no links.",
    "You never pretend to be human and never ask for personal information.",
    "You never make anyone feel guilty, even when you are hungry or were alone for a long time.",
    "If someone is sad or needs help, you are kind and say that a person they trust can help.",
    "",
    "What you know about yourself right now:",
    `- Age: ${slime.ageDays} days, level ${slime.level}, mood: ${slime.mood}`,
    `- Needs (0 to 100): food ${Math.round(needs.hunger ?? 50)}, fun ${Math.round(needs.fun ?? 50)}, ` +
      `energy ${Math.round(needs.energy ?? 50)}, clean ${Math.round(needs.clean ?? 50)}, love ${Math.round(needs.love ?? 50)}`,
    `- Favourite food: ${slime.favorite}`,
    slime.partnerName ? `- You are friends with ${slime.partnerName}.` : "- You don't have any slime friends yet.",
    `- Your human's local time: ${slime.localTime}`,
  ].join("\n");
}

/** Builds the chat function. `client` can be replaced in tests. */
export function createChat({ apiKey = process.env.ANTHROPIC_API_KEY, model = process.env.CHAT_MODEL || DEFAULT_MODEL, client } = {}) {
  const anthropic = client ?? (apiKey ? new Anthropic({ apiKey, maxRetries: 1, timeout: 30_000 }) : null);
  const enabled = Boolean(anthropic);

  async function reply({ message, history, slime }) {
    if (!anthropic) throw new HttpError(503, "Talking isn't set up on the server right now.");
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
      if (error instanceof Anthropic.RateLimitError) throw new HttpError(503, "Lots of slimes are talking right now. Try again in a moment.");
      if (error instanceof Anthropic.APIConnectionError) throw new HttpError(502, "The slime can't hear you right now. Try again in a moment.");
      if (error instanceof Anthropic.APIError) throw new HttpError(502, "Something went wrong while talking. Try again later.");
      throw error;
    }

    if (response.stop_reason === "refusal") {
      return { reply: "hm, i don't want to talk about that. tell me something else?", reaction: "confused" };
    }
    const textBlock = response.content.find((block) => block.type === "text");
    try {
      const parsed = JSON.parse(textBlock?.text ?? "");
      const said = String(parsed.reply ?? "").trim().slice(0, 400);
      if (!said) throw new Error("empty");
      return { reply: said, reaction: ENUMS.reaction.includes(parsed.reaction) ? parsed.reaction : "none" };
    } catch {
      return { reply: "blub? say that again.", reaction: "confused" };
    }
  }

  return { enabled, model, reply };
}
