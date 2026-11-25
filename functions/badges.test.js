const test = require("node:test");
const assert = require("node:assert");
const { earnedIds, countChanges } = require("./badges");

test("a primeira vez que alguém guarda distintivos conta a pessoa e cada distintivo", () => {
  const after = { earned: { relampagoIc: 20415, noctivago: 20400 }, updatedAt: 1 };
  assert.deepStrictEqual(countChanges(null, after), { relampagoIc: 1, noctivago: 1, _users: 1 });
});

test("só o distintivo novo soma; reescrever o mesmo não muda nada", () => {
  const before = { earned: { noctivago: 20400 }, updatedAt: 1 };
  const after = { earned: { noctivago: 20400, toupeira: 20410 }, updatedAt: 2 };
  assert.deepStrictEqual(countChanges(before, after), { toupeira: 1 });
  assert.deepStrictEqual(countChanges(after, after), {});
});

test("apagar a pessoa tira-a de tudo", () => {
  const before = { earned: { noctivago: 20400, toupeira: 20410 }, updatedAt: 1 };
  assert.deepStrictEqual(countChanges(before, null), { noctivago: -1, toupeira: -1, _users: -1 });
});

test("sem distintivos ainda conta como pessoa; ids estranhos não contam", () => {
  assert.deepStrictEqual(countChanges(null, { updatedAt: 1 }), { _users: 1 });
  assert.deepStrictEqual(earnedIds({ earned: { "a/b": 1, ok_1: 2, "": 3 } }), ["ok_1"]);
});
