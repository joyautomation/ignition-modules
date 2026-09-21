// TLS and authenticated brokers. Every broker in a plant has at least a username and a password, and most
// have a certificate signed by the plant's own CA — so "it works against an anonymous mosquitto" is not
// evidence of much.
//
// The finding these tests protect: Mantle needs no TLS settings of its own. Ignition loads every certificate
// in data/certificates/supplemental into the JVM's default trust store, and that is the trust store Mantle's
// ssl:// connections use, so trusting a private CA is a gateway-level act an Ignition administrator already
// knows how to perform. scripts/lib.sh trust_dev_ca is the dev stack doing exactly that. If someone
// "helpfully" adds a truststore field to BrokerConnectionConfig, these are the tests that should make them
// justify it.
package mantle

import (
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The connection scripts/dev-up.sh does NOT create, because an embedded secret is encrypted with the
// gateway's own key and so cannot be committed to the repo. Create it by hand (mantle/README.md says how)
// and these tests start asserting on it.
const (
	tlsConnection  = "tls-broker"
	mtlsConnection = "mtls-broker"
)

// Marker for a skip that means the *environment* was not set up, as opposed to a deliberate skip for a known
// bug. CI fails on this one, because a TLS test that quietly stops running is how TLS coverage rots.
const setupMissing = "SETUP-MISSING:"

// A CONNECT packet for MQTT 3.1.1 with client id "probe" and no credentials — the smallest thing a broker
// must answer, and so the smallest thing that proves it refused to.
var mqttConnect = []byte{
	0x10, 17, // CONNECT, remaining length
	0x00, 0x04, 'M', 'Q', 'T', 'T', // protocol name
	0x04,       // level 4 (3.1.1)
	0x02,       // flags: clean session, no username/password
	0x00, 0x3c, // keepalive 60
	0x00, 0x05, 'p', 'r', 'o', 'b', 'e', // client id
}

// The broker's certificate is signed by a CA generated for this repo, which nothing on the machine trusts.
// That is the point: it is the same situation as a plant's own CA, and a public certificate would prove
// nothing about the path that matters.
func TestTheBrokersTlsListenerIsSignedByTheDevCa(t *testing.T) {
	pem, err := os.ReadFile(filepath.Join("..", "..", "dev", "certs", "ca.crt"))
	if err != nil {
		t.Skipf("%s no dev CA — run scripts/gen-dev-certs.sh (%v)", setupMissing, err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		t.Fatal("dev/certs/ca.crt is not a PEM certificate")
	}

	// Verified against the dev CA and nothing else: if the broker ever starts presenting something signed by
	// a public CA, or by nobody, this fails rather than quietly passing.
	conn, err := tls.Dial("tcp", "localhost:8883", &tls.Config{RootCAs: roots, ServerName: "localhost"})
	if err != nil {
		t.Fatalf("TLS handshake with the broker failed: %v", err)
	}
	defer conn.Close()

	chain := conn.ConnectionState().PeerCertificates
	if len(chain) == 0 {
		t.Fatal("the broker presented no certificate")
	}
	if chain[0].Subject.CommonName != "broker" {
		t.Errorf("broker certificate CN = %q, want broker", chain[0].Subject.CommonName)
	}
}

// An anonymous client must not get onto the authenticated listener. If this passes when it should not, the
// broker's per-listener settings have reverted to mosquitto's global ones and every other test in this file
// is proving nothing.
func TestTheAuthenticatedListenerRefusesAnonymousClients(t *testing.T) {
	// A bare TCP connect always succeeds; MQTT rejects at CONNECT, so send one and read the CONNACK.
	code := connackCode(t, "localhost:1884", mqttConnect)
	// 0 is "accepted", 4 is "bad user name or password", 5 is "not authorized"
	if code == 0 {
		t.Error("the authenticated listener accepted an anonymous client — check per_listener_settings in dev/mosquitto.conf")
	}
}

// The module's own view of a TLS connection: connected, and recording. This is the end of the chain the
// supplemental CA makes possible.
func TestATlsConnectionIsHealthy(t *testing.T) {
	requireHealthyConnection(t, tlsConnection)
}

// Asserts one named connection is connected, and skips when this gateway has not been given it — the TLS
// connections cannot be seeded from a file, because an embedded secret is encrypted with the gateway's own
// key. mantle/README.md has the commands.
func requireHealthyConnection(t *testing.T, name string) {
	t.Helper()
	connections, err := gw.Connections()
	must(t, err)

	for _, c := range connections {
		if c.Name != name {
			continue
		}
		// An untrusted CA fails here and nowhere else, so name it: this is the assertion that proves the
		// gateway's supplemental trust store reaches Mantle's MQTT client.
		if strings.Contains(c.Message, "certification path") {
			t.Fatalf("%s cannot verify the broker's certificate — is the dev CA in the gateway's "+
				"data/certificates/supplemental? (%s)", name, c.Message)
		}
		if strings.Contains(c.Message, "certificate_required") {
			t.Fatalf("%s did not present a client certificate the broker would accept: %s", name, c.Message)
		}
		if !c.Healthy {
			t.Fatalf("%s is unhealthy: %s", name, c.Message)
		}
		return
	}
	t.Skipf("%s no %q connection on this gateway — scripts/dev-up.sh creates it", setupMissing, name)
}

// The mutual-TLS listener must refuse a client that brings no certificate. Without this, a "connected"
// mtls-broker would prove nothing at all — the listener could simply not be asking.
func TestTheMutualTlsListenerDemandsAClientCertificate(t *testing.T) {
	// Under TLS 1.3 the client's certificate is sent *after* the handshake looks complete, so tls.Dial
	// returns a usable-looking connection and the rejection only arrives when the connection is actually
	// used. Asserting on Dial alone passes for the wrong reason — which is exactly what this test did first
	// time round. So send a real MQTT CONNECT and insist it goes nowhere.
	conn, err := tls.Dial("tcp", "localhost:8884", &tls.Config{RootCAs: devCA(t), ServerName: "localhost"})
	if err != nil {
		if !strings.Contains(err.Error(), "certificate") {
			t.Errorf("handshake failed, but not over a certificate: %v", err)
		}
		return // rejected at handshake time (TLS 1.2), which is also correct
	}
	defer conn.Close()
	must(t, conn.SetDeadline(time.Now().Add(10*time.Second)))

	if _, err := conn.Write(mqttConnect); err != nil {
		return // refused on write: correct
	}
	if _, err := io.ReadFull(conn, make([]byte, 4)); err == nil {
		t.Error("the mutual-TLS listener answered an MQTT CONNECT from a client with no certificate")
	}
}

// And with a certificate it must succeed — otherwise the test above is passing for the wrong reason.
func TestTheMutualTlsListenerAcceptsTheDevClientCertificate(t *testing.T) {
	certificate, err := tls.LoadX509KeyPair(
		filepath.Join("..", "..", "dev", "certs", "client.crt"),
		filepath.Join("..", "..", "dev", "certs", "client.key"))
	if err != nil {
		t.Skipf("%s no dev client certificate — run scripts/gen-dev-certs.sh (%v)", setupMissing, err)
	}

	conn, err := tls.Dial("tcp", "localhost:8884", &tls.Config{
		RootCAs:      devCA(t),
		ServerName:   "localhost",
		Certificates: []tls.Certificate{certificate},
	})
	if err != nil {
		t.Fatalf("the dev client certificate was refused: %v", err)
	}
	conn.Close()
}

// The module's own end of it: Mantle reading a PEM certificate and key off disk and presenting them.
func TestAMutualTlsConnectionIsHealthy(t *testing.T) {
	requireHealthyConnection(t, mtlsConnection)
}

func devCA(t *testing.T) *x509.CertPool {
	t.Helper()
	pem, err := os.ReadFile(filepath.Join("..", "..", "dev", "certs", "ca.crt"))
	if err != nil {
		t.Skipf("%s no dev CA — run scripts/gen-dev-certs.sh (%v)", setupMissing, err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		t.Fatal("dev/certs/ca.crt is not a PEM certificate")
	}
	return roots
}

// Whatever else is configured, nothing should be quietly broken.
func TestEveryConnectionIsHealthy(t *testing.T) {
	connections, err := gw.Connections()
	must(t, err)
	if len(connections) == 0 {
		t.Fatal("the gateway reports no Mantle connections at all")
	}
	for _, c := range connections {
		if !c.Healthy {
			t.Errorf("connection %q is unhealthy: %s", c.Name, c.Message)
		}
	}
}

// connackCode sends one CONNECT packet and returns the return code from the CONNACK.
func connackCode(t *testing.T, address string, connect []byte) byte {
	t.Helper()
	conn, err := net.Dial("tcp", address)
	if err != nil {
		t.Skipf("no broker at %s: %v", address, err)
	}
	defer conn.Close()
	must(t, conn.SetDeadline(time.Now().Add(10 * time.Second)))

	if _, err := conn.Write(connect); err != nil {
		t.Fatalf("writing CONNECT: %v", err)
	}
	connack := make([]byte, 4)
	if _, err := io.ReadFull(conn, connack); err != nil {
		t.Fatalf("reading CONNACK: %v", err)
	}
	if connack[0] != 0x20 {
		t.Fatalf("expected a CONNACK, got packet type 0x%02x", connack[0])
	}
	return connack[3]
}

// ── a second broker implementation ───────────────────────────────────────

// "It works against Mosquitto" is a statement about Mosquitto. EMQX is the broker most likely to be found in
// a plant after it, and it differs in the places Sparkplug leans on hardest: retained messages, will
// delivery and session takeover. With HiveMQ below that makes three implementations. AWS IoT Core and Azure
// are still untested — they need accounts — and the product page says so.
func TestTheEmqxConnectionIsHealthy(t *testing.T) {
	requireHealthyConnection(t, "emqx-broker")
}

// HiveMQ is the broker the Sparkplug specification's own TCK is built on, which makes it the one a Sparkplug
// host has least excuse for failing against.
func TestTheHiveMqConnectionIsHealthy(t *testing.T) {
	requireHealthyConnection(t, "hivemq-broker")
}

// ── transports ───────────────────────────────────────────────────────────

// ws:// takes a different path through the client than tcp:// — a WebSocket config built from the URL's
// path — and it had never run. wss:// shares everything with this except the TLS layer, which the ssl://
// tests already cover.
func TestTheWebSocketConnectionIsHealthy(t *testing.T) {
	requireHealthyConnection(t, "ws-broker")
}
