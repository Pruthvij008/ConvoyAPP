const nodemailer = require("nodemailer");
const pug = require("pug");
const { convert } = require("html-to-text");
const config = require("../config/config");

// Transactional email via Gmail + Nodemailer, rendering Pug templates from
// ../email. Mirrors the cropify-v3 Email class but trimmed to the auth flows.
//
// Usage:
//   await new Email(user).sendWelcome();
//   await new Email(user).sendOtp(code, "verify");   // email verification
//   await new Email(user).sendOtp(code, "reset");    // password reset
module.exports = class Email {
  constructor(user) {
    this.to = user.email;
    this.firstName = (user.name || "there").split(" ")[0];
    this.from = `${config.mail.fromName} <${config.mail.gmailAddress}>`;
  }

  newTransport() {
    if (!config.mail.gmailAddress || !config.mail.gmailAppPassword) {
      throw new Error(
        "Gmail credentials missing — set GMAIL_ADDRESS and GMAIL_APP_PASSWORD in config.env"
      );
    }

    return nodemailer.createTransport({
      service: "gmail",
      host: "smtp.gmail.com",
      port: 587,
      secure: false,
      auth: {
        user: config.mail.gmailAddress,
        pass: config.mail.gmailAppPassword,
      },
      tls: { rejectUnauthorized: false },
    });
  }

  // Render a Pug template and send it.
  async send(template, subject, data = {}) {
    const html = pug.renderFile(`${__dirname}/../email/${template}.pug`, {
      firstName: this.firstName,
      subject,
      ...data,
    });

    const mailOptions = {
      from: this.from,
      to: this.to,
      subject,
      html,
      text: convert(html),
    };

    return this.newTransport().sendMail(mailOptions);
  }

  async sendWelcome() {
    await this.send("welcome", `Welcome to ${config.mail.fromName}! 🎉`);
  }

  // kind: "verify" | "reset" — chooses copy + subject.
  async sendOtp(code, kind = "verify") {
    const isReset = kind === "reset";
    const subject = isReset
      ? "Your password reset code"
      : "Verify your email address";
    await this.send("otp", subject, {
      code,
      isReset,
      minutes: config.otp.expiresInMinutes,
    });
  }
};
