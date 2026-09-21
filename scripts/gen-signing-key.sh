#!/usr/bin/env bash
# A self-signed code-signing key for the module, and the four repository secrets that let CI use it.
#
#   scripts/gen-signing-key.sh                 # ./.signing/mantle.p12 + mantle.p7b
#   scripts/gen-signing-key.sh --upload        # ...and push the four secrets straight to GitHub
#   scripts/gen-signing-key.sh --secrets       # ...or print them, to paste by hand
#
# Self-signed is enough to be listed on the Ignition Module Showcase — a leading open-source Showcase vendor
# ships exactly that. What a CA certificate buys is a cleaner install prompt on the gateway, nothing else.
# See docs/releasing.md.
#
# The output is a signing identity. If it leaks, someone else can publish a module that installs as Joy
# Automation. .signing/ is gitignored, as are *.p12 and *.p7b; keep the passphrase somewhere real.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/.signing"
alias_name="${SIGNING_ALIAS:-mantle}"
keystore="$out/mantle.p12"
chain="$out/mantle.p7b"
# Ten years: a self-signed certificate's expiry is not doing much work, and a module that stops installing
# because a key quietly lapsed is a bad day.
days=3650

mode="${1:-}"

if [ -f "$keystore" ] && [ "$mode" != "--secrets" ] && [ "$mode" != "--upload" ]; then
    echo "$keystore already exists. Delete .signing/ to make a new identity, or pass --upload (or"
    echo "--secrets) to send the repository secrets for the existing one."
    exit 0
fi

mkdir -p "$out"
chmod 700 "$out"

if [ ! -f "$keystore" ]; then
    if [ -z "${SIGNING_PASSWORD:-}" ]; then
        read -r -s -p "Passphrase for the new signing keystore: " SIGNING_PASSWORD; echo
        read -r -s -p "Again: " confirm; echo
        [ "$SIGNING_PASSWORD" = "$confirm" ] || { echo "they don't match" >&2; exit 1; }
    fi
    [ -n "$SIGNING_PASSWORD" ] || { echo "a passphrase is required" >&2; exit 1; }

    echo "generating a $days-day self-signed code-signing key..."
    keytool -genkeypair -alias "$alias_name" -keyalg RSA -keysize 3072 -validity "$days" \
        -dname "CN=Joy Automation, O=Joy Automation LLC, C=US" \
        -ext KeyUsage=digitalSignature -ext ExtendedKeyUsage=codeSigning \
        -keystore "$keystore" -storetype PKCS12 \
        -storepass "$SIGNING_PASSWORD" -keypass "$SIGNING_PASSWORD" >/dev/null

    # The Gradle plugin wants the certificate chain as a separate PKCS#7 file; it will not sign without one,
    # whatever is in the keystore.
    keytool -exportcert -alias "$alias_name" -keystore "$keystore" -storepass "$SIGNING_PASSWORD" \
        -rfc -file "$out/mantle.crt" >/dev/null
    openssl crl2pkcs7 -nocrl -certfile "$out/mantle.crt" -out "$chain"
    chmod 600 "$keystore" "$chain" "$out/mantle.crt"
    echo "wrote $keystore and $chain"
    echo
    echo "To sign locally, put these in ~/.gradle/gradle.properties (never in the repo):"
    echo "  ignition.signing.keystoreFile=$keystore"
    echo "  ignition.signing.keystorePassword=<the passphrase>"
    echo "  ignition.signing.certFile=$chain"
    echo "  ignition.signing.certAlias=$alias_name"
    echo "  ignition.signing.certPassword=<the passphrase>"
fi

# Uploading beats printing: the keystore is a signing identity, and base64 of it scrolling through a
# terminal is a copy of that identity in scrollback, in a paste buffer, and possibly in a screen recording.
# gh reads the files directly and nothing is echoed.
if [ "$mode" = "--upload" ]; then
    if [ -z "${SIGNING_PASSWORD:-}" ]; then
        read -r -s -p "Passphrase for $keystore: " SIGNING_PASSWORD; echo
    fi
    # Check it before uploading. A wrong passphrase here becomes a failed release later, which is a much
    # worse place to discover a typo.
    if ! keytool -list -keystore "$keystore" -storepass "$SIGNING_PASSWORD" -alias "$alias_name" \
            >/dev/null 2>&1; then
        echo "that passphrase does not open $keystore (or alias '$alias_name' is not in it)" >&2
        exit 1
    fi

    repo="${SIGNING_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
    echo "uploading four secrets to $repo..."
    base64 -w0 "$keystore" | gh secret set MODULE_SIGNING_KEYSTORE_B64 --repo "$repo"
    base64 -w0 "$chain"    | gh secret set MODULE_SIGNING_CERT_B64     --repo "$repo"
    printf '%s' "$alias_name"        | gh secret set MODULE_SIGNING_ALIAS         --repo "$repo"
    printf '%s' "$SIGNING_PASSWORD"  | gh secret set MODULE_SIGNING_KEYSTORE_PASS --repo "$repo"

    echo
    echo "done. $repo now has:"
    gh secret list --repo "$repo" | grep MODULE_SIGNING || true
    echo
    echo "Cut a release with:  git tag mantle/v1.3.0 && git push origin mantle/v1.3.0"
    exit 0
fi

if [ "$mode" = "--secrets" ]; then
    echo
    echo "Repository secrets for .github/workflows/release.yml"
    echo "(Settings → Secrets and variables → Actions → New repository secret):"
    echo
    echo "MODULE_SIGNING_ALIAS"
    echo "$alias_name"
    echo
    echo "MODULE_SIGNING_KEYSTORE_B64"
    base64 -w0 "$keystore"; echo
    echo
    echo "MODULE_SIGNING_CERT_B64"
    base64 -w0 "$chain"; echo
    echo
    echo "MODULE_SIGNING_KEYSTORE_PASS"
    echo "<the passphrase you chose; not stored anywhere by this script>"
    echo
    echo "Those two base64 blobs are the signing identity in plain text. Paste them into GitHub and clear"
    echo "your scrollback."
fi
