/**
 * One-time script to seed Firebase RTDB with vehicle data from comboios.live.
 * Run this once to populate the database before the Cloud Function is deployed.
 * 
 * Usage: node seed_vehicles.js
 * 
 * Requires: GOOGLE_APPLICATION_CREDENTIALS env var pointing to a Firebase service account key,
 * OR run after `firebase login` with the firebase-admin SDK.
 */
const admin = require("firebase-admin");
const fetch = require("node-fetch");

// Initialize with default credentials (works after `firebase login`)
admin.initializeApp({
  databaseURL: "https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app"
});
const db = admin.database();

async function seed() {
  console.log("Fetching vehicles from comboios.live...");
  
  const response = await fetch("https://comboios.live/api/vehicles", {
    headers: { "User-Agent": "Metrotime/1.0" },
  });

  if (!response.ok) {
    console.error(`Failed: ${response.status}`);
    process.exit(1);
  }

  const data = await response.json();
  const vehicles = data.vehicles || [];
  
  const activeVehicles = vehicles.filter(
    (v) => v.status !== "COMPLETED" && v.status !== "CANCELLED"
  );

  console.log(`Got ${vehicles.length} total, ${activeVehicles.length} active`);

  const vehiclesMap = {};
  activeVehicles.forEach((v) => {
    vehiclesMap[v.trainNumber] = {
      trainNumber: v.trainNumber,
      runDate: v.runDate || null,
      delay: v.delay || 0,
      lastStation: v.lastStation || null,
      latitude: v.latitude || null,
      longitude: v.longitude || null,
      status: v.status || null,
      hasDisruptions: v.hasDisruptions || false,
      service: v.service || null,
      origin: v.origin || null,
      destination: v.destination || null,
    };
  });

  await db.ref("cp_vehicles").set({
    vehicles: vehiclesMap,
    updatedAt: admin.database.ServerValue.TIMESTAMP,
    count: activeVehicles.length,
  });

  console.log(`✅ Seeded ${activeVehicles.length} vehicles to Firebase RTDB`);
  process.exit(0);
}

seed().catch((err) => {
  console.error("Error:", err);
  process.exit(1);
});
