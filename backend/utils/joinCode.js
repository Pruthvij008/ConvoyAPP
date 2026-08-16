const crypto = require("crypto");
const { customAlphabet } = require("nanoid");
const config = require("../config/config");

// Ambiguous characters (0/O, 1/I/L) are excluded from the alphabet in
// config so a code read aloud over a phone call cannot be mistyped.
const generate = customAlphabet(config.trip.codeAlphabet, config.trip.codeLength);

// A short join code. Collisions are possible, so the caller retries against
// the unique index rather than trusting a single draw.
const generateJoinCode = () => generate();

// The join LINK token. Long and crypto-random because the link is the whole
// credential — anyone holding it can request to join.
const generateJoinToken = () => crypto.randomBytes(32).toString("hex");

// Only the hash is stored, exactly like the OTP flow in utils/otp.js. A
// database dump therefore never yields working join links.
const hashJoinToken = (token) =>
  crypto.createHash("sha256").update(token).digest("hex");

module.exports = { generateJoinCode, generateJoinToken, hashJoinToken };
