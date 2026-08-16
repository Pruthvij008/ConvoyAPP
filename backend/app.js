const path = require("path");
const express = require("express");
const morgan = require("morgan");
const cookieParser = require("cookie-parser");
const cors = require("cors");
const rateLimit = require("express-rate-limit");
const helmet = require("helmet");

const config = require("./config/config");
const AppError = require("./utils/appError");
const globalErrorHandler = require("./controllers/errorController");

const authRouter = require("./routes/authRoute");
const userRouter = require("./routes/userRoute");
const tripRouter = require("./routes/tripRoute");
const markerRouter = require("./routes/markerRoute");
const placesRouter = require("./routes/placesRoute");
const publicRouter = require("./routes/publicRoute");

const app = express();

// ── Global middleware ────────────────────────────────────────────
app.use(helmet());

if (config.env === "development") app.use(morgan("dev"));

// CORS: reflect the request origin so cookies work with credentials.
app.use(
  cors({
    origin: true,
    credentials: true,
    optionsSuccessStatus: 200,
  })
);

// Throttle auth endpoints to blunt brute-force / OTP guessing.
app.use(
  "/api/v1/auth",
  rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 100,
    standardHeaders: true,
    legacyHeaders: false,
    message: { status: "fail", message: "Too many requests. Try again later." },
  })
);

// Joining is the one trip endpoint an unauthenticated-ish stranger can
// hammer: a 6-character code, or a guessed link token. The alphabet gives
// ~729M combinations so brute force is impractical, but this also blunts
// someone spraying leaked links.
app.use(
  ["/api/v1/trips/join", "/api/v1/trips/preview"],
  rateLimit({
    windowMs: 10 * 60 * 1000,
    max: 30,
    standardHeaders: true,
    legacyHeaders: false,
    message: {
      status: "fail",
      message: "Too many join attempts. Try again in a few minutes.",
    },
  })
);

app.use(express.json({ limit: "10mb" }));
app.use(cookieParser());
app.use(express.static(path.join(__dirname, "public")));

// ── Routes ───────────────────────────────────────────────────────
app.get("/api/v1/health", (req, res) =>
  res.status(200).json({ status: "success", message: "API is up" })
);

app.use("/api/v1/auth", authRouter);
app.use("/api/v1/users", userRouter);
app.use("/api/v1/trips", tripRouter);
app.use("/api/v1/markers", markerRouter);
app.use("/api/v1/places", placesRouter);
// No auth: the share token is the credential (see publicRoute.js).
app.use("/api/v1/live", publicRouter);

// ── 404 + global error handler ───────────────────────────────────
app.all("*", (req, res, next) =>
  next(new AppError(`Can't find ${req.originalUrl} on this server.`, 404))
);
app.use(globalErrorHandler);

module.exports = app;
