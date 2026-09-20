// `@inductiveautomation/ignition-gateway-lib` is the gateway's own React library. It is not on public npm —
// it lives on Inductive's private registry — so it is never installed here. It does not need to be: the
// gateway serves it at /res/sys/js/IgnitionGatewayLib.js and maps the bare specifier to it in the SystemJS
// import map on every page, so listing it in webpack's `externals` is enough to use it.
//
// That leaves TypeScript with no types for it, hence this declaration. The shapes below are the props the
// library actually reads, transcribed from its published source map (its bundle ships `sourcesContent`).
declare module "@inductiveautomation/ignition-gateway-lib" {
  import type * as React from "react";

  /** One column of the resource table. `fieldName` is a dotted path into the resource JSON. */
  export interface ColumnDef {
    fieldName: string;
    header: string;
    sortable?: boolean;
    cell?: (context: { row: { originalValue: any } }) => React.ReactNode;
  }

  export interface BlankStateConfig {
    label: string;
    content: string;
    primaryLabel?: string;
    secondaryLabel?: string;
    secondaryUrl?: string;
  }

  export interface ExtensionPointDataGridPageProps {
    /** "<module id>/<type id>" — the same string the gateway's resource REST API is mounted under. */
    resourceType: string;
    columnDefs: ColumnDef[];
    pageTitle?: string;
    /** Used in buttons and confirmations: "Create <itemName>", "Delete <itemName>". */
    itemName?: string;
    /** Used in the generated form's own prose: "Enter a name for the <resourceNoun>." */
    resourceNoun?: string;
    blankStateConfig?: BlankStateConfig;
    showMore?: Array<{ text: string; icon?: () => React.ReactNode; onClick: (item: any) => void }>;
    defaultHiddenColumns?: string[];
  }

  /**
   * The gateway's own configuration page for a resource type: the table, the create wizard, edit, delete,
   * enable/disable and the config-mode banner. It reads the extension points from
   * /data/api/v1/resources/type/<resourceType> and renders each one's form from the JSON schema the
   * extension point declares — which is why Mantle needs no form code of its own.
   */
  const ExtensionPointDataGridPage: React.FC<ExtensionPointDataGridPageProps>;
  export default ExtensionPointDataGridPage;
  export { ExtensionPointDataGridPage };
}
