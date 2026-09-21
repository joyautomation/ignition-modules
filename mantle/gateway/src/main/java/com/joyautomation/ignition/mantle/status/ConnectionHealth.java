package com.joyautomation.ignition.mantle.status;

import java.util.function.Supplier;

import java.util.Optional;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.SharedHealthCheckRegistries;
import com.inductiveautomation.ignition.gateway.metrics.CriticalHealthCheck;

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
 *
 * <p>It is also a {@link CriticalHealthCheck}, the interface the gateway's own overview reads to describe a
 * problem and offer a way to fix it — the same one behind the platform's disk-full and deadlock checks. The
 * point is that the failure this module guards against is one nobody goes looking for, so it should arrive
 * unprompted rather than wait on the Sparkplug page.
 *
 * <p><b>Unverified:</b> {@code OverviewRoutes.getCriticalProblems} does read this registry and does call
 * these four methods, and these checks are registered beside the platform's own — but no banner was observed
 * on {@code /data/api/v1/overview/banners} for a deliberately broken connection. That endpoint may need a
 * browser session rather than the basic auth available to a script, so this needs looking at in a browser
 * before it is claimed anywhere.
 */
public final class ConnectionHealth extends HealthCheck implements CriticalHealthCheck {
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
    private final String connectionName;

    private ConnectionHealth(String connectionName, State state) {
        this.connectionName = connectionName;
        this.state = state;
    }

    @Override
    public String getTitle() {
        return "Sparkplug connection '%s'".formatted(connectionName);
    }

    /**
     * The banner shows this, so it has to say what to do rather than what happened — the check's own message
     * already says what happened.
     */
    @Override
    public String getResolutionText() {
        if (state.connected() && state.historize() && state.historian() == null) {
            return "Mantle is connected to %s and creating tags, but this gateway has no tag historian, so "
                .formatted(state.brokerUrl())
                + "nothing is being recorded. Configure a historian and history switches itself on as each "
                + "edge node births again — no tag needs touching.";
        }
        return "Mantle cannot reach %s, so no tags are being created or updated from it. Check the broker "
            .formatted(state.brokerUrl())
            + "address, credentials and certificate on the connection's configuration page.";
    }

    /**
     * Where to go. The historian page when that is what is missing, otherwise the connection itself. Both
     * are resolved from the gateway's own navigation elsewhere in the module; these are the paths that
     * navigation produced.
     */
    @Override
    public Optional<String> getResolutionUrl() {
        if (state.connected() && state.historize() && state.historian() == null) {
            return Optional.of("/services/historian/providers");
        }
        return Optional.of("/connections/mantle-sparkplug");
    }

    @Override
    public String getActionLabel() {
        if (state.connected() && state.historize() && state.historian() == null) {
            return "Configure a historian";
        }
        return "Check the connection";
    }

    /** Visible for testing: the check on its own, without the gateway's registry. */
    static ConnectionHealth of(State state) {
        return new ConnectionHealth("test", state);
    }

    /**
     * Registers a check for one connection. Call {@link #unregister} when the connection stops.
     *
     * <p>{@code SharedHealthCheckRegistries.getDefault()} and {@code GatewayContext.getHealthCheckRegistry()}
     * are the same object — verified, not assumed — so one registration serves both the Status column on the
     * configuration page and the gateway's own critical-problem machinery. Our checks sit in it alongside
     * {@code host.disk.fullDisk} and {@code jvm.threads.deadlock}.
     */
    public static void register(String connectionName, State state) {
        SharedHealthCheckRegistries.getDefault()
            .register(NAME_TEMPLATE.formatted(connectionName), new ConnectionHealth(connectionName, state));
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
