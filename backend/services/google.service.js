const { OAuth2Client } = require("google-auth-library");
const config = require("../config/config");

// Verifies a Google Identity Services ID token (the JWT the Google Sign-In
// button hands the frontend). Returns the trusted profile, or throws.
//
// Only used when config.google.enabled === true.

const client = new OAuth2Client(config.google.clientId);

exports.verifyIdToken = async (idToken) => {
  const ticket = await client.verifyIdToken({
    idToken,
    audience: config.google.clientId, // must match our client id
  });

  const payload = ticket.getPayload();
  // payload.sub is Google's stable unique user id.
  return {
    googleId: payload.sub,
    email: payload.email,
    emailVerified: payload.email_verified,
    name: payload.name || payload.email.split("@")[0],
    picture: payload.picture,
  };
};
