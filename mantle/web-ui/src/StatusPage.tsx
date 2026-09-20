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

/**
 * The gateway protects mutating routes with a CSRF token, which its web app keeps in its own store after
 * reading this endpoint. A page outside that store has to ask for it the same way. Fetched once and kept,
 * because it lasts as long as the session does.
 */
let csrf: string | null = null;
async function csrfToken(): Promise<string | null> {
  if (csrf) {
    return csrf;
  }
  try {
    const session = await fetch("/data/app/session", { headers: { Accept: "application/json" } });
    if (!session.ok) {
      return null;
    }
    const body = await session.json();
    csrf = body?.csrfToken ?? body?.userSession?.csrfToken ?? null;
  } catch {
    csrf = null;
  }
  return csrf;
}

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

  const [notice, setNotice] = useState<string | null>(null);

  const rebirth = useCallback(
    async (group: string, edge: string) => {
      const node = `${group}/${edge}`;
      try {
        // A rebirth is a command to the field, so the route wants WRITE, and the gateway wants its CSRF token
        // with it. The token lives on the gateway's own session endpoint — the same one its web app reads.
        const csrf = await csrfToken();
        const response = await fetch(
          `/data/mantle/rebirth/${encodeURIComponent(group)}/${encodeURIComponent(edge)}`,
          { method: "POST", headers: csrf ? { "X-CSRF-Token": csrf } : {} },
        );
        if (!response.ok) {
          // Say what happened. A button that quietly does nothing is worse than one that fails out loud.
          throw new Error(
            response.status === 403
              ? "the gateway refused it (403) — your account needs write permission"
              : `the gateway answered ${response.status}`,
          );
        }
        const body = await response.json().catch(() => ({}));
        setNotice(
          body.requested === false
            ? `${node}: the module does not know that node`
            : `${node}: rebirth requested`,
        );
      } catch (e) {
        setNotice(`${node}: could not request a rebirth — ${e instanceof Error ? e.message : String(e)}`);
      }
      load();
    },
    [load],
  );

  return (
    <StatusView
      status={status}
      error={error}
      loaded={loaded.current}
      notice={notice}
      onRebirth={rebirth}
      onDismissNotice={() => setNotice(null)}
    />
  );
}

export default MantleStatus;
