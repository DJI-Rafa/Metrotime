/**
 * Migração única para as novas regras do Realtime Database.
 *
 * `users/{uid}` deixou de ser público (tinha email e data de nascimento). Este script copia o que
 * pode ser visto por outros utilizadores para os novos nós:
 *   profiles/{uid}        -> { name, photoUrl? }
 *   profile_photos/{uid}  -> foto em base64 (se existir)
 *   email_index/{email}   -> uid   (pesquisa de amigos por email exato, sem listar emails)
 *
 * Correr a partir da raiz do projeto, com scripts/serviceAccountKey.json presente:
 *   node scripts/migrate_profiles.js            (mostra o que ia fazer)
 *   node scripts/migrate_profiles.js --apply    (escreve)
 */
const path = require("path");
const admin = require(path.join(__dirname, "..", "functions", "node_modules", "firebase-admin"));

admin.initializeApp({
  credential: admin.credential.cert(require(path.join(__dirname, "serviceAccountKey.json"))),
  databaseURL: "https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app",
});

const apply = process.argv.includes("--apply");
const INVALID_KEY_CHARS = /[.#$\[\]\/]/;

function emailKey(email) {
  const key = email.trim().toLowerCase().split(".").join(",");
  return INVALID_KEY_CHARS.test(key) ? null : key;
}

(async () => {
  const db = admin.database();
  const users = (await db.ref("users").get()).val() || {};
  const updates = {};

  for (const [uid, u] of Object.entries(users)) {
    const name = typeof u.name === "string" && u.name.trim() ? u.name.trim().slice(0, 80) : "Utilizador";
    const profile = { name };
    if (typeof u.photoUrl === "string" && u.photoUrl.length <= 1000) profile.photoUrl = u.photoUrl;
    updates[`profiles/${uid}`] = profile;

    if (typeof u.photoBase64 === "string" && u.photoBase64.length <= 200000) {
      updates[`profile_photos/${uid}`] = u.photoBase64;
    }
    if (typeof u.email === "string" && u.email.includes("@")) {
      const key = emailKey(u.email);
      if (key) updates[`email_index/${key}`] = uid;
    }
  }

  console.log(`${Object.keys(users).length} utilizadores, ${Object.keys(updates).length} escritas.`);
  if (!apply) {
    console.log("Nada escrito. Corre com --apply para gravar.");
  } else {
    await db.ref().update(updates);
    console.log("Migração concluída.");
  }
  process.exit(0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
