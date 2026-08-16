// Operational error with an attached HTTP status code. Anything thrown as
// an AppError is treated as an expected, client-facing error by the global
// error handler (as opposed to an unexpected programming bug).
class AppError extends Error {
  constructor(message, statusCode) {
    super(message);

    this.statusCode = statusCode;
    this.status = `${statusCode}`.startsWith("4") ? "fail" : "error";
    this.isOperational = true;

    Error.captureStackTrace(this, this.constructor);
  }
}

module.exports = AppError;
