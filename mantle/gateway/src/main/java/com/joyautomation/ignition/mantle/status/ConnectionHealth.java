package com.joyautomation.ignition.mantle.status;

import java.util.function.Supplier;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.SharedHealthCheckRegistries;

/**
 * One connection's health, as the gateway asks for it: a Dropwizard health check registered under a name the
 * resource type's status delegate derives from the resource's own name (see
 * {@link com.joyautomation.ignition.mantle.config.SparkplugConnections#meta()}). The configuration page reads
 * the result straight out of the resource listing, so this is what puts a status in that table.
 *
 * <p>Two things can be wrong, and the second is the one this module exists to prevent. A connection that is
 * down is obvious and noisy. A connection that is <em>up</em>, told to historize, and pointed at a gateway
 * with no historian looks perfectly healthy while recording nothing — the silent data loss that comes from
 * something never being configured. It is reported as unhealthy, because it is.
 *
 * <p>Historizing being off is a decision, not a fault, so it is not reported at all.
 */
public final class ConnectionHealth extends HealthCheck {
    /** {@code %s} is the connection's resource name; the gateway fills it in when it gathers the check. */
    public static final String NAME_TEMPLATE = "mantle.%s.status";

    /** What the check needs to know, read at the moment it runs rather than captured. */
    public interface State {
        boolean connected();

        /** The last connection failure, or null. */
        String lastError();

        /** The tag historian new tags are pointed at, or null when the gateway has none. */
        String historian();

        /** Whether this connection was told to historize what arrives. */
        boolean historize();

        String brokerUrl();
    }

    private final State state;

    private ConnectionHealth(State state) {
        this.state = state;
    }

    /** Visible for testing: the check on its own, without the gateway's registry. */
    static ConnectionHealth of(State state) {
        return new ConnectionHealth(state);
    }

    /** Registers a check for one connection. Call {@link #unregister} when the connection stops. */
    public static void register(String connectionName, State state) {
        SharedHealthCheckRegistries.getDefault()
            .register(NAME_TEMPLATE.formatted(connectionName), new ConnectionHealth(state));
    }

    public static void unregister(String connectionName) {
        SharedHealthCheckRegistries.getDefault().unregister(NAME_TEMPLATE.formatted(connectionName));
    }

    @Override
    protected Result check() {
        if (!state.connected()) {
            String why = state.lastError();
            return Result.unhealthy("Not connected to %s%s".formatted(state.brokerUrl(),
                why == null || why.isBlank() ? "" : " — " + why));
        }
        if (state.historize() && state.historian() == null) {
            return Result.unhealthy(
                "Connected, but nothing is being recorded: this connection historizes every tag it creates "
                    + "and the gateway has no tag historian. Configure one, or turn historizing off.");
        }
        if (state.historian() == null) {
            return Result.healthy("Connected. Historizing is off.");
        }
        return Result.healthy("Connected. Recording to %s.".formatted(state.historian()));
    }
}
