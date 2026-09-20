// The entry point of Mantle's web bundle. One bundle serves the whole module: the gateway maps the module
// id to this file in its SystemJS import map, and each page it mounts names one of the exports below.
//
// So a page is added here by exporting it and mounting it from MantleGatewayHook.
export { MantleStatus } from "./StatusPage";
export { MantleConnections } from "./ConnectionsPage";
