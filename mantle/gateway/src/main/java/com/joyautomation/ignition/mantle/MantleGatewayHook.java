package com.joyautomation.ignition.mantle;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.joyautomation.ignition.mantle.config.BrokerConnectionConfig;
import com.joyautomation.ignition.mantle.mqtt.BrokerConnection;
import com.joyautomation.ignition.mantle.status.ModuleStatus;
import com.joyautomation.ignition.mantle.status.StatusRoutes;
import com.joyautomation.ignition.mantle.tags.ManagedTagSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MantleGatewayHook extends AbstractGatewayModuleHook {
    public static final String MODULE_ID = "com.joyautomation.mantle";
    private static final Logger logger = LoggerFactory.getLogger("Mantle");

    private record Running(BrokerConnection connection, ManagedTagSink sink, BiPredicate<String, Object> writer,
                           String provider) {
    }

    private GatewayContext context;
    private NamedResourceHandler<BrokerConnectionConfig> connections;

    private final Object lock = new Object();
    private final Map<String, Running> running = new HashMap<>();
    /** One sink per tag provider. They outlive connections, so two brokers can feed one provider. */
    private final Map<String, ManagedTagSink> sinks = new HashMap<>();

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        registerStatusPage(context);
        connections = NamedResourceHandler.newBuilder(BrokerConnectionConfig.META)
            .context(context)
            .onInitialResources(resources -> resources.forEach(this::startConnection))
            .onResourceAdded(this::startConnection)
            .onResourceUpdated(modified -> {
                stopConnection(modified.oldResource().name());
                startConnection(modified.newResource());
            })
            .onResourceRemoved(resource -> stopConnection(resource.name()))
            .build();
    }

    @Override
    public void startup(LicenseState activationState) {
        connections.startup();
        logger.info("Mantle module started.");
    }

    @Override
    public void shutdown() {
        if (connections != null) {
            connections.shutdown();
        }
        synchronized (lock) {
            running.keySet().stream().toList().forEach(this::stopConnection);
            sinks.values().forEach(ManagedTagSink::shutdown);
            sinks.clear();
        }
        logger.info("Mantle module stopped.");
    }

    private void startConnection(DecodedResource<BrokerConnectionConfig> resource) {
        String name = resource.name();
        if (!resource.enabled()) {
            logger.info("Broker connection '{}' is disabled.", name);
            return;
        }
        BrokerConnectionConfig config = resource.config();
        synchronized (lock) {
            try {
                ManagedTagSink sink = sinks.computeIfAbsent(config.tagProvider().trim(), provider ->
                    new ManagedTagSink(context, provider, config.historize(), config.historyProvider()));

                String hostId = config.hostId() == null || config.hostId().isBlank() ? name : config.hostId().trim();
                BrokerConnection connection = new BrokerConnection(new BrokerConnection.Settings(name,
                    config.brokerUrl(), config.username(), () -> password(config), config.clientId(),
                    config.keepAliveOrDefault(), hostId, config.groupIdSet(), config.reorderTimeoutOrDefault()), sink);

                BiPredicate<String, Object> writer = connection.host()::write;
                sink.addWriter(writer);
                // nodes we knew before a restart may be quiet; ask rather than wait for them to speak
                connection.setOnConnected(() -> connection.host().requestRebirths(sink.knownNodes()));
                running.put(name, new Running(connection, sink, writer, config.tagProvider().trim()));
                connection.start();
                logger.info("Broker connection '{}' starting: {} -> [{}]", name, config.brokerUrl(),
                    config.tagProvider());
            } catch (Exception e) {
                logger.error("Could not start broker connection '{}'", name, e);
            }
        }
    }

    private void stopConnection(String name) {
        synchronized (lock) {
            Running r = running.remove(name);
            if (r != null) {
                r.sink().removeWriter(r.writer());
                r.connection().stop();
                logger.info("Broker connection '{}' stopped.", name);
            }
        }
    }

    private byte[] password(BrokerConnectionConfig config) {
        if (config.password() == null) {
            return null;
        }
        try (Plaintext plaintext = Secret.create(context, config.password()).getPlaintext()) {
            return plaintext.getBytes().clone();
        } catch (Exception e) {
            logger.warn("Could not read the broker password secret", e);
            return null;
        }
    }

    // ── the gateway's own web UI ──────────────────────────────────────────────

    /**
     * Puts a Mantle page in the gateway's own navigation, under Diagnostics — which is where an administrator
     * looks when they want to know whether something is working, and this page answers exactly that.
     */
    private void registerStatusPage(GatewayContext context) {
        SystemJsModule bundle = new SystemJsModule(MODULE_ID, "/res/mantle/mantleStatus.js");
        context.getWebResourceManager().getNavigationModel().getDiagnostics()
            .addCategory("mantle", category -> category
                .label("Mantle")
                .addPage("Sparkplug", page -> page
                    .title("Mantle — Sparkplug status")
                    .requiredPermission(PermissionType.READ)
                    // "MantleStatus" is the named export of the UMD bundle
                    .mount("/mantle-status", "MantleStatus", bundle)));
    }

    /** Where the status page's bundle is served from: /res/mantle/<file>, out of the jar's "mounted" folder. */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    /** Shortens /res/<module-id>/… and /data/<module-id>/… to /res/mantle/… and /data/mantle/…. */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("mantle");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        // GET /data/mantle/status — everything the page draws, in one call.
        routes.newRoute("/status")
            .method(HttpMethod.GET)
            .type(RouteGroup.TYPE_JSON)
            // any authenticated gateway identity: the status page's own web session, an API token (so a
            // monitoring system or a test can read it), or a trusted security zone
            .requirePermission(PermissionType.READ)
            .handler((request, response) -> StatusRoutes.status(snapshot()))
            .nocache()
            .mount();

        // POST /data/mantle/rebirth/:group/:edge — the one thing an operator can do from here. A rebirth is
        // safe (it asks a node to re-announce itself) but it is still a command to the field, so it needs a
        // session with write permission rather than read.
        routes.newRoute("/rebirth/:group/:edge")
            .method(HttpMethod.POST)
            .type(RouteGroup.TYPE_JSON)
            .requirePermission(PermissionType.WRITE)
            .handler((request, response) -> StatusRoutes.rebirth(this::connections,
                request.getParameter("group"), request.getParameter("edge"), response))
            .nocache()
            .mount();
    }

    /** A consistent picture of every connection. Ordered by name so the page doesn't reshuffle on refresh. */
    private List<ModuleStatus.Connection> snapshot() {
        List<Running> live;
        synchronized (lock) {
            live = running.values().stream()
                .sorted(Comparator.comparing(r -> r.connection().settings().name()))
                .toList();
        }
        return live.stream().map(r -> {
            BrokerConnection c = r.connection();
            BrokerConnection.Settings s = c.settings();
            return new ModuleStatus.Connection(s.name(), s.brokerUrl(), s.hostId(), r.provider(), true,
                c.isConnected(), c.lastError(), s.groups().stream().sorted().toList(),
                c.host().counters(), c.host().nodeStatus());
        }).toList();
    }

    private List<BrokerConnection> connections() {
        synchronized (lock) {
            return running.values().stream().map(Running::connection).toList();
        }
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
