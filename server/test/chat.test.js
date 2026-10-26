import { describe, test } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULTS, GENTLE, MAX_REPLY, SLEEPY, createChat, describeFeelings, parseEmotion, parseReply, providersFrom, systemPrompt,
} from "../src/chat.js";
import { HttpError } from "../src/util.js";

// The chat against a fake fetch: each provider answers what the test says,
// and every request is recorded.

const ALL = { CF_ACCOUNT_ID: "acc123", CF_AI_TOKEN: "cf-token", GROQ_API_KEY: "groq-key", CHAT_BASE_URL: "http://127.0.0.1:11434/v1/", CHAT_MODEL: "llama3.2" };
const SLIME = { name: "Blob", ageDays: 3, level: 2, mood: "idle", needs: { hunger: 40 }, favorite: "cake", partnerName: null, localTime: "12:00" };

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const said = (content) => json({ choices: [{ message: { role: "assistant", content } }] });

/** `script(request)` returns a Response, throws, or never resolves. */
function harness(env, script, options = {}) {
  const requests = [];
  const usage = [];
  const fetch = async (url, init) => {
    const request = { url, headers: init.headers, body: JSON.parse(init.body), signal: init.signal };
    requests.push(request);
    return script(request, requests.length);
  };
  const chat = createChat({ env, fetch, log: () => {}, random: () => 0, onUsage: (name, ok) => usage.push([name, ok]), ...options });
  const ask = (message = "hi there", history = []) => chat.reply({ message, history, slime: SLIME });
  return { chat, requests, usage, ask };
}

const provider = (request) => (request.url.includes("cloudflare") ? "cloudflare" : request.url.includes("groq") ? "groq" : "custom");

describe("providers", () => {
  test("come from the environment, in order, never showing keys", () => {
    assert.deepEqual(providersFrom({}).map((p) => p.configured), [false, false, false]);
    const { chat } = harness(ALL, () => said("{}"));
    assert.equal(chat.enabled, true);
    assert.deepEqual(chat.providers, ["cloudflare", "groq", "custom"]);
    assert.deepEqual(chat.describe(), [
      { name: "cloudflare", model: DEFAULTS.cloudflare, configured: true },
      { name: "groq", model: DEFAULTS.groq, configured: true },
      { name: "custom", model: "llama3.2", configured: true },
    ]);
    assert.doesNotMatch(JSON.stringify(chat.describe()), /token|key/);
    assert.equal(createChat({ env: {} }).enabled, false);
    // A custom server needs both a URL and a model.
    assert.equal(createChat({ env: { CHAT_BASE_URL: "http://x" } }).enabled, false);
  });

  test("Cloudflare gets its account URL, token and the model", async () => {
    const { requests, ask } = harness({ CF_ACCOUNT_ID: "acc123", CF_AI_TOKEN: "cf-token", CF_CHAT_MODEL: "@cf/test/model" },
      () => said('{"reply":"hi!","reaction":"wave"}'));
    await ask();
    const [r] = requests;
    assert.equal(r.url, "https://api.cloudflare.com/client/v4/accounts/acc123/ai/v1/chat/completions");
    assert.equal(r.headers.Authorization, "Bearer cf-token");
    assert.equal(r.body.model, "@cf/test/model");
    assert.equal(r.body.max_tokens, 300);
    assert.equal(r.body.response_format.type, "json_schema");
    // Strict structured output lists every key; the API answer leaves an unusable emotion out.
    assert.deepEqual(r.body.response_format.json_schema.schema.required, ["reply", "reaction", "emotion"]);
    assert.ok(r.signal instanceof AbortSignal);
  });

  test("Groq gets low reasoning effort, no reasoning text and 300 tokens", async () => {
    const { requests, ask } = harness({ GROQ_API_KEY: "groq-key" }, () => said('{"reply":"hi!","reaction":"wave"}'));
    await ask();
    const { url, body, headers } = requests[0];
    assert.equal(url, "https://api.groq.com/openai/v1/chat/completions");
    assert.equal(headers.Authorization, "Bearer groq-key");
    assert.equal(body.model, "openai/gpt-oss-20b");
    assert.equal(body.reasoning_effort, "low");
    assert.equal(body.include_reasoning, false);
    assert.equal(body.max_completion_tokens, 300);
  });

  test("a custom server without a key gets no Authorization header", async () => {
    const { requests, ask } = harness({ CHAT_BASE_URL: "http://127.0.0.1:11434/v1/", CHAT_MODEL: "llama3.2" }, () => said('{"reply":"hi","reaction":"none"}'));
    await ask();
    assert.equal(requests[0].url, "http://127.0.0.1:11434/v1/chat/completions");
    assert.equal(requests[0].headers.Authorization, undefined);
  });
});

describe("fallback", () => {
  test("Cloudflare 429 falls back to Groq", async () => {
    const { requests, usage, ask } = harness(ALL, (r) =>
      (provider(r) === "cloudflare" ? json({ errors: [{ message: "quota" }] }, 429) : said('{"reply":"blub, hi!","reaction":"wave"}')));
    assert.deepEqual(await ask(), { reply: "blub, hi!", reaction: "wave" });
    assert.deepEqual(requests.map(provider), ["cloudflare", "groq"]);
    assert.deepEqual(usage, [["cloudflare", false], ["groq", true]]);
  });

  test("Groq 429 on the small model retries once with the big one", async () => {
    const { requests, usage, ask } = harness({ GROQ_API_KEY: "k" }, (r) =>
      (r.body.model === "openai/gpt-oss-20b" ? json({}, 429) : said('{"reply":"big brain blub","reaction":"curious"}')));
    assert.equal((await ask()).reply, "big brain blub");
    assert.deepEqual(requests.map((r) => r.body.model), ["openai/gpt-oss-20b", "openai/gpt-oss-120b"]);
    assert.deepEqual(usage, [["groq", false], ["groq", true]]);
  });

  test("Groq 429 on both models moves on to the custom server", async () => {
    const { requests, ask } = harness({ GROQ_API_KEY: "k", CHAT_BASE_URL: "http://x/v1", CHAT_MODEL: "m" }, (r) =>
      (provider(r) === "groq" ? json({}, 429) : said('{"reply":"local blub","reaction":"none"}')));
    assert.equal((await ask()).reply, "local blub");
    assert.deepEqual(requests.map(provider), ["groq", "groq", "custom"]);
  });

  test("a server that rejects structured output is asked again without it", async () => {
    const { requests, ask } = harness({ CHAT_BASE_URL: "http://x/v1", CHAT_MODEL: "m" }, (r) =>
      (r.body.response_format ? json({ error: "response_format not supported" }, 400) : said('{"reply":"ok","reaction":"none"}')));
    assert.equal((await ask()).reply, "ok");
    assert.equal(requests.length, 2);
    assert.equal(requests[1].body.response_format, undefined);
  });

  test("a timeout counts as a failure and the next provider answers", async () => {
    const { requests, usage, ask } = harness(ALL, (r) => {
      if (provider(r) === "cloudflare") {
        return new Promise((_, reject) => r.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))));
      }
      return said('{"reply":"sorry, i was napping","reaction":"stretch"}');
    }, { timeoutMs: 30 });
    const started = Date.now();
    assert.equal((await ask()).reply, "sorry, i was napping");
    assert.ok(Date.now() - started < 2000);
    assert.deepEqual(requests.map(provider), ["cloudflare", "groq"]);
    assert.deepEqual(usage[0], ["cloudflare", false]);
  });

  test("network errors, 5xx and unusable answers fall through; all failing is 503", async () => {
    const { requests, usage, ask } = harness(ALL, (r) => {
      if (provider(r) === "cloudflare") throw new TypeError("fetch failed");
      if (provider(r) === "groq") return json({ error: "down" }, 503);
      return said("");
    });
    await assert.rejects(ask(), (error) => error instanceof HttpError && error.status === 503 && error.message === SLEEPY);
    assert.deepEqual(requests.map(provider), ["cloudflare", "groq", "custom"]);
    assert.ok(usage.every(([, ok]) => !ok));
  });

  test("an answer that isn't JSON at all is a failure", async () => {
    const { ask } = harness({ GROQ_API_KEY: "k" }, () => new Response("<html>bad gateway</html>", { status: 200 }));
    await assert.rejects(ask(), (error) => error.status === 503);
  });

  test("a usage counter that throws doesn't break the chat", async () => {
    const chat = createChat({
      env: { GROQ_API_KEY: "k" }, fetch: async () => said('{"reply":"hi","reaction":"none"}'), log: () => {},
      onUsage: () => { throw new Error("disk full"); },
    });
    assert.equal((await chat.reply({ message: "hi", slime: SLIME })).reply, "hi");
  });
});

describe("answers", () => {
  test("tolerates fences, trailing commas and extra text", async () => {
    const { ask } = harness({ GROQ_API_KEY: "k" }, () => said('Sure!\n```json\n{"reply": "hehe, that tickles", "reaction": "blush",}\n```'));
    assert.deepEqual(await ask(), { reply: "hehe, that tickles", reaction: "blush" });
  });

  test("parseReply reads objects, strings, parts and plain text", () => {
    assert.deepEqual(parseReply({ reply: "hi", reaction: "wave" }), { reply: "hi", reaction: "wave" });
    assert.deepEqual(parseReply('{"reply":"hi","reaction":"dance"}'), { reply: "hi", reaction: "none" });
    assert.deepEqual(parseReply([{ type: "text", text: '{"reply":"parts",' }, { type: "text", text: '"reaction":"zap"}' }]), { reply: "parts", reaction: "zap" });
    assert.deepEqual(parseReply("just a little blub."), { reply: "just a little blub.", reaction: "none" });
    assert.deepEqual(parseReply("{'reply': 'x'"), null);
    assert.equal(parseReply(""), null);
    assert.equal(parseReply('{"reply":"   ","reaction":"wave"}'), null);
    assert.equal(parseReply(null), null);
  });

  test("replies are capped at 240 characters", () => {
    const long = parseReply(JSON.stringify({ reply: "blub ".repeat(100), reaction: "wave" }));
    assert.ok([...long.reply].length <= MAX_REPLY);
    assert.ok(long.reply.endsWith("…"));
  });

  test("a rude or linky reply becomes a gentle line", async () => {
    for (const content of ['{"reply":"what the fuck","reaction":"zap"}', '{"reply":"visit www.example.com","reaction":"wave"}']) {
      const { ask } = harness({ GROQ_API_KEY: "k" }, () => said(content));
      assert.deepEqual(await ask(), { reply: GENTLE.output[0], reaction: "confused" });
    }
  });

  test("a refusal becomes a gentle line", async () => {
    const { ask } = harness({ GROQ_API_KEY: "k" }, () => json({ choices: [{ message: { content: null, refusal: "No." } }] }));
    assert.deepEqual(await ask(), { reply: GENTLE.refusal[0], reaction: "confused" });
  });
});

describe("input", () => {
  test("a moderated message never reaches a provider", async () => {
    const { requests, usage, ask } = harness(ALL, () => said('{"reply":"x","reaction":"none"}'));
    assert.deepEqual(await ask("you stupid b1tch"), { reply: GENTLE.profanity[0], reaction: "confused" });
    assert.deepEqual(await ask("my number is 0171 2345678"), { reply: GENTLE.phone[0], reaction: "confused" });
    assert.deepEqual(await ask("mail me: simon@gmail.com"), { reply: GENTLE.email[0], reaction: "confused" });
    assert.equal(requests.length, 0);
    assert.equal(usage.length, 0);
  });

  test("works even when no provider is set up", async () => {
    const chat = createChat({ env: {}, fetch: () => assert.fail("no fetch"), log: () => {} });
    assert.equal((await chat.reply({ message: "fuck", slime: SLIME })).reaction, "confused");
    await assert.rejects(chat.reply({ message: "hi", slime: SLIME }), (error) => error.status === 503);
  });

  test("history keeps turns that pass and starts with the user", async () => {
    const { requests, ask } = harness({ GROQ_API_KEY: "k" }, () => said('{"reply":"ok","reaction":"none"}'));
    await ask("and now?", [
      { role: "slime", text: "hello!" },
      { role: "user", text: "you are a b1tch" },
      { role: "slime", text: "hm, those words make my jelly wobble." },
      { role: "user", text: "sorry, i like cake" },
      { role: "slime", text: "cake is the best" },
    ]);
    const roles = requests[0].body.messages.map((m) => `${m.role}:${m.content.slice(0, 12)}`);
    assert.deepEqual(roles.slice(1), ["user:sorry, i lik", "assistant:cake is the ", "user:and now?"]);
  });
});

describe("prompt", () => {
  test("is English, in character, with the slime's facts and the JSON shape", () => {
    const prompt = systemPrompt({ ...SLIME, partnerName: "Mochi", needs: { hunger: "lots", fun: 140, energy: -5 } });
    assert.match(prompt, /^You are Blob, a small pixel slime/);
    assert.match(prompt, /You are friends with Mochi/);
    assert.match(prompt, /food 50, fun 100, energy 0/);
    assert.match(prompt, /"reaction"/);
    assert.match(prompt, /never ask for personal information/);
    assert.doesNotMatch(prompt, /\b(der|die|das|und|nicht|ist)\b/);
  });

  test("without a partner it says nothing about friends and keeps the facts together", () => {
    const prompt = systemPrompt(SLIME);
    assert.doesNotMatch(prompt, /friends/);
    assert.match(prompt, /- Favourite food: cake\n- Your human's local time: 12:00\n/);
    assert.match(systemPrompt({ ...SLIME, localTime: "" }), /- Favourite food: cake\n\n/);
  });

  test("no German is left in the request", async () => {
    const { requests, ask } = harness({ GROQ_API_KEY: "k" }, () => said('{"reply":"ok","reaction":"none"}'));
    await ask();
    assert.doesNotMatch(JSON.stringify(requests[0].body.response_format), /\b(die|der|das|dabei|zeigt)\b/);
  });
});

describe("emotions", () => {
  const FEELS = { ...SLIME, emotions: { joy: 0.82, sleepiness: 0.2, curiosity: 0.5, fear: 0.04 } };

  test("the schema asks for the one feeling the reply expresses", async () => {
    const { requests, ask } = harness({ GROQ_API_KEY: "k" }, () => said('{"reply":"ok","reaction":"none","emotion":{"name":"joy","intensity":0.5}}'));
    await ask();
    const schema = requests[0].body.response_format.json_schema.schema;
    assert.deepEqual(schema.properties.emotion.properties.name.enum,
      ["joy", "sadness", "anger", "fear", "love", "curiosity", "boredom", "excitement", "sleepiness"]);
    assert.equal(schema.properties.emotion.properties.intensity.type, "number");
    assert.deepEqual(schema.properties.emotion.required, ["name", "intensity"]);
    assert.equal(schema.properties.emotion.additionalProperties, false);
    assert.equal(requests[0].body.response_format.json_schema.strict, true);
  });

  test("the prompt names the feelings in plain words, strongest first, and asks to stay in the mood", () => {
    const prompt = systemPrompt(FEELS);
    // Fear at 0.04 is too faint to mention.
    assert.equal(prompt.split("\n").find((line) => line.startsWith("How you feel")),
      "How you feel right now, strongest first: very happy, curious, a little sleepy.");
    assert.match(prompt, /Speak in that mood/);
    assert.match(prompt, /words can change how you feel/);
    assert.match(prompt, /never blame your human/);
    assert.match(prompt, /"emotion": \{"name": "<one feeling>", "intensity": <0 to 1>\}/);
    assert.match(prompt, /one of: joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness/);
    assert.doesNotMatch(prompt, /\b(der|die|das|und|nicht|ist)\b/);
  });

  test("feelings: at most four, faint ones left out, calm when nothing is strong, nothing without emotions", () => {
    assert.deepEqual(describeFeelings({ sadness: 0.9, anger: 0.5, boredom: 0.36, love: 0.34, fear: 0.2, joy: 0.09 }),
      ["very sad", "grumpy", "bored", "a little loving"]);
    assert.deepEqual(describeFeelings({ excitement: 0.6, joy: 0.6 }), ["happy", "excited"]);
    assert.deepEqual(describeFeelings({ joy: "lots", love: Number.NaN }), []);
    assert.deepEqual(describeFeelings(null), []);
    assert.match(systemPrompt({ ...SLIME, emotions: { joy: 0.05 } }), /you feel calm/);
    const old = systemPrompt(SLIME);
    assert.doesNotMatch(old, /How you feel right now|you feel calm/);
    // Old apps send no emotions; the slime may still change its mood and name it.
    assert.match(old, /"emotion"/);
  });

  test("the reply carries the emotion the model named", async () => {
    const { ask } = harness({ GROQ_API_KEY: "k" },
      () => said('{"reply":"yawn... is it nap time?","reaction":"stretch","emotion":{"name":"sleepiness","intensity":0.7}}'));
    assert.deepEqual(await ask(), { reply: "yawn... is it nap time?", reaction: "stretch", emotion: { name: "sleepiness", intensity: 0.7 } });
  });

  test("no emotion, an unknown one or a moderated reply means no emotion in the answer", async () => {
    for (const content of [
      '{"reply":"hi","reaction":"wave"}',
      '{"reply":"hi","reaction":"wave","emotion":{"name":"rage","intensity":0.9}}',
      '{"reply":"hi","reaction":"wave","emotion":null}',
    ]) {
      const { ask } = harness({ GROQ_API_KEY: "k" }, () => said(content));
      assert.deepEqual(await ask(), { reply: "hi", reaction: "wave" }, content);
    }
    const { ask } = harness({ GROQ_API_KEY: "k" }, () => said('{"reply":"go to www.example.com","reaction":"wave","emotion":{"name":"joy","intensity":1}}'));
    assert.deepEqual(await ask(), { reply: GENTLE.output[0], reaction: "confused" });
  });

  test("parsing is tolerant: flat fields, everyday words, percentages, broken JSON", () => {
    assert.deepEqual(parseReply('{"reply":"hi","reaction":"wave","emotion":"Happy","intensity":"70%"}').emotion, { name: "joy", intensity: 0.7 });
    assert.deepEqual(parseReply('{"reply":"hi","emotion":{"name":"sleepy", "intensity": 0.333},}').emotion, { name: "sleepiness", intensity: 0.33 });
    assert.deepEqual(parseReply('```json\n{"reply":"boo","reaction":"hide","emotion":{"name":"fear","intensity":85}}\n```').emotion,
      { name: "fear", intensity: 0.85 });
    assert.deepEqual(parseReply({ reply: "hi", reaction: "wave", emotion: { name: "LOVE" } }).emotion, { name: "love", intensity: 0.5 });
    assert.equal(parseReply("just a little blub.").emotion, undefined);
    assert.deepEqual(parseEmotion("curious", 2.5), { name: "curiosity", intensity: 0.03 });
    assert.deepEqual(parseEmotion({ emotion: "excited", intensity: -1 }), { name: "excitement", intensity: 0 });
    assert.deepEqual(parseEmotion("bored", 1000), { name: "boredom", intensity: 1 });
    assert.equal(parseEmotion("hungry", 0.5), null);
    assert.equal(parseEmotion(42, 0.5), null);
    assert.equal(parseEmotion(null), null);
  });
});
