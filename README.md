# Convoy

**Keep a group of cars together on a road trip.**

Six friends set off in three cars. Within twenty minutes someone has missed
a turn, someone else has stopped for fuel without telling anyone, and the
lead car is thirty kilometres ahead wondering whether to wait. The group
chat fills up with "where are you?" and photographs of road signs.

Convoy is built for exactly that hour. Everyone sees everyone on one map,
stopping tells the group *why* you stopped, and nobody has to type a message
while driving.

---

## Screenshots

> Drop images into `docs/screenshots/` with these filenames and they will
> appear here.

| Live map | Why have you stopped? | Navigation |
|---|---|---|
| ![Live map](docs/screenshots/map.png) | ![Marker picker](docs/screenshots/stops.png) | ![Navigation](docs/screenshots/navigation.png) |

| Lobby | Chat | Alert |
|---|---|---|
| ![Lobby](docs/screenshots/lobby.png) | ![Chat](docs/screenshots/chat.png) | ![SOS](docs/screenshots/sos.png) |

---

## What it does

### The map is the app

While a trip is running, the map fills the screen and everything else floats
over it. Each vehicle is one dot in a colour the server assigns, so every
phone draws the same car the same way.

**Vehicles, not people.** Four friends in one car are one dot, not four.
They share the car's status, and only one device broadcasts location — which
is also why the battery cost is paid once per car rather than once per
person.

**A frozen dot never looks live.** Position age is drawn, not just labelled:
a solid dot is reporting, a faded ring has gone quiet, a hollow ring is
lost. On a mountain road the difference between "they stopped" and "we lost
signal" decides whether you turn the car around.

### Stopping says why

Tapping **Mark a stop** asks one question — *why have you stopped?* — and
answers it for everyone at once with a marker on your dot.

⛽ Fuel · 🍽️ Food · ☕ Chai · 🚻 Restroom · 🛏️ Rest · 🅿️ Parking ·
🔧 Breakdown · 🛞 Puncture · 🩹 Medical · ⚠️ Accident · 🚧 Traffic ·
👮 Police check · 📸 Photo stop · 🏞️ Viewpoint · 💰 Toll · 🛒 Shopping

The catalogue never covers what a particular group actually stops for, so
you can invent a marker mid-trip — name it, pick an icon, and it appears in
everyone's picker immediately and in your own library for next time.

Every stop carries the decision that actually matters: **wait for me**, or
**go ahead and I'll catch up**. "Stopped for fuel" on its own tells the
convoy nothing about what to do.

Hold a marker instead of tapping it to attach a **photo** and a note. A
picture of a shredded sidewall says in one glance what a paragraph gets
wrong, and it reaches the person deciding whether to turn back.

### Talking without typing

- **Quick messages** — one tap: *Pulling over · Need fuel · Go ahead · Wait
  up · Almost there · On my way · Where are you? · Took a wrong turn*
- **Voice notes** — hold to record, release to send. A clip rather than a
  live channel, deliberately: a recording that fails to upload can be
  retried, whereas a live stream in a tunnel is simply gone.
- **Chat**, for when there is something to actually say.

### Alerts you did not have to ask for

Raised by the server, so they do not depend on anyone's phone being awake:

| Alert | Fires when |
|---|---|
| **Gap** | A car falls too far behind — measured *along the route*, not as the crow flies |
| **Off route** | Someone leaves the planned path |
| **Stalled** | A vehicle stops moving without marking a stop |
| **Signal lost** | Positions stop arriving |
| **Low battery** | A tracking phone is about to go dark |
| **SOS** | Raised by hand, with a ten-second cancellable countdown |

Gap distance is measured along the road on purpose. Two cars two kilometres
apart as the crow flies can be fifteen kilometres apart on a ghat road full
of hairpins, and the straight-line number is worst exactly where it matters
most.

### Getting there

- **The shared route** is fetched once by the server and sent to everyone, so
  N members never mean N routing calls.
- **Directions** to the destination, to a friend, to an SOS or to a waypoint
  — all the same question, asked the same way.
- **In Convoy or in Google Maps?** You are asked every time rather than the
  app deciding. Convoy's own view keeps the group on screen; Google gives
  turn-by-turn voice.
- **Chase Mode** for a car that is still moving — a live bearing, distance
  and closing speed, because turn-by-turn cannot route to a moving target.

### Details that matter on a real drive

- **Day and night themes** switch on the actual sunset at your position, not
  a fixed clock.
- **Battery-adaptive tracking.** GPS is sampled often enough to draw a
  moving dot, but positions are only *sent* on a cadence that stretches from
  seconds while moving to minutes while parked. Sending is what costs
  battery; looking is nearly free.
- **Your own dot is drawn from your own GPS**, never round-tripped through
  the server, so it keeps up with the car.
- **Reconnects properly.** A socket only carries what happens after it
  connects, so the server sends a full snapshot on join and on every
  reconnect — coming out of a tunnel does not leave you with a blank map.

### Privacy

**Location leaves your phone only while a trip is ACTIVE.** Not in the
lobby, not after it ends. It is enforced on the server, and the app mirrors
the rule so it will not even start the tracking service otherwise.

A permanent, non-dismissible notification shows whenever you are sharing,
with a way to stop from inside it. Trips left running auto-end after twelve
hours of inactivity, so a host who forgets cannot keep a group broadcasting
for days.

---

## How it works

```
   Android app                    Server                     Data
  ┌──────────────┐          ┌────────────────┐         ┌──────────────┐
  │ Compose UI   │          │ Express (REST) │         │ MongoDB      │
  │ MapLibre map │◄────────►│                │◄───────►│ Atlas        │
  │              │   REST   │ Socket.IO      │         │ (durable)    │
  │ Foreground   │◄────────►│ (live layer)   │         ├──────────────┤
  │ location svc │  socket  │                │◄───────►│ Redis        │
  └──────────────┘          └────────────────┘         │ (positions)  │
         │                          │                  └──────────────┘
         │  photos / voice          │  routes
         ▼  (direct upload)         ▼
   ┌────────────┐            ┌──────────────┐
   │ Cloudinary │            │ Google Routes│
   └────────────┘            │ / OSRM       │
                             └──────────────┘
```

**Two channels, on purpose.** Positions go over the socket because they are
superseded within seconds and a dropped one costs nothing. Anything durable
— a stop, a message, an alert — is written over REST *first* and only then
broadcast, so nothing is ever announced that does not exist.

**Redis holds live positions; MongoDB holds everything durable.** A Redis
restart costs at most one ping's worth of state, which the next position
update replaces. The app degrades to MongoDB rather than failing if Redis is
unavailable.

**Media never touches the API.** The phone asks the server to sign a
folder-scoped upload, sends the bytes straight to Cloudinary, and the server
then verifies what landed. A `publicId` reported by a client is a claim
until it is checked.

---

## Built with

**Android** — Kotlin · Jetpack Compose · Hilt · Retrofit/OkHttp ·
MapLibre GL · Coil · Play Services Location · Socket.IO client

**Backend** — Node.js · Express · MongoDB/Mongoose · Redis/ioredis ·
Socket.IO · JWT · Cloudinary · Turf.js

**Maps** — MapLibre with OpenFreeMap vector styles. No API key, no account,
no per-request billing.

**Routing** — Google Routes when traffic matters, OSRM as the free default
and fallback. The app cannot tell which one answered.

---

## Running it

### Backend

```bash
cd backend
npm install
cp config.env.example config.env    # then fill it in
npm run dev
```

Needs MongoDB and Redis. Both can be local:

```bash
# MongoDB on 27017, Redis on 6379
npm run redis
```

Minimum `config.env`:

```
DATABASE=mongodb://127.0.0.1:27017/convoy
JWT_SECRET=<generate one, see below>
JWT_EXPIRESIN=90d
REDIS_URL=redis://127.0.0.1:6379
```

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Photos and voice notes need Cloudinary; without those keys the app hides
those features rather than failing when someone taps them.

### Android

`android/local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/sdk
convoy.baseUrl=http://10.0.2.2:3000/          # emulator -> host machine
convoy.releaseBaseUrl=https://your-api.example.com/
```

On a physical device, use your machine's LAN address instead:
`convoy.baseUrl=http://192.168.1.8:3000/`

```bash
cd android
./gradlew assembleDebug
```

### Verifying the backend

```bash
npm run verify          # everything
npm run verify:models   # schema rules and invariants
npm run verify:routing  # route geometry accuracy
```

The routing suite measures how far the simplified route line strays from the
geometry it came from — a shape bug is invisible in a diff, so it needs a
test that measures shape.

---

## Deployment

See **[HOSTING.md](HOSTING.md)** for the full walkthrough.

One thing worth knowing up front: Convoy holds a WebSocket open for the
whole of a live trip, so it never idles while anyone is driving. Hosting
plans that meter *instance hours* will therefore bill for the entire trip
duration, and free tiers built around apps that sleep between visitors run
out quickly.

---

## Repository layout

```
android/
  app/src/main/java/com/convoy/mobile/
    activities/       screens
    customControls/   shared Compose components, incl. the map
    dataModel/        API models
    network/          Retrofit, Socket.IO, error handling
    repository/       data access
    service/          foreground location tracking
    viewModels/       screen state
backend/
  controllers/        request handlers
  services/           routing, location, alerts, media, places
  models/             Mongoose schemas
  socket/             the live layer
  jobs/               alert and trip sweepers
  scripts/            verification suites
  deploy/             nginx, systemd, VM setup
```

---

## Licence

Not currently licensed for reuse. All rights reserved.
