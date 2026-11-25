/**
 * Contagem dos distintivos: cada pessoa guarda os seus em user_badges/<uid> ({ earned: { <id>: <dia> }, ... }) e
 * badge_counts tem, para cada distintivo, quantas pessoas o têm, e em _users quantas pessoas têm distintivos
 * guardados. Aqui só se calcula o que muda com uma escrita (sem Firebase, para se poder testar).
 */

const ID = /^[A-Za-z0-9_]{1,40}$/;

/** Os distintivos ganhos num user_badges/<uid> (null se não existe). */
function earnedIds(value) {
  if (!value || typeof value !== "object" || !value.earned || typeof value.earned !== "object") return [];
  return Object.keys(value.earned).filter((id) => ID.test(id));
}

/**
 * O que soma ou tira em badge_counts quando user_badges/<uid> passa de [before] para [after]: +1 em cada distintivo
 * novo, -1 em cada um que saiu, e ±1 em _users quando a pessoa aparece ou desaparece.
 */
function countChanges(before, after) {
  const was = new Set(earnedIds(before));
  const is = new Set(earnedIds(after));
  const deltas = {};
  for (const id of is) if (!was.has(id)) deltas[id] = 1;
  for (const id of was) if (!is.has(id)) deltas[id] = -1;
  const users = (after ? 1 : 0) - (before ? 1 : 0);
  if (users !== 0) deltas._users = users;
  return deltas;
}

module.exports = { earnedIds, countChanges };
