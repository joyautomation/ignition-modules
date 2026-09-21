package com.joyautomation.ignition.mantle.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.health.HealthCheck;
import org.junit.jupiter.api.Test;

/**
 * What the configuration page's Status column says, and when. The case worth protecting is the third one: a
 * connection that is up and recording nothing is the failure this module exists to prevent, and it is only a
 * failure the page can show if the health check calls it one.
 */
class ConnectionHealthTest {
    /** A connection that cannot reach its broker says so, and says why. */
    @Test
    void aDisconnectedConnectionReportsTheBrokerAndTheError() {
        HealthCheck.Result result = check(false, "Connection refused", "Core", true);

        assertFalse(result.isHealthy());
        assertEquals("Not connected to tcp://broker:1883 — Connection refused", result.getMessage());
    }

    /** Nothing has failed yet, so there is no error to add — and no empty dash either. */
    @Test
    void aDisconnectedConnectionWithNoErrorYetJustSaysSo() {
        assertEquals("Not connected to tcp://broker:1883", check(false, null, "Core", true).getMessage());
    }

    /**
     * The one that matters. Told to historize, connected, and no historian on the gateway: this looks healthy
     * from every other angle and records nothing.
     */
    @Test
    void connectedWithHistorizingOnAndNoHistorianIsAFailure() {
        HealthCheck.Result result = check(true, null, null, true);

        assertFalse(result.isHealthy(), "a connection recording nothing must not report healthy");
        assertTrue(result.getMessage().contains("nothing is being recorded"), result.getMessage());
        assertTrue(result.getMessage().contains("no tag historian"), result.getMessage());
    }

    /** Historizing off is a decision, not a fault. No historian is then exactly what was asked for. */
    @Test
    void connectedWithHistorizingOffIsHealthy() {
        HealthCheck.Result result = check(true, null, null, false);

        assertTrue(result.isHealthy());
        assertEquals("Connected. Historizing is off.", result.getMessage());
    }

    /** The ordinary case names the historian, so an operator can see where the data is going. */
    @Test
    void connectedAndRecordingNamesTheHistorian() {
        assertEquals("Connected. Recording to Core.", check(true, null, "Core", true).getMessage());
    }

    /** A stale error from an earlier outage must not make a reconnected connection look broken. */
    @Test
    void aReconnectedConnectionIgnoresTheOldError() {
        assertTrue(check(true, "Connection refused", "Core", true).isHealthy());
    }

    private static HealthCheck.Result check(boolean connected, String lastError, String historian,
                                            boolean historize) {
        ConnectionHealth.State state = new ConnectionHealth.State() {
            @Override
            public boolean connected() {
                return connected;
            }

            @Override
            public String lastError() {
                return lastError;
            }

            @Override
            public String historian() {
                return historian;
            }

            @Override
            public boolean historize() {
                return historize;
            }

            @Override
            public String brokerUrl() {
                return "tcp://broker:1883";
            }
        };
        return ConnectionHealth.of(state).execute();
    }
}
