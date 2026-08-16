// Wrap an async route handler so any rejected promise is forwarded to
// Express's error middleware instead of crashing / hanging the request.
module.exports = (fn) => {
  return (req, res, next) => {
    fn(req, res, next).catch(next);
  };
};
