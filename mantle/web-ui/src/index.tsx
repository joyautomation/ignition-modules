// The Mantle status page. The gateway loads this bundle through SystemJS and renders the named export below
// at the route the gateway hook mounted.
//
// Deliberately plain React with fetch: the page is a table that refreshes, and a state library would be more
// machinery than the job needs. React itself comes from the gateway (see `externals` in webpack.config.js).
import React, { useCallback, useEffect, useRef, useState } from "react";

import type { Status } from "./types";
import { StatusView } from "./StatusView";

/** Relative, so the browser sends the gateway session cookie. Matches getMountPathAlias() in the hook. */
const STATUS_URL = "/data/mantle/status";
const REFRESH_MS = 2000;

export function MantleStatus() {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  // the first load shows a spinner; later ones must not make the page flicker every two seconds
  const loaded = useRef(false);

  const load = useCallback(async () => {
    try {
      const response = await fetch(STATUS_URL, { headers: { Accept: "application/json" } });
      if (!response.ok) {
        throw new Error(`the gateway answered ${response.status}`);
      }
      setStatus(await response.json());
      setError(null);
      loaded.current = true;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const rebirth = useCallback(
    async (group: string, edge: string) => {
      // A rebirth is a command to the field, so the route wants WRITE and the gateway wants its CSRF header.
      const token = document.querySelector<HTMLMetaElement>('meta[name="csrf-token"]')?.content;
      await fetch(`/data/mantle/rebirth/${encodeURIComponent(group)}/${encodeURIComponent(edge)}`, {
        method: "POST",
        headers: token ? { "X-CSRF-Token": token } : {},
      });
      load();
    },
    [load],
  );

  return <StatusView status={status} error={error} loaded={loaded.current} onRebirth={rebirth} />;
}

export default MantleStatus;
