#!/usr/bin/env bash
# Certificates and a password file for the dev broker's TLS and authenticated listeners.
#
# Generated rather than committed, for two reasons: a private key in a repo is a bad habit even when it is
# worthless, and the point of the exercise is a broker whose certificate is signed by a CA the JVM has never
# heard of — which is what a broker in a real plant looks like. A public CA would prove nothing.
#
# Everything lands in dev/certs/ (gitignored). Idempotent: it does nothing if the CA is already there.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
certs="$here/dev/certs"

# The broker is reachable under two names, and the certificate has to cover both: "broker" from inside the
# compose network (which is what the gateway container uses) and "localhost" from the host (the integration
# suite, and anything run by hand).
SAN="DNS:broker,DNS:localhost,IP:127.0.0.1"
DAYS=3650

if [[ -f "$certs/ca.crt" && -f "$certs/broker.crt" && -f "$certs/passwd" ]]; then
    echo "dev certs already present in dev/certs (delete the directory to regenerate)"
    exit 0
fi

mkdir -p "$certs"
cd "$certs"

echo "generating a private CA and a broker certificate..."
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days "$DAYS" \
    -subj "/O=Joy Automation/CN=Mantle Dev CA" 2>/dev/null

openssl req -newkey rsa:2048 -nodes -keyout broker.key -out broker.csr \
    -subj "/O=Joy Automation/CN=broker" 2>/dev/null

openssl x509 -req -in broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out broker.crt \
    -days "$DAYS" -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$SAN") 2>/dev/null

# A client certificate for the mutual-TLS listener, signed by the same CA. PKCS#8, because that is what
# Mantle reads — openssl's default for -newkey since 3.0, but asked for explicitly so this does not quietly
# change under us.
echo "generating a client certificate..."
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr \
    -subj "/O=Joy Automation/CN=mantle-client" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pk8.key 2>/dev/null
mv client.pk8.key client.key
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt \
    -days "$DAYS" -extfile <(printf 'extendedKeyUsage=clientAuth\n') 2>/dev/null

rm -f broker.csr client.csr ca.srl

# Mosquitto runs as uid 1883 in the official image and refuses a key it cannot read.
chmod 644 broker.key ca.key client.key

# The password file for the authenticated listeners. mosquitto_passwd lives in the broker image, so use it
# from there rather than depending on it being installed here.
echo "generating the broker password file..."
docker run --rm -v "$certs:/out" eclipse-mosquitto:2 \
    sh -c 'touch /out/passwd && mosquitto_passwd -b /out/passwd mantle mantle-dev-password && chmod 644 /out/passwd'

echo "dev certs written to dev/certs:"
ls -1 "$certs"
