/** The shape of GET /data/mantle/status. Mirrors StatusRoutes.java, which is the contract's other half. */

export interface Status {
  connections: Connection[];
  asOfMs: number;
}

export interface Connection {
  name: string;
  brokerUrl: string;
  hostId: string;
  tagProvider: string;
  /** null when the gateway has no historian at all — nothing is being recorded */
  historian: string | null;
  connected: boolean;
  lastError: string | null;
  groups: string[];
  counters: Counters;
  nodes: Node[];
}

export interface Counters {
  messages: number;
  seqGaps: number;
  rebirthsRequested: number;
  decodeFailures: number;
  nodesOnline: number;
  nodesKnown: number;
}

export interface Node {
  group: string;
  edge: string;
  online: boolean;
  bdSeq: number;
  lastBirthMs: number | null;
  metrics: number;
  awaitingRebirth: boolean;
  devices: Device[];
}

export interface Device {
  id: string;
  online: boolean;
  lastBirthMs: number | null;
  metrics: number;
}
