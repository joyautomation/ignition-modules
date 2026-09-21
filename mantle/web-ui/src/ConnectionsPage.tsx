// Mantle's configuration page: Connections → Sparkplug.
//
// Almost nothing here is Mantle's. `ExtensionPointDataGridPage` is the gateway's own configuration page —
// the same component behind Historians and OPC UA Connections — and it supplies the table, the create
// wizard, edit, delete, enable/disable, copy and the config-mode banner. The *form* is generated from the
// JSON Schema the gateway derives from the annotations on BrokerConnectionConfig, so adding a setting there
// adds a field here with no change to this file.
//
// What is left for us is the list: which columns, what the page is called, and what it says when empty.
import React from "react";

import { ExtensionPointDataGridPage, type ColumnDef } from "@inductiveautomation/ignition-gateway-lib";

/** Must match SparkplugConnections.RESOURCE_TYPE on the gateway side. */
const RESOURCE_TYPE = "com.joyautomation.mantle/connection";

/**
 * The column worth having. A connection that is down is obvious, but one that is up, told to historize and
 * running on a gateway with no historian looks fine and records nothing — so the health check reports that as
 * unhealthy and the message says what to do. See ConnectionHealth on the gateway side.
 */
function status({ row }: { row: { originalValue: any } }) {
  const result = row.originalValue.healthchecks?.status?.result;
  if (!row.originalValue.enabled) {
    return "Disabled";
  }
  if (!result) {
    return "—";
  }
  return `${result.healthy ? "OK" : "Problem"} — ${result.message ?? ""}`;
}

const COLUMN_DEFS: ColumnDef[] = [
  { fieldName: "name", header: "Name" },
  { fieldName: "healthchecks.status.result.healthy", header: "Status", cell: status },
  // The page swaps this cell for the extension point's own label, so a new connection type shows up here
  // named rather than as its type id.
  { fieldName: "config.profile.type", header: "Type" },
  { fieldName: "config.settings.brokerUrl", header: "Broker" },
  { fieldName: "config.settings.hostId", header: "Host ID" },
  { fieldName: "config.settings.tagProvider", header: "Tag Provider" },
  {
    fieldName: "config.settings.historizeByDefault",
    header: "Historize",
    cell: ({ row }) => (row.originalValue.config?.settings?.historizeByDefault ? "Yes" : "No"),
  },
  {
    fieldName: "enabled",
    header: "Enabled",
    cell: ({ row }) => (row.originalValue.enabled ? "Yes" : "No"),
  },
  { fieldName: "description", header: "Description" },
];

// A gateway with no connection is a gateway receiving nothing, so the empty page says what to do rather
// than sitting blank, and points at the status page as the place to see whether it worked.
const BLANK_STATE = {
  label: "No Sparkplug connections",
  content:
    "Mantle is not connected to a broker, so no tags are being created. Add a connection to an MQTT " +
    "broker and every metric that arrives becomes a tag automatically.",
  primaryLabel: "Create Connection",
  secondaryLabel: "View Sparkplug status",
  secondaryUrl: "/diagnostics/mantle-status",
};

export function MantleConnections() {
  return (
    <ExtensionPointDataGridPage
      pageTitle="Sparkplug Connections"
      resourceType={RESOURCE_TYPE}
      itemName="Connection"
      // The generated form lowercases this into its own prose ("Enter a name for the connection.", "Set
      // whether this connection is enabled…"), so a longer noun reads badly there. The page title and the
      // navigation category already say Sparkplug.
      resourceNoun="Connection"
      columnDefs={COLUMN_DEFS}
      blankStateConfig={BLANK_STATE}
      defaultHiddenColumns={["config.settings.tagProvider", "config.settings.historizeByDefault", "description"]}
    />
  );
}

export default MantleConnections;
