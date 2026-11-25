/**
 * De onde vem cada pacote de horários e como se faz: usado pela Cloud Function diária (index.js) e pelo comando local
 * que gera os ficheiros que vão dentro da app (build_packs.js), para ser sempre o mesmo conversor.
 */
const transit = require("./transit");
const osm = require("./osm");

/** O GTFS oficial do Metro do Porto (página "Mapas e horários" do site deles). */
const MDP_GTFS_URL = "https://www.metrodoporto.pt/metrodoporto/uploads/document/file/794/google_transit.zip";

/**
 * [[código, async () => { bytes, stats }]] para todos os operadores. [io]: { fetchJson(url), fetchBuffer(url),
 * fetchOverpass(query) }. Se os planos da TML falharem, os operadores da TML ficam de fora desta vez.
 */
async function packJobs(io, now = new Date()) {
  const sources = { ...transit.FIXED_SOURCES };
  try {
    Object.assign(sources, transit.activeTmlPlans(await io.fetchJson(transit.TML_PLANS_URL), now));
  } catch (err) {
    console.warn("Planos da TML falharam:", err.message);
  }
  const jobs = Object.entries(sources).map(([code, url]) => [
    code,
    async () => transit.buildPack(code, await io.fetchBuffer(url), now),
  ]);
  // Metro do Porto: horários do GTFS oficial com o traçado real do OpenStreetMap; sem o GTFS, só o OSM (sem horários)
  jobs.push(["MDP", async () => {
    const osmJson = await io.fetchOverpass(osm.MDP_QUERY);
    try {
      const gtfs = transit.gtfsReader(await io.fetchBuffer(MDP_GTFS_URL));
      return transit.buildPackFromRows("MDP", osm.withOsmShapes(gtfs, osmJson), now);
    } catch (err) {
      console.warn("GTFS do Metro do Porto falhou, fica só o OpenStreetMap:", err.message);
      return transit.buildPackFromRows("MDP", osm.metroPortoRows(osmJson), now);
    }
  }]);
  return jobs;
}

module.exports = { packJobs };
