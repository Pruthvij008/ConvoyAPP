const AppError = require("../utils/appError");

// Convert known Mongo/Mongoose/JWT errors into friendly AppErrors.
const handleCastError = (err) =>
  new AppError(`Invalid ${err.path}: ${err.value}.`, 400);

const handleDuplicateFields = (err) => {
  const field = Object.keys(err.keyValue || {})[0] || "field";
  return new AppError(`That ${field} is already in use.`, 409);
};

const handleValidationError = (err) => {
  const messages = Object.values(err.errors).map((e) => e.message);
  return new AppError(`Invalid input. ${messages.join(" ")}`, 400);
};

const handleJwtError = () =>
  new AppError("Invalid token. Please log in again.", 401);
const handleJwtExpired = () =>
  new AppError("Your session expired. Please log in again.", 401);

const sendDev = (err, res) =>
  res.status(err.statusCode || 500).json({
    status: err.status || "error",
    error: err,
    message: err.message,
    stack: err.stack,
  });

const sendProd = (err, res) => {
  // Operational, trusted error → tell the client what happened.
  if (err.isOperational) {
    return res
      .status(err.statusCode)
      .json({ status: err.status, message: err.message });
  }
  // Unknown / programming error → don't leak details.
  console.error("UNEXPECTED ERROR:", err);
  return res
    .status(500)
    .json({ status: "error", message: "Something went wrong." });
};

// Global Express error-handling middleware (4 args).
module.exports = (err, req, res, next) => {
  err.statusCode = err.statusCode || 500;
  err.status = err.status || "error";

  if (process.env.NODE_ENV === "production") {
    let error = Object.assign(Object.create(Object.getPrototypeOf(err)), err);
    error.message = err.message;

    if (err.name === "CastError") error = handleCastError(err);
    if (err.code === 11000) error = handleDuplicateFields(err);
    if (err.name === "ValidationError") error = handleValidationError(err);
    if (err.name === "JsonWebTokenError") error = handleJwtError();
    if (err.name === "TokenExpiredError") error = handleJwtExpired();

    return sendProd(error, res);
  }

  return sendDev(err, res);
};
