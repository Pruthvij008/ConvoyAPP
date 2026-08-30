#!/usr/bin/env bash
#
# One-shot setup for the Convoy API on an Oracle Cloud Always Free VM.
#
# Run it on the VM as the default user (`ubuntu` on the Ubuntu images):
#   curl -fsSL <raw-url>/setup-oracle.sh -o setup-oracle.sh
#   chmod +x setup-oracle.sh
#   ./setup-oracle.sh
#
# Safe to run twice. Every step checks before it acts, because the one thing
# worse than a half-finished server is a script that cannot be re-run to
# finish it.
#
# What it does NOT do: write config.env. That holds every secret in the
# system and is created by hand, once, in step 6 of the printed summary.

set -euo pipefail

APP_USER=convoy
APP_DIR=/opt/convoy
REPO=https://github.com/Pruthvij008/ConvoyAPP.git
BRANCH=hosting
NODE_MAJOR=20

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m !  %s\033[0m\n' "$*"; }

# ── 1. Packages ──────────────────────────────────────────────────
log "Updating packages"
sudo apt-get update -qq
sudo apt-get install -y -qq git nginx redis-server ca-certificates curl gnupg

# ── 2. Node ──────────────────────────────────────────────────────
# Ubuntu's own Node is far too old for this app. NodeSource pins a current
# LTS instead.
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | cut -c2- | cut -d. -f1)" -lt "$NODE_MAJOR" ]; then
  log "Installing Node ${NODE_MAJOR}"
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | sudo -E bash -
  sudo apt-get install -y -qq nodejs
else
  log "Node $(node -v) already present"
fi

# ── 3. Service user ──────────────────────────────────────────────
# The app runs as its own unprivileged user. It is internet-facing and holds
# database credentials; it must not be able to touch anything else.
if ! id -u "$APP_USER" >/dev/null 2>&1; then
  log "Creating service user '${APP_USER}'"
  sudo useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"
fi

# ── 4. Code ──────────────────────────────────────────────────────
if [ ! -d "$APP_DIR/.git" ]; then
  log "Cloning ${REPO} (${BRANCH})"
  sudo mkdir -p "$APP_DIR"
  sudo chown "$APP_USER:$APP_USER" "$APP_DIR"
  sudo -u "$APP_USER" git clone --branch "$BRANCH" --depth 1 "$REPO" "$APP_DIR"
else
  log "Updating existing checkout"
  sudo -u "$APP_USER" git -C "$APP_DIR" fetch --depth 1 origin "$BRANCH"
  sudo -u "$APP_USER" git -C "$APP_DIR" reset --hard "origin/${BRANCH}"
fi

log "Installing production dependencies"
sudo -u "$APP_USER" bash -c "cd '$APP_DIR/backend' && npm ci --omit=dev"

# ── 5. Redis ─────────────────────────────────────────────────────
# Local, on the same box, reachable only over loopback. No separate service,
# no network hop, no third-party free-tier command limit to run into.
#
# Memory-capped and volatile ON PURPOSE: Redis holds live positions only,
# MongoDB holds everything durable, so eviction or a restart costs at most
# one ping's worth of state that the next position update replaces.
log "Configuring Redis (loopback only, 256MB, no persistence)"
sudo tee /etc/redis/redis.conf.d-convoy.conf >/dev/null <<'REDISCONF'
bind 127.0.0.1 ::1
protected-mode yes
maxmemory 256mb
maxmemory-policy allkeys-lru
save ""
appendonly no
REDISCONF
if ! grep -q "redis.conf.d-convoy.conf" /etc/redis/redis.conf; then
  echo "include /etc/redis/redis.conf.d-convoy.conf" | sudo tee -a /etc/redis/redis.conf >/dev/null
fi
sudo systemctl enable --now redis-server
sudo systemctl restart redis-server

# ── 6. systemd ───────────────────────────────────────────────────
log "Installing the systemd unit"
sudo cp "$APP_DIR/backend/deploy/convoy-api.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable convoy-api

# ── 7. nginx ─────────────────────────────────────────────────────
log "Installing the nginx site"
sudo cp "$APP_DIR/backend/deploy/nginx-convoy.conf" /etc/nginx/sites-available/convoy
sudo ln -sf /etc/nginx/sites-available/convoy /etc/nginx/sites-enabled/convoy
sudo rm -f /etc/nginx/sites-enabled/default

# ── 8. The firewall nobody expects ───────────────────────────────
# Oracle's Ubuntu images ship a REJECT-everything iptables ruleset that is
# separate from, and additional to, the VCN security list in the web
# console. Opening the port in the console alone leaves the VM silently
# unreachable, and it is the single most common reason an Oracle box
# "doesn't work" — the request is dropped on the machine itself.
log "Opening ports 80 and 443 in the local firewall"
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT || true
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT || true
if command -v netfilter-persistent >/dev/null 2>&1; then
  sudo netfilter-persistent save
else
  sudo apt-get install -y -qq iptables-persistent || warn "Install iptables-persistent or these rules die on reboot"
fi

sudo nginx -t && sudo systemctl reload nginx

# ── Done ─────────────────────────────────────────────────────────
cat <<'SUMMARY'

────────────────────────────────────────────────────────────────
Installed. Three things left, and the app will not start without
the first one.

1. SECRETS. Create the env file (it is gitignored and must never
   be committed):

     sudo -u convoy nano /opt/convoy/backend/config.env

   Copy config.env.example and fill in:
     DATABASE=                 your MongoDB Atlas string
     JWT_SECRET=               generate a NEW one, see below
     CLOUDINARY_CLOUD_NAME=
     CLOUDINARY_API_KEY=
     CLOUDINARY_API_SECRET=
     GOOGLE_ROUTES_KEY=
     REDIS_URL=redis://127.0.0.1:6379
     NODE_ENV=production
     PORT=3000

   A fresh secret:
     node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"

2. VCN SECURITY LIST. In the Oracle console, open your subnet's
   security list and add ingress for TCP 80 and 443 from 0.0.0.0/0.
   This is SEPARATE from the iptables rules above — you need both,
   and missing either one looks identical from outside: nothing
   responds at all.

3. START IT, then add TLS:

     sudo systemctl start convoy-api
     sudo journalctl -u convoy-api -f        # watch it come up
     curl localhost:3000/api/v1/health       # expect {"status":"success"}

   Then, once a domain points at this VM's public IP:
     sudo apt-get install -y certbot python3-certbot-nginx
     sudo certbot --nginx -d api.yourdomain.com

   Edit server_name in /etc/nginx/sites-available/convoy first.

To deploy a change later:
     sudo -u convoy git -C /opt/convoy pull
     sudo -u convoy bash -c "cd /opt/convoy/backend && npm ci --omit=dev"
     sudo systemctl restart convoy-api
────────────────────────────────────────────────────────────────

SUMMARY
