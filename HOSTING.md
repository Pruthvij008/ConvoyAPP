# Hosting Convoy

Getting the backend onto the internet so friends can join from their own
phones, on their own data, from anywhere.

Everything below is free. No card required at any step.

---

## What has to move

The app currently talks to a server on your laptop, backed by a local
MongoDB and a local Redis. Three things therefore need a new home:

| Piece | Where it goes | Cost |
|---|---|---|
| The Node/Express API | **Render** web service | Free |
| MongoDB | **MongoDB Atlas** M0 cluster | Free forever |
| Redis | **Render Key Value** | Free |

Cloudinary already lives in the cloud and needs no change.

---

## Step 1 — MongoDB Atlas (10 minutes)

Render has no managed MongoDB, so the database goes to Atlas.

1. Sign up at <https://www.mongodb.com/cloud/atlas/register>
2. **Create a free cluster** — choose **M0**, and pick the region closest to
   you (Mumbai `ap-south-1` if offered)
3. **Database Access** → Add New Database User
   - Username and password — use a *generated* password and copy it
   - Role: **Read and write to any database**
4. **Network Access** → Add IP Address → **Allow access from anywhere**
   (`0.0.0.0/0`)

   Render's free tier does not publish fixed outbound IPs, so there is no
   narrower rule to write. The database user's password is what actually
   protects it — which is why it must be a generated one, not a memorable
   one.
5. **Connect** → Drivers → copy the connection string. It looks like:

   ```
   mongodb+srv://USER:PASSWORD@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority
   ```

   **Add the database name** before the `?`, or Mongo will use `test`:

   ```
   mongodb+srv://USER:PASSWORD@cluster0.xxxxx.mongodb.net/convoy?retryWrites=true&w=majority
   ```

---

## Step 2 — A fresh JWT secret

The development secret is in a chat log and a local file. Production gets
its own. Generate one:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Keep the output for step 3. Changing this signs out every existing session,
which is exactly what you want when moving to a new environment.

---

## Step 3 — Render (15 minutes)

1. Sign up at <https://render.com> with your GitHub account
2. **New → Blueprint**, choose the `ConvoyAPP` repo, branch **`hosting`**

   Render reads `backend/render.yaml` and creates both the web service and
   the Redis instance, already wired together.
3. It will prompt for the secrets marked `sync: false`:

   | Variable | Value |
   |---|---|
   | `DATABASE` | the Atlas string from step 1 |
   | `JWT_SECRET` | the value from step 2 |
   | `CLOUDINARY_CLOUD_NAME` | `dsowpxag` |
   | `CLOUDINARY_API_KEY` | from your Cloudinary dashboard |
   | `CLOUDINARY_API_SECRET` | from your Cloudinary dashboard |
   | `GOOGLE_ROUTES_KEY` | the Maps demo key |

4. Apply. First deploy takes about five minutes.
5. You get a URL like `https://convoy-api.onrender.com`. Check it:

   ```bash
   curl https://convoy-api.onrender.com/api/v1/health
   ```

   Expect `{"status":"success","message":"API is up"}`.

---

## Step 4 — Point the app at it

In `android/local.properties` (never committed):

```
convoy.releaseBaseUrl=https://convoy-api.onrender.com/
```

Then build a release APK and send it to your friends. It will work on any
network, anywhere — no shared WiFi, no firewall rules, no laptop running.

---

## What to expect from the free tier

**It sleeps after 15 minutes of no traffic, and waking takes about a
minute.** The first person to open the app after a quiet spell waits;
everyone after that is immediate.

This bites far less than it sounds, because **Render counts WebSocket
messages as traffic**. Once a trip is live and phones are reporting
positions, the service stays awake for the whole journey. The cold start
only happens before anyone has opened the app that hour.

**Redis is in-memory only** on the free plan — no persistence. That is fine
here by design: Redis holds live positions, MongoDB holds everything
durable, so a restart costs at most one ping's worth of state and the next
position update replaces it.

**750 instance hours a month.** A service that sleeps when idle uses far
fewer than a month's worth, so this is not a real limit for testing.

---

## When to stop using the free tier

Move to Render's $7/month plan (or Railway) when the cold start starts
annoying real users rather than just you. Nothing in the setup changes —
it is a plan toggle.

---

## Things that would have broken, and are already handled

**`trust proxy`.** Behind a hosting proxy every request arrives from the
proxy's address. Without `app.set("trust proxy", 1)` the rate limiter would
have seen one IP for the entire user base and locked out a whole convoy on
the first retry. Set to `1` rather than `true`, because trusting every hop
lets a client forge `X-Forwarded-For` and choose its own bucket.

**The join limiter counts only failures.** Friends behind one router share a
public IP, so successful joins must not accumulate against them.

**`PORT`.** Render assigns it; `config.js` already reads `process.env.PORT`.

**HTTPS.** Render terminates TLS, so the app talks plain HTTP internally and
the phone gets a secure connection. `usesCleartextTraffic` stays on only for
LAN development.

---

# Moving off Render: Oracle Cloud Always Free

## Why the free Render plan could never hold this app

Render's free tier allows **750 instance-hours a month**. A month is 720 to
744 hours. So the allowance is one instance running continuously, with
essentially no headroom.

That is fine for a normal web app, which sleeps between visitors. It cannot
work here, and the reason is written into this very document: Socket.IO
holds a connection open for the whole of a live trip, so the service never
idles while anyone is driving. Running out was arithmetic, not bad luck.

The same applies to every other hour-metered free tier — Railway's credit,
Koyeb, Fly's allowance. It is not a Render problem, it is a
persistent-connection problem, and changing to another metered free plan
just moves the date.

Serverless is out for the same reason at a deeper level: Vercel, Netlify,
Cloudflare Workers and Lambda cannot hold a long-lived Socket.IO connection
without rewriting the live layer entirely.

**Oracle Cloud Always Free is the only genuinely free option that survives
this workload**, because it gives you a VM rather than metered hours. It
asks for a card to verify identity at signup and never charges it — Always
Free resources cannot bill you.

Its Mumbai and Hyderabad regions are also closer to home than Render's
Singapore, so latency improves rather than degrades.

## What moves, and what does not

| Piece | Where it goes |
|---|---|
| MongoDB Atlas | **Stays.** Free forever, untouched. |
| Cloudinary | **Stays.** Untouched. |
| Node + Socket.IO API | Oracle VM, behind nginx |
| Redis | Same VM, on loopback |

Redis needs no code change anywhere. `ioredis` reads the scheme from
`REDIS_URL`, so local, remote and TLS all work without touching a line.

## Doing it

1. Create an **Always Free** VM. The ARM shape (VM.Standard.A1.Flex) is the
   generous one — up to 4 cores and 24 GB. Pick Ubuntu, and a region near
   you. If ARM capacity is unavailable, retry or take an AMD Micro shape;
   this app needs very little.

2. SSH in and run the setup script, which installs Node, nginx, Redis, the
   service user and the systemd unit:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/Pruthvij008/ConvoyAPP/hosting/backend/deploy/setup-oracle.sh -o setup-oracle.sh
   chmod +x setup-oracle.sh
   ./setup-oracle.sh
   ```

3. Follow the three steps it prints: write `config.env`, open TCP 80/443 in
   the VCN security list, start the service.

## The two things that go wrong

**The firewall is in two places.** Oracle's Ubuntu images ship a
REJECT-everything iptables ruleset that is entirely separate from the VCN
security list in the web console. Opening the port in the console alone
leaves the VM unreachable, and from outside the two failures are
indistinguishable — nothing answers either way. The setup script handles
the iptables half; the console half is yours.

**WebSockets die at the proxy.** nginx proxies as HTTP/1.0 by default and
strips `Connection` as a hop-by-hop header, so the Socket.IO upgrade never
reaches Node. This fails in the most confusing way possible: REST works,
the map loads, the roster fills in — and no position ever moves.
`deploy/nginx-convoy.conf` carries the upgrade properly and holds idle
sockets for five minutes rather than nginx's default sixty seconds, which
would otherwise cut every connection mid-trip.

## Before you migrate, check one thing

A trip left `ACTIVE` keeps a phone's foreground service connected, which
keeps the server awake around the clock, and trips only auto-abandon after
12 hours of *inactivity*. One forgotten trip can therefore consume the
entire monthly allowance on its own. Worth ruling out before concluding the
plan was too small — and worth ending trips properly regardless.
