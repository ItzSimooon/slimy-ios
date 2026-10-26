import { describe, test } from "node:test";
import assert from "node:assert/strict";
import {
  checkChatInput, checkChatOutput, checkName, cleanText, findProfanity, fold, hasEmail, hasHandle, hasLink, hasPhone,
} from "../src/moderation.js";

describe("fold", () => {
  test("lowercases and removes accents, invisible characters and look-alike letters", () => {
    assert.equal(fold("ÄÖÜ ß Zoë"), "aou ss zoe");
    assert.equal(fold("f\u200bu\u00adck"), "fuck");
    assert.equal(fold("ѕhіt"), "shit"); // Cyrillic s and i
    assert.equal(fold("ＦＵＣＫ"), "fuck"); // full width
  });

  test("cleanText removes control and bidi characters", () => {
    assert.equal(cleanText("  An\u202ena \u200b\n Blob\t"), "An na Blob");
    assert.equal(cleanText(null), "");
  });
});

describe("profanity", () => {
  const blocked = [
    "fuck", "FUCK", "Fuuuuck", "f u c k", "f.u.c.k", "f_u_c_k", "fu ck", "fück", "phuck", "sh1t", "$hit", "a$$", "@ss", "b1tch",
    "N1gg3r", "niiiigger", "c u n t", "pen1s", "ѕhit", "Sh1tty", "motherfucker", "dickhead",
    "Arschloch", "Hurensohn", "Scheiße", "Schei55e", "Wichser", "Fotze", "Missgeburt", "Spast", "Schlampe", "Kanake",
    "hitler", "Sieg Heil", "nazis", "kys", "Kill Yourself", "14 88", "\u5350",
  ];
  const fine = [
    "Simon", "Cassandra", "Titan", "Scunthorpe", "Therapist", "Grape", "Assassin", "Dickens", "Nigeria", "Niger",
    "Shiitake", "Fukushima", "Mongolia", "Ignazio", "Analysis", "Peacock", "Classic", "Hitchcock", "Snigger", "Sussex",
    "Kasse", "Wasser", "Klasse", "Fickle", "Bass", "Pussycat", "Annas Blob", "Bläschen", "Jörg", "Mochi", "Slime friend",
  ];

  for (const word of blocked) {
    test(`blocks ${JSON.stringify(word)}`, () => assert.ok(findProfanity(word, { joined: true }), word));
  }

  test("leaves ordinary names alone", () => {
    for (const word of fine) assert.equal(findProfanity(word, { joined: true }), null, word);
  });

  test("sentences are not glued across words", () => {
    for (const sentence of ["my pen is red", "who reads this?", "what happens is funny", "top ornament", "so do i", "is it a bit cold"]) {
      assert.equal(findProfanity(sentence), null, sentence);
    }
    // A name is short enough to read as one word.
    assert.ok(findProfanity("fuc kface", { joined: true }));
    assert.equal(findProfanity("fuc kface"), null);
  });

  test("runs of real short words and plain numbers are ordinary text", () => {
    for (const sentence of ["let's go ok?", "up or no?", "an us", "i have a $5 bill", "i got a 55 in snack catcher", "6 9"]) {
      assert.equal(findProfanity(sentence), null, sentence);
    }
    // Spaced-out letters and leetspeak inside a word still count.
    for (const sentence of ["you are a s s", "as s", "fu ck you", "a$$", "5h1t"]) assert.ok(findProfanity(sentence), sentence);
  });

  test("real first names are fine for a person, not for a slime", () => {
    assert.equal(checkName("Dick", { person: true }), null);
    assert.equal(checkName("dick"), "profanity");
    assert.equal(checkName("Dickhead", { person: true }), "profanity");
    assert.equal(checkName("Dicks", { person: true }), "profanity");
  });
});

describe("contact details", () => {
  test("links", () => {
    for (const t of ["www.example.com", "https://x.io", "discord.gg/abc", "simon.de", "slimy (dot) com", "visit spam[dot]net"]) {
      assert.ok(hasLink(t), t);
    }
    for (const t of ["Mr. Blob", "i love it. come here", "J.Lo", "3.5 stars"]) assert.equal(hasLink(t), false, t);
  });

  test("emails", () => {
    for (const t of ["simon@gmail.com", "simon (at) gmail (dot) com", "a.b+c@mail.example.de", "x [at] y.de"]) assert.ok(hasEmail(t), t);
    for (const t of ["@simon", "simon at home", "me@home"]) assert.equal(hasEmail(t), false, t);
  });

  test("phone numbers", () => {
    for (const t of ["0171 2345678", "+49 171 2345678", "(555) 123-4567", "5551234567"]) assert.ok(hasPhone(t), t);
    // Big scores and dates are fine in chat, but not in a name.
    for (const t of ["i got 1000000 points", "1.000.000", "2026-10-26", "12:30"]) assert.equal(hasPhone(t), false, t);
    assert.ok(hasPhone("1234567", { strict: true }));
    assert.equal(hasPhone("Blob 2010", { strict: true }), false);
  });

  test("handles", () => {
    assert.ok(hasHandle("@simon"));
    assert.ok(hasHandle("follow me @slimy_fan"));
    assert.equal(hasHandle("simon@gmail.com"), false);
    assert.equal(hasHandle("Bl@b"), false);
  });
});

describe("checks", () => {
  test("names: profanity, links, emails, phones, handles and messenger names", () => {
    assert.equal(checkName("Simon"), null);
    assert.equal(checkName("Blob 2010"), null);
    assert.equal(checkName("fuck"), "profanity");
    assert.equal(checkName("simon@gmail.com"), "email");
    assert.equal(checkName("simon.de"), "link");
    assert.equal(checkName("01712345678"), "phone");
    assert.equal(checkName("@simon"), "handle");
    assert.equal(checkName("insta simon"), "handle");
    assert.equal(checkName("my snapchat"), "handle");
  });

  test("chat input: swearing and private details only", () => {
    assert.equal(checkChatInput("hello! do you like www.example.com?"), null);
    assert.equal(checkChatInput("i got 1000000 points in snack catcher"), null);
    assert.equal(checkChatInput("you are a b1tch"), "profanity");
    assert.equal(checkChatInput("my email is simon@gmail.com"), "email");
    assert.equal(checkChatInput("call me 0171 2345678"), "phone");
  });

  test("chat output: nothing rude and nothing that sends anyone anywhere", () => {
    assert.equal(checkChatOutput("blub! i love cake. tell me more?"), null);
    assert.equal(checkChatOutput("i'm 3 days old and very bouncy!"), null);
    assert.equal(checkChatOutput("go to www.slimy.com"), "link");
    assert.equal(checkChatOutput("follow me @slimy"), "handle");
    assert.equal(checkChatOutput("what the fuck"), "profanity");
  });
});
