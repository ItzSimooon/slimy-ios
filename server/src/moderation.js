// Moderation for everything people type that someone else (or the AI) sees:
// owner and slime names, chat messages and the slime's replies.
//
// The word lists are matched after folding the text: lowercase, accents and
// look-alike letters removed, leetspeak mapped (sh1t, a$$), repeated letters
// allowed (fuuuck) and spacing tricks joined (f u c k, f.u.c.k). Short or
// ambiguous words only match as whole words, so "Cassandra", "Titan",
// "therapist" or "Scunthorpe" stay fine. Everything here is pure.

/** Matched anywhere, even inside a longer word or across spaces. */
const ANYWHERE = [
  // English
  "fuck", "phuck", "cunt", "bitch", "bastard", "asshole", "arsehole", "bullshit", "dickhead", "cocksucker",
  "whore", "slut", "nigger", "nigga", "faggot", "retard", "wetback", "raghead", "towelhead", "hitler", "neonazi",
  "siegheil", "penis", "vagina", "porn", "hentai", "dildo", "blowjob", "handjob", "cumshot", "jizz",
  "pedophil", "paedophil", "molest", "killyourself", "killurself",
  // German
  "arschloch", "wichser", "wichs", "hurensohn", "fotze", "ficken", "ficker", "schlampe", "schwuchtel",
  "missgeburt", "kanake", "kanacke", "zigeuner", "judensau", "untermensch", "scheisse", "scheiss", "pimmel",
  "titten", "vergewaltig", "bringdichum",
];

/** Matched only as a whole word (plural endings allowed). */
const WHOLE_WORDS = [
  // English
  "fuk", "fck", "fcuk", "shit", "shitty", "shite", "piss", "pissed", "ass", "arse", "dick", "cock", "pussy",
  "twat", "prick", "wank", "wanker", "bollocks", "cum", "sex", "sexy", "anal", "anus", "tit", "titty", "titties",
  "boob", "milf", "horny", "nude", "nsfw", "xxx", "fag", "negro", "kike", "spic", "chink", "gook", "coon",
  "tranny", "dyke", "beaner", "rape", "raped", "raping", "rapist", "pedo", "nazi", "kkk", "kys", "stfu", "wtf",
  // German
  "arsch", "fick", "hure", "huren", "nutte", "nutten", "spast", "spasti", "spacko", "behindert", "mongo",
  "neger", "kacke", "bumsen", "vogeln", "sperma",
];

/** Innocent words that contain an ANYWHERE word. */
const ALLOWED = ["scunthorpe", "snigger", "niggard", "penistone"];

/** Real first names that are also on a list. Fine as a person's name, not elsewhere. */
const FIRST_NAMES = new Set(["dick"]);

/**
 * Real one- and two-letter words. A run made only of these ("go ok", "up or
 * no", "an us") is ordinary text, not a spaced-out word.
 */
const SHORT_WORDS = new Set([
  "a", "i", "u", "am", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if", "in", "is", "it", "me", "my", "no",
  "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we", "yo", "ya", "ha", "hm", "uh", "um", "ow", "ah", "eh",
  "ur", "du", "da", "zu", "ja", "es", "er", "im", "wo", "ab", "ob", "ei",
]);

/** Hate symbols that need no folding. */
const SYMBOLS = /[\u5350\u534d]|(?:^|\D)14\D{0,2}88(?:\D|$)/u;

/** Look-alike letters from other scripts. */
const CONFUSABLE = {
  "а": "a", "в": "b", "е": "e", "ё": "e", "к": "k", "м": "m", "н": "h", "о": "o", "р": "p", "с": "c", "т": "t",
  "у": "y", "х": "x", "і": "i", "ї": "i", "ј": "j", "ѕ": "s", "ԁ": "d", "ɡ": "g",
  "α": "a", "β": "b", "ε": "e", "η": "n", "ι": "i", "κ": "k", "ν": "v", "ο": "o", "ρ": "p", "τ": "t", "υ": "u",
  "χ": "x", "ω": "w",
  "ß": "ss", "æ": "ae", "œ": "oe", "ø": "o", "đ": "d", "ł": "l", "þ": "th", "ı": "i",
};

const LEET = { "0": "o", "3": "e", "4": "a", "5": "s", "6": "g", "7": "t", "8": "b", "9": "g", "@": "a", "$": "s", "€": "e", "!": "i", "+": "t", "¡": "i" };

/** "nigger" becomes n+i+g{2,}e+r+: letters may repeat, doubled letters must stay doubled. */
function runPattern(word) {
  let out = "";
  for (let i = 0; i < word.length;) {
    let j = i;
    while (j < word.length && word[j] === word[i]) j++;
    out += j - i === 1 ? `${word[i]}+` : `${word[i]}{${j - i},}`;
    i = j;
  }
  return out;
}

const ANYWHERE_RE = new RegExp(ANYWHERE.map(runPattern).join("|"));
const WHOLE_RE = new RegExp(`^(?:${WHOLE_WORDS.map(runPattern).join("|")})(?:e?s)?$`);
const ALLOWED_RE = new RegExp(ALLOWED.join("|"), "g");

/** Lowercase, without accents, invisible characters or look-alike letters. */
export function fold(value) {
  return String(value ?? "")
    .normalize("NFKD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/[\u00ad\u200b-\u200f\u2060\ufeff]/g, "")
    .replace(/[^\x00-\x7f]/g, (c) => CONFUSABLE[c] ?? c);
}

/** Removes control characters and bidi overrides; collapses whitespace. */
export function cleanText(value) {
  return String(value ?? "")
    .replace(/[\u0000-\u001f\u007f-\u009f\u200b\u200e\u200f\u202a-\u202e\u2060\u2066-\u2069\ufeff]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * The letters-only words of `folded`, once with "1" and "|" read as i and once
 * as l. Leetspeak needs a letter in the word: "sh1t" and "a$$" count, a plain
 * number like "55" or "$5" is just a number.
 */
function wordVariants(folded) {
  const readings = /[1|]/.test(folded) ? ["i", "l"] : ["i"];
  return readings.map((one) => folded
    .split(/\s+/)
    .map((word) => (/[a-z]/.test(word)
      ? [...word].map((c) => (c === "1" || c === "|" ? one : LEET[c] ?? c)).join("").replace(/[^a-z]/g, "")
      : ""))
    .filter(Boolean));
}

/** Single words, plus runs of one- and two-letter pieces glued back together ("f u c k", "fu ck"). */
function candidates(words) {
  const out = new Set(words);
  let run = [];
  const flush = () => {
    if (run.length > 1 && !run.every((piece) => SHORT_WORDS.has(piece))) out.add(run.join(""));
    run = [];
  };
  for (const word of words) {
    if (word.length <= 2) run.push(word);
    else flush();
  }
  flush();
  return out;
}

/**
 * The first blocked word found in `value`, or null. `joined` also reads the
 * whole text as one word ("fuc kface"); that suits short names, not
 * sentences, where "my pen is" would turn into something else. `person`
 * allows real first names such as "Dick".
 */
export function findProfanity(value, { joined = false, person = false } = {}) {
  const raw = String(value ?? "");
  if (SYMBOLS.test(raw.normalize("NFKC"))) return "hate symbol";
  for (const words of wordVariants(fold(raw))) {
    const all = candidates(words);
    if (joined) all.add(words.join(""));
    for (const candidate of all) {
      const whole = candidate.match(WHOLE_RE);
      if (whole && !(person && FIRST_NAMES.has(candidate))) return whole[0];
      const inside = candidate.replace(ALLOWED_RE, "-").match(ANYWHERE_RE);
      if (inside) return inside[0];
    }
  }
  return null;
}

const TLDS = "com|net|org|de|at|ch|eu|io|gg|me|tv|ly|co|app|dev|xyz|info|ru|uk|us|fr|nl|it|es|pl|be|link|site|online|"
  + "shop|store|live|fun|top|club|tk|ml|ga|cf|gq|to|cc|ws|ai|sh|so|biz|lol|vip|chat|social";
const URL_RE = new RegExp(`(?:https?:\\/\\/|www\\.)\\S|\\b[a-z0-9][a-z0-9-]*\\.(?:${TLDS})(?![a-z0-9-])|`
  + `\\b[a-z0-9][a-z0-9-]*\\s*(?:\\(\\s*dot\\s*\\)|\\[\\s*dot\\s*\\])\\s*(?:${TLDS})\\b`);
const EMAIL_RE = /[a-z0-9._%+-]+\s*(?:@|\(\s*at\s*\)|\[\s*at\s*\])\s*[a-z0-9-]+\s*(?:\.|\(\s*dot\s*\)|\[\s*dot\s*\])\s*[a-z]{2,}/;
const HANDLE_RE = /(?:^|[^a-z0-9_.@])@[a-z0-9_.]{2,}/;
const PHONE_RE = /(\+|\b0)?\d(?:[\s().\/-]{0,2}\d)+/g;
const SOCIAL = ["instagram", "snapchat", "tiktok", "discord", "telegram", "whatsapp", "onlyfans", "linktree", "gmail",
  "hotmail", "icloud", "outlook", "protonmail"];

const plain = (value) => String(value ?? "").normalize("NFKC").toLowerCase();

/** A web address, also written as "name (dot) com". */
export function hasLink(value) {
  return URL_RE.test(plain(value));
}

/** An email address, also written as "name (at) mail (dot) com". */
export function hasEmail(value) {
  return EMAIL_RE.test(plain(value));
}

/** An @handle for another app. */
export function hasHandle(value) {
  return HANDLE_RE.test(plain(value));
}

/**
 * A phone number. `strict` (names) flags any run of seven digits; otherwise
 * (chat) it takes nine digits, or seven that start with + or 0, so scores
 * like 1000000 still go through.
 */
export function hasPhone(value, { strict = false } = {}) {
  for (const match of plain(value).matchAll(PHONE_RE)) {
    const digits = match[0].replace(/\D/g, "").length;
    if (digits >= 9 || (digits >= 7 && (strict || match[1]))) return true;
  }
  return false;
}

/** Names of messengers and mail services, which in a name mean "contact me there". */
export function hasSocial(value) {
  const letters = fold(value).replace(/[^a-z]/g, "");
  return SOCIAL.some((word) => letters.includes(word)) || /(?:^|[^a-z])insta(?:[^a-z]|$)/.test(fold(value));
}

/**
 * Why a name can't be used, or null when it's fine: "profanity", "link",
 * "email", "phone" or "handle". `person` is for the owner's own name, where
 * real first names like "Dick" are fine.
 */
export function checkName(value, { person = false } = {}) {
  if (findProfanity(value, { joined: true, person })) return "profanity";
  if (hasEmail(value)) return "email";
  if (hasLink(value)) return "link";
  if (hasPhone(value, { strict: true })) return "phone";
  if (hasHandle(value) || hasSocial(value)) return "handle";
  return null;
}

/** What someone types to their slime: no swearing, and no private contact details. */
export function checkChatInput(value) {
  if (findProfanity(value)) return "profanity";
  if (hasEmail(value)) return "email";
  if (hasPhone(value)) return "phone";
  return null;
}

/** What the slime says back: nothing rude, and nothing that sends anyone anywhere. */
export function checkChatOutput(value) {
  if (findProfanity(value)) return "profanity";
  if (hasEmail(value)) return "email";
  if (hasLink(value)) return "link";
  if (hasPhone(value)) return "phone";
  if (hasHandle(value)) return "handle";
  return null;
}
