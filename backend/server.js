const mongoose = require("mongoose");
const dotenv = require("dotenv");

// Load env BEFORE anything reads config.
dotenv.config({ path: "./config.env" });

process.on("uncaughtException", (err) => {
  console.log("UNCAUGHT EXCEPTION 💥", err.name, err.message);
  process.exit(1);
});

const config = require("./config/config");
const app = require("./app");

// DATABASE may contain <db_password>, replaced with DATABASE_PASSWORD.
//
// Trimmed and unquoted first. Atlas hands you the string inside a .env
// line — MONGODB_URI="mongodb+srv://..." — and pasting that into a hosting
// dashboard carries the quotes into the value, where a .env parser would
// have stripped them. The driver then rejects it for an "invalid scheme",
// which points at the URL rather than at the two characters wrapping it.
const db = String(config.db.uri || "")
  .trim()
  .replace(/^["']|["']$/g, "")
  .replace("<db_password>", config.db.password);

if (!db.startsWith("mongodb://") && !db.startsWith("mongodb+srv://")) {
  // Named explicitly, because every wrong value — empty, quoted, a whole
  // env line pasted in — produces the same unhelpful driver error.
  console.error("❌ DATABASE is not a MongoDB connection string.");
  console.error(
    `   Got: ${db ? `"${db.slice(0, 40)}..."` : "(empty — is DATABASE set?)"}`
  );
  console.error(
    '   Expected it to start with "mongodb+srv://", with no surrounding quotes.'
  );
  process.exit(1);
}

mongoose
  .connect(db)
  .then((con) => console.log(`✅ MongoDB connected: ${con.connection.name}`))
  .catch((err) => {
    console.error("❌ MongoDB connection error:", err.message);
    process.exit(1);
  });

// Redis holds live vehicle positions. The app degrades rather than fails if
// it is unavailable — the map falls back to Vehicle.lastKnown in Mongo.
const redis = require("./services/redis.service");
redis.connect();

const server = app.listen(config.port, () =>
  console.log(`🚀 Server running on port ${config.port} (${config.env})`)
);

// Socket.IO shares the same HTTP server and port: REST on /api/v1/*,
// sockets on /socket.io.
const socketLayer = require("./socket");
const io = socketLayer.attach(server);
// Controllers reach the socket layer through the app, so a durable REST
// write can broadcast without importing the server.
app.set("io", io);
console.log("🔌 Socket.IO attached");

// SIGNAL_LOST and STALLED are defined by the ABSENCE of a position, so no
// amount of event-driven evaluation can detect them — only a clock can.
const alertSweeper = require("./jobs/alertSweeper");
alertSweeper.start(io);

// The privacy backstop: a host who forgets to end a trip must not keep
// broadcasting their location for days.
const tripSweeper = require("./jobs/tripSweeper");
tripSweeper.start(io);

const shutdown = async (signal) => {
  console.log(`\n${signal} received — shutting down.`);
  alertSweeper.stop();
  tripSweeper.stop();
  io.close();
  await redis.disconnect();
  await mongoose.connection.close();
  server.close(() => process.exit(0));
};
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

process.on("unhandledRejection", (err) => {
  console.log("UNHANDLED REJECTION 💥", err.name, err.message);
  server.close(() => process.exit(1));
});
