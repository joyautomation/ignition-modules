// The rendering half, kept apart from the fetching half so it is a pure function of the status it is given.
import React from "react";

import type { Connection, Counters, Node, Status } from "./types";

export function StatusView({
  status,
  error,
  loaded,
  notice,
  onRebirth,
  onDismissNotice,
}: {
  status: Status | null;
  error: string | null;
  loaded: boolean;
  notice: string | null;
  onRebirth: (group: string, edge: string) => void;
  onDismissNotice: () => void;
}) {
  if (!loaded && !error) {
    return <p style={styles.muted}>Loading…</p>;
  }
  if (!status) {
    return <Problem error={error} />;
  }
  return (
    <div style={styles.page}>
      {/* A refresh that fails while an older reading is on screen: say so, but keep showing the reading. */}
      {error && <Problem error={error} stale />}
      {notice && (
        <p style={styles.notice} onClick={onDismissNotice} title="click to dismiss">
          {notice}
        </p>
      )}
      {status.connections.length === 0 ? (
        <p style={styles.muted}>
          No broker connections are configured. Add one under Connections, and every metric that arrives will
          become a tag.
        </p>
      ) : (
        status.connections.map((c) => <ConnectionCard key={c.name} connection={c} onRebirth={onRebirth} />)
      )}
    </div>
  );
}

function Problem({ error, stale }: { error: string | null; stale?: boolean }) {
  return (
    <p style={{ ...styles.error, ...(stale ? styles.errorStale : {}) }}>
      {stale ? "This reading is stale — " : "Could not read the module's status: "}
      {error}
    </p>
  );
}

function ConnectionCard({
  connection,
  onRebirth,
}: {
  connection: Connection;
  onRebirth: (group: string, edge: string) => void;
}) {
  const { counters } = connection;
  return (
    <section style={styles.card}>
      <header style={styles.cardHeader}>
        <h2 style={styles.title}>
          <Dot on={connection.connected} /> {connection.name}
        </h2>
        <span style={styles.muted}>
          {connection.brokerUrl} · host ID <code>{connection.hostId}</code> · tags into{" "}
          <code>[{connection.tagProvider}]</code>
          {connection.groups.length > 0 && <> · groups {connection.groups.join(", ")}</>}
        </span>
      </header>

      {!connection.connected && connection.lastError && (
        <p style={styles.error}>Not connected: {connection.lastError}</p>
      )}

      <CounterRow counters={counters} />

      {connection.nodes.length === 0 ? (
        <p style={styles.muted}>
          No edge nodes yet. Nodes appear here as they publish their birth certificates.
        </p>
      ) : (
        <NodeTable nodes={connection.nodes} onRebirth={onRebirth} />
      )}
    </section>
  );
}

function CounterRow({ counters }: { counters: Counters }) {
  return (
    <div style={styles.counters}>
      <Counter label="Nodes online" value={`${counters.nodesOnline} / ${counters.nodesKnown}`} />
      <Counter label="Messages" value={counters.messages.toLocaleString()} />
      {/* These two mean data was lost between the edge and here. Zero is the only good number. */}
      <Counter label="Sequence gaps" value={counters.seqGaps} warn={counters.seqGaps > 0} />
      <Counter label="Decode failures" value={counters.decodeFailures} warn={counters.decodeFailures > 0} />
      <Counter label="Rebirths requested" value={counters.rebirthsRequested} />
    </div>
  );
}

function Counter({ label, value, warn }: { label: string; value: string | number; warn?: boolean }) {
  return (
    <div style={styles.counter}>
      <div style={{ ...styles.counterValue, ...(warn ? styles.counterWarn : {}) }}>{value}</div>
      <div style={styles.counterLabel}>{label}</div>
    </div>
  );
}

function NodeTable({ nodes, onRebirth }: { nodes: Node[]; onRebirth: (g: string, e: string) => void }) {
  return (
    <table style={styles.table}>
      <thead>
        <tr>
          {["Edge node", "Metrics", "Last birth", "bdSeq", ""].map((h, i) => (
            <th key={h} style={{ ...styles.th, textAlign: i === 0 || i === 4 ? "left" : "right" }}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {nodes.map((node) => (
          <NodeRows key={`${node.group}/${node.edge}`} node={node} onRebirth={onRebirth} />
        ))}
      </tbody>
    </table>
  );
}

function NodeRows({ node, onRebirth }: { node: Node; onRebirth: (g: string, e: string) => void }) {
  return (
    <>
      <tr>
        <td style={styles.td}>
          <Dot on={node.online} />{" "}
          <span style={styles.muted}>{node.group}</span> / <strong>{node.edge}</strong>
          {node.awaitingRebirth && <span style={styles.badge}>rebirth requested</span>}
        </td>
        <td style={styles.tdRight}>{node.metrics}</td>
        <td style={styles.tdRight}>{since(node.lastBirthMs)}</td>
        <td style={styles.tdRight}>{node.bdSeq < 0 ? "—" : node.bdSeq}</td>
        <td style={styles.td}>
          <button style={styles.button} onClick={() => onRebirth(node.group, node.edge)}>
            Request rebirth
          </button>
        </td>
      </tr>
      {node.devices.map((device) => (
        <tr key={device.id}>
          <td style={{ ...styles.td, paddingLeft: 32 }}>
            <Dot on={device.online} /> <span style={styles.muted}>device</span> {device.id}
          </td>
          <td style={styles.tdRight}>{device.metrics}</td>
          <td style={styles.tdRight}>{since(device.lastBirthMs)}</td>
          <td style={styles.tdRight} />
          <td style={styles.td} />
        </tr>
      ))}
    </>
  );
}

function Dot({ on }: { on: boolean }) {
  return (
    <span
      title={on ? "online" : "offline"}
      style={{ ...styles.dot, background: on ? "#3fb950" : "#8b949e" }}
    />
  );
}

/** "4s ago" reads better than a timestamp for something that changes while you watch it. */
function since(ms: number | null): string {
  if (!ms) {
    return "never";
  }
  const seconds = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m ago`;
  }
  if (seconds < 86400) {
    return `${Math.floor(seconds / 3600)}h ago`;
  }
  return `${Math.floor(seconds / 86400)}d ago`;
}

// Inline styles, and colours that read on both the light and dark gateway themes: this is one page, and a
// stylesheet in a UMD bundle would have to be injected at runtime anyway.
const styles: Record<string, React.CSSProperties> = {
  page: { display: "flex", flexDirection: "column", gap: 16, padding: 16 },
  card: {
    border: "1px solid rgba(128,128,128,0.35)",
    borderRadius: 6,
    padding: 16,
    display: "flex",
    flexDirection: "column",
    gap: 12,
  },
  cardHeader: { display: "flex", flexDirection: "column", gap: 4 },
  title: { margin: 0, fontSize: 18, fontWeight: 600 },
  muted: { opacity: 0.7, fontSize: 13 },
  error: { color: "#f85149", fontSize: 13, margin: 0 },
  errorStale: { opacity: 0.8 },
  notice: {
    margin: 0,
    padding: "6px 10px",
    borderRadius: 4,
    fontSize: 13,
    background: "rgba(128,128,128,0.15)",
    border: "1px solid rgba(128,128,128,0.3)",
    cursor: "pointer",
  },
  counters: { display: "flex", flexWrap: "wrap", gap: 24 },
  counter: { minWidth: 96 },
  counterValue: { fontSize: 22, fontWeight: 650, fontVariantNumeric: "tabular-nums" },
  counterWarn: { color: "#d29922" },
  counterLabel: { fontSize: 12, opacity: 0.7, textTransform: "uppercase", letterSpacing: 0.4 },
  table: { width: "100%", borderCollapse: "collapse", fontSize: 14 },
  th: {
    borderBottom: "1px solid rgba(128,128,128,0.35)",
    padding: "6px 8px",
    fontSize: 12,
    textTransform: "uppercase",
    letterSpacing: 0.4,
    opacity: 0.7,
    fontWeight: 600,
  },
  td: { borderBottom: "1px solid rgba(128,128,128,0.18)", padding: "6px 8px" },
  tdRight: {
    borderBottom: "1px solid rgba(128,128,128,0.18)",
    padding: "6px 8px",
    textAlign: "right",
    fontVariantNumeric: "tabular-nums",
  },
  dot: { display: "inline-block", width: 8, height: 8, borderRadius: "50%", verticalAlign: "middle" },
  badge: {
    marginLeft: 8,
    padding: "1px 6px",
    borderRadius: 10,
    fontSize: 11,
    background: "rgba(210,153,34,0.18)",
    color: "#d29922",
  },
  button: {
    padding: "3px 10px",
    fontSize: 12,
    borderRadius: 4,
    border: "1px solid rgba(128,128,128,0.45)",
    background: "transparent",
    color: "inherit",
    cursor: "pointer",
  },
};
