const { promisify } = require("util");
const jwt = require("jsonwebtoken");
const User = require("../models/userModel");
const catchAsync = require("../utils/catchAsync");
const AppError = require("../utils/appError");
const Email = require("../utils/email");
const { generateOtp, hashOtp, otpExpiry } = require("../utils/otp");
const googleService = require("../services/google.service");
const config = require("../config/config");

// ── Token helpers ────────────────────────────────────────────────
const signToken = (id) =>
  jwt.sign({ id }, config.jwt.secret, { expiresIn: config.jwt.expiresIn });

const createSendToken = (user, statusCode, res) => {
  const token = signToken(user._id);

  res.cookie("jwt", token, {
    expires: new Date(
      Date.now() + config.jwt.cookieExpiresInDays * 24 * 60 * 60 * 1000
    ),
    httpOnly: true,
    secure: config.env === "production",
    sameSite: config.env === "production" ? "none" : "lax",
  });

  // Strip sensitive fields from the response body.
  user.password = undefined;
  user.emailOtp = undefined;
  user.passwordResetOtp = undefined;

  res.status(statusCode).json({
    status: "success",
    token,
    data: { user },
  });
};

// Issue a fresh OTP, persist its hash, and email it. `kind` picks the copy.
const issueOtp = async (user, kind) => {
  const otp = generateOtp();
  if (kind === "verify") {
    user.emailOtp = hashOtp(otp);
    user.emailOtpExpires = otpExpiry();
  } else {
    user.passwordResetOtp = hashOtp(otp);
    user.passwordResetExpires = otpExpiry();
  }
  await user.save({ validateBeforeSave: false });
  await new Email(user).sendOtp(otp, kind);
};

// ── Signup ───────────────────────────────────────────────────────
// Creates an unverified local account and emails a verification OTP.
exports.signup = catchAsync(async (req, res, next) => {
  const existing = await User.findOne({ email: req.body.email });
  if (existing) {
    return next(new AppError("An account with that email already exists.", 409));
  }

  const user = await User.create({
    name: req.body.name,
    email: req.body.email,
    phone: req.body.phone,
    password: req.body.password,
    passwordConfirm: req.body.passwordConfirm,
    authProvider: "local",
    isVerified: false,
  });

  await issueOtp(user, "verify");

  res.status(201).json({
    status: "success",
    message: "Account created. Check your email for a verification code.",
    data: { email: user.email },
  });
});

// ── Verify email (OTP) ───────────────────────────────────────────
exports.verifyEmail = catchAsync(async (req, res, next) => {
  const { email, otp } = req.body;
  if (!email || !otp) {
    return next(new AppError("Please provide your email and the code.", 400));
  }

  const user = await User.findOne({ email }).select(
    "+emailOtp +emailOtpExpires"
  );
  if (!user) return next(new AppError("No account with that email.", 404));
  if (user.isVerified) {
    return next(new AppError("This email is already verified.", 400));
  }
  if (
    !user.emailOtp ||
    user.emailOtp !== hashOtp(otp) ||
    user.emailOtpExpires < Date.now()
  ) {
    return next(new AppError("Code is invalid or has expired.", 400));
  }

  user.isVerified = true;
  user.emailOtp = undefined;
  user.emailOtpExpires = undefined;
  await user.save({ validateBeforeSave: false });

  // Welcome email is best-effort; don't fail verification if it bounces.
  try {
    await new Email(user).sendWelcome();
  } catch (_) {}

  createSendToken(user, 200, res);
});

// ── Resend verification OTP ──────────────────────────────────────
exports.resendOtp = catchAsync(async (req, res, next) => {
  const { email } = req.body;
  const user = await User.findOne({ email });
  if (!user) return next(new AppError("No account with that email.", 404));
  if (user.isVerified) {
    return next(new AppError("This email is already verified.", 400));
  }
  await issueOtp(user, "verify");
  res.status(200).json({ status: "success", message: "A new code is on its way." });
});

// ── Login (email/phone + password — the "cropify" path) ──────────
exports.login = catchAsync(async (req, res, next) => {
  const { emailOrPhone, password } = req.body;
  if (!emailOrPhone || !password) {
    return next(new AppError("Please provide email/phone and password.", 400));
  }

  const query = emailOrPhone.includes("@")
    ? { email: emailOrPhone.toLowerCase() }
    : { phone: emailOrPhone };
  const user = await User.findOne(query).select("+password");

  if (!user || !user.password) {
    return next(new AppError("Incorrect credentials.", 401));
  }
  if (!(await user.correctPassword(password, user.password))) {
    return next(new AppError("Incorrect credentials.", 401));
  }
  if (!user.isVerified) {
    return next(
      new AppError("Please verify your email before logging in.", 403)
    );
  }

  createSendToken(user, 200, res);
});

// ── Google Sign-In (gated by GOOGLE_AUTH_ENABLED) ────────────────
// Frontend sends the ID token from the Google button; we verify it and
// upsert the user. Verified-by-Google emails skip the OTP step.
exports.googleAuth = catchAsync(async (req, res, next) => {
  if (!config.google.enabled) {
    return next(new AppError("Google sign-in is disabled.", 400));
  }

  const { credential } = req.body; // Google ID token (JWT)
  if (!credential) {
    return next(new AppError("Missing Google credential.", 400));
  }

  const profile = await googleService.verifyIdToken(credential);
  if (!profile.emailVerified) {
    return next(new AppError("Your Google email is not verified.", 401));
  }

  let user = await User.findOne({ email: profile.email });
  if (!user) {
    // New Google account.
    user = await User.create({
      name: profile.name,
      email: profile.email,
      authProvider: "google",
      googleId: profile.googleId,
      isVerified: true,
      photo: profile.picture || "default.jpg",
    });
  } else if (user.authProvider !== "google") {
    // Existing local account with the same email — link it to Google.
    user.authProvider = "google";
    user.googleId = profile.googleId;
    user.isVerified = true;
    await user.save({ validateBeforeSave: false });
  }

  createSendToken(user, 200, res);
});

// Upsert a push token, keeping the array bounded (plan §12.1: no unbounded
// embedded arrays). Same token → refresh lastSeenAt; new token → append and
// trim to the 3 most recently seen.
const registerPushToken = async (user, token, platform) => {
  const existing = user.pushTokens.find((t) => t.token === token);
  if (existing) {
    existing.lastSeenAt = new Date();
  } else {
    user.pushTokens.push({ token, platform, lastSeenAt: new Date() });
    user.pushTokens.sort((a, b) => b.lastSeenAt - a.lastSeenAt);
    user.pushTokens = user.pushTokens.slice(0, 3);
  }
  await user.save({ validateBeforeSave: false });
};

// ── Username + password ──────────────────────────────────────────
// The primary sign-in. No email, so nothing to verify and no OTP round
// trip before someone can join a convoy.
exports.register = catchAsync(async (req, res, next) => {
  const { username, name, password, passwordConfirm, deviceId } = req.body;

  if (!username?.trim()) return next(new AppError("Pick a username.", 400));
  if (!password) return next(new AppError("Pick a password.", 400));
  if (password.length < 8) {
    return next(new AppError("Password must be at least 8 characters.", 400));
  }
  if (passwordConfirm !== undefined && password !== passwordConfirm) {
    return next(new AppError("Those passwords don't match.", 400));
  }

  const handle = username.trim().toLowerCase();

  // Checked here as well as in the schema so the user gets a sentence
  // written for them rather than a raw Mongoose validation dump.
  if (handle.length < 3 || handle.length > 20) {
    return next(new AppError("Username must be between 3 and 20 characters.", 400));
  }
  if (!/^[a-z0-9_]+$/.test(handle)) {
    return next(
      new AppError("Username can only use letters, numbers and underscores.", 400)
    );
  }

  // Checked up front so the error is about the username rather than a raw
  // duplicate-key failure. The unique index is still the real guarantee.
  const taken = await User.findOne({ username: handle });
  if (taken) return next(new AppError("That username is already taken.", 409));

  let user;
  try {
    user = await User.create({
      username: handle,
      // Display name is what the convoy sees; it falls back to the username.
      name: name?.trim() || handle,
      password,
      // The schema requires a confirmation for local accounts. A client that
      // only sends one password field has already been checked above, so
      // mirroring it here keeps that validator satisfied without demanding
      // the caller send the same string twice.
      passwordConfirm: passwordConfirm ?? password,
      authProvider: "local",
      deviceId: deviceId || undefined,
      isVerified: true, // nothing to verify without an email
    });
  } catch (err) {
    if (err.code === 11000) {
      return next(new AppError("That username is already taken.", 409));
    }
    throw err;
  }

  createSendToken(user, 201, res);
});

// Accepts a username, and still accepts an email so any account created
// through the older email flow keeps working.
exports.loginWithUsername = catchAsync(async (req, res, next) => {
  const { username, email, password } = req.body;
  const identifier = (username || email || "").trim().toLowerCase();

  if (!identifier || !password) {
    return next(new AppError("Enter your username and password.", 400));
  }

  const user = await User.findOne({
    $or: [{ username: identifier }, { email: identifier }],
  }).select("+password");

  // Deliberately the same message for "no such user" and "wrong password",
  // so this cannot be used to discover which usernames exist.
  if (!user || !user.password || !(await user.correctPassword(password, user.password))) {
    return next(new AppError("Wrong username or password.", 401));
  }

  createSendToken(user, 200, res);
});

// ── Device (anonymous) auth ──────────────────────────────────────
// Convoy's primary and default sign-in: no email, no password, no OTP.
// The client generates a random deviceId at first launch, stores it, and
// posts it here with a display name. Returns the SAME JWT the email and
// Google flows issue, so `protect` and every downstream route are unchanged.
//
// Idempotent by design: the client calls this on every cold start and gets
// the same user back. A reinstall generates a new deviceId and therefore a
// new user — rejoining a trip then goes through the original join link
// (plan §4.8).
exports.deviceAuth = catchAsync(async (req, res, next) => {
  const { deviceId, name, platform, pushToken } = req.body;

  // 21+ chars because this is the entire credential — it must not be
  // guessable. The client uses a crypto-random value, not an Android ID.
  if (!deviceId || typeof deviceId !== "string" || deviceId.length < 21) {
    return next(new AppError("A valid deviceId is required.", 400));
  }

  // deviceId is `select: false`, so ask for it back explicitly.
  let user = await User.findOne({ deviceId }).select("+deviceId");

  if (!user) {
    if (!name || !name.trim()) {
      return next(new AppError("Please tell us your name.", 400));
    }
    user = await User.create({
      name: name.trim(),
      deviceId,
      authProvider: "device",
    });
  } else if (name && name.trim() && name.trim() !== user.name) {
    // Renaming is allowed and cheap. It does NOT rewrite history: every
    // Participant and Message holds a snapshot of the name at the time
    // (plan §12.3), so past trips keep reading correctly.
    user.name = name.trim();
    await user.save({ validateBeforeSave: false });
  }

  if (pushToken) await registerPushToken(user, pushToken, platform);

  user.deviceId = undefined; // never echo the credential back
  createSendToken(user, 200, res);
});

// ── Forgot password (send OTP) ───────────────────────────────────
exports.forgotPassword = catchAsync(async (req, res, next) => {
  const { email } = req.body;
  const user = await User.findOne({ email });

  // Always respond the same way so we don't leak which emails exist.
  if (user && user.authProvider === "local") {
    await issueOtp(user, "reset");
  }
  res.status(200).json({
    status: "success",
    message: "If that email exists, a reset code has been sent.",
  });
});

// ── Reset password (verify OTP + set new password) ───────────────
exports.resetPassword = catchAsync(async (req, res, next) => {
  const { email, otp, password, passwordConfirm } = req.body;
  if (!email || !otp || !password) {
    return next(new AppError("Missing required fields.", 400));
  }

  const user = await User.findOne({ email }).select(
    "+passwordResetOtp +passwordResetExpires"
  );
  if (
    !user ||
    !user.passwordResetOtp ||
    user.passwordResetOtp !== hashOtp(otp) ||
    user.passwordResetExpires < Date.now()
  ) {
    return next(new AppError("Code is invalid or has expired.", 400));
  }

  user.password = password;
  user.passwordConfirm = passwordConfirm;
  user.passwordResetOtp = undefined;
  user.passwordResetExpires = undefined;
  await user.save(); // runs validators + re-hashes password

  createSendToken(user, 200, res);
});

// ── Update password (while logged in) ────────────────────────────
exports.updatePassword = catchAsync(async (req, res, next) => {
  const user = await User.findById(req.user.id).select("+password");
  if (!user.password) {
    return next(
      new AppError("This account signs in with Google; no password to change.", 400)
    );
  }
  if (!(await user.correctPassword(req.body.passwordCurrent, user.password))) {
    return next(new AppError("Your current password is wrong.", 401));
  }
  user.password = req.body.password;
  user.passwordConfirm = req.body.passwordConfirm;
  await user.save();

  createSendToken(user, 200, res);
});

// ── Logout ───────────────────────────────────────────────────────
exports.logout = (req, res) => {
  res.cookie("jwt", "loggedout", {
    expires: new Date(Date.now() + 2 * 1000),
    httpOnly: true,
  });
  res.status(200).json({ status: "success" });
};

// ── protect: require a valid token ───────────────────────────────
exports.protect = catchAsync(async (req, res, next) => {
  let token;
  if (req.headers.authorization?.startsWith("Bearer")) {
    token = req.headers.authorization.split(" ")[1];
  } else if (req.cookies.jwt) {
    token = req.cookies.jwt;
  }
  if (!token || token === "loggedout") {
    return next(new AppError("You are not logged in. Please log in.", 401));
  }

  const decoded = await promisify(jwt.verify)(token, config.jwt.secret);
  const currentUser = await User.findById(decoded.id);
  if (!currentUser) {
    return next(new AppError("This user no longer exists.", 401));
  }
  if (currentUser.changedPasswordAfter(decoded.iat)) {
    return next(new AppError("Password recently changed. Log in again.", 401));
  }

  req.user = currentUser;
  next();
});

// ── restrictTo: role gate ────────────────────────────────────────
exports.restrictTo = (...roles) => (req, res, next) => {
  if (!roles.includes(req.user.role)) {
    return next(new AppError("You do not have permission for this action.", 403));
  }
  next();
};

// ── Expose runtime auth config to the frontend ───────────────────
// Lets the UI show/hide the Google button without hardcoding the flag.
exports.authConfig = (req, res) => {
  res.status(200).json({
    status: "success",
    data: {
      googleAuthEnabled: config.google.enabled,
      googleClientId: config.google.enabled ? config.google.clientId : null,
    },
  });
};
