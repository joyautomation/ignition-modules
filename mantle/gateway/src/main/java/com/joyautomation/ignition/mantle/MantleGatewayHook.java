package com.joyautomation.ignition.mantle;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.web.nav.NavigationModel;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.joyautomation.ignition.mantle.config.BrokerConnectionConfig;
import com.joyautomation.ignition.mantle.config.MqttConnectionExtensionPoint;
import com.joyautomation.ignition.mantle.config.SparkplugConnectionProfile;
import com.joyautomation.ignition.mantle.config.SparkplugConnections;
import com.joyautomation.ignition.mantle.mqtt.BrokerConnection;
import com.joyautomation.ignition.mantle.status.ModuleStatus;
import com.joyautomation.ignition.mantle.status.StatusRoutes;
import com.joyautomation.ignition.mantle.tags.ManagedTagSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MantleGatewayHook extends AbstractGatewayModuleHook {
    public static final String MODULE_ID = "com.joyautomation.mantle";
    private static final Logger logger = LoggerFactory.getLogger("Mantle");
    /** The Historian module's provider config, so the status page can link to it when there is no historian. */
    private static final ResourceType HISTORIAN_PROVIDER =
        new ResourceType("com.inductiveautomation.historian", "historian-provider");
    private static final String HISTORIAN_PAGE = "/services/historian/providers";

    private record Running(BrokerConnection connection, ManagedTagSink sink, BiPredicate<String, Object> writer,
                           String provider) {
    }

    private GatewayContext context;
    private NamedResourceHandler<ExtensionPointConfig<SparkplugConnectionProfile, ?>> connections;

    private final Object lock = new Object();
    private final Map<String, Running> running = new HashMap<>();
    /** One sink per tag provider. They outlive connections, so two brokers can feed one provider. */
    private final Map<String, ManagedTagSink> sinks = new HashMap<>();

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        // Tell the gateway this resource type exists. Without it the type is invisible to the configuration
        // REST API — and so to any UI built on it — and connections can only be created by editing files.
        context.getConfigurationManager().getResourceTypeMetaRegistry().register(SparkplugConnections.meta());
        registerStatusPage(context);
        registerConnectionsPage(context);
        connections = NamedResourceHandler.newBuilder(SparkplugConnections.meta())
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

    private void startConnection(DecodedResource<ExtensionPointConfig<SparkplugConnectionProfile, ?>> resource) {
        String name = resource.name();
        if (!resource.enabled()) {
            logger.info("Connection '{}' is disabled.", name);
            return;
        }
        if (!(resource.config().settings().orElse(null) instanceof BrokerConnectionConfig config)) {
            logger.warn("Connection '{}' has no MQTT settings; it will not start.", name);
            return;
        }
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
    /**
     * The page where connections are added and edited. The form is the extension point's, generated from the
     * annotations on the settings record — so this is the whole of the configuration UI.
     */
    private void registerConnectionsPage(GatewayContext context) {
        new MqttConnectionExtensionPoint().getWebUiComponent(ExtensionPoint.ComponentType.EDIT_FORM)
            .ifPresent(form -> context.getWebResourceManager().getNavigationModel().getConnections()
                .addCategory("mantle", category -> category
                    .label("Sparkplug")
                    .addPage("Connections", page -> page
                        .title("Sparkplug Connections")
                        .requiredPermission(PermissionType.READ)
                        .mount("/connections/sparkplug", form))));
    }

    private void registerStatusPage(GatewayContext context) {
        SystemJsModule bundle = new SystemJsModule(MODULE_ID, "/res/mantle/mantleStatus.js");
        context.getWebResourceManager().getNavigationModel().getDiagnostics()
            .addCategory("mantle", category -> category
                .label("Mantle")
                .addPage("Sparkplug", page -> page
                    .title("Mantle — Sparkplug status")
                    .requiredPermission(PermissionType.READ)
                    // "MantleStatus" is the named export of the UMD bundle
                    .mount("/diagnostics/mantle-status", "MantleStatus", bundle)));
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
            .handler((request, response) -> StatusRoutes.status(snapshot(), links()))
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
            return new ModuleStatus.Connection(s.name(), s.brokerUrl(), s.hostId(), r.provider(),
                r.sink().historian(), true, c.isConnected(), c.lastError(),
                s.groups().stream().sorted().toList(), c.host().counters(), c.host().nodeStatus());
        }).toList();
    }

    /**
     * Asks the gateway where its own configuration pages are. A module that tells you to go and configure
     * something should be able to take you there, and the navigation model knows the answer better than a
     * hardcoded path does.
     */
    private ModuleStatus.Links links() {
        return new ModuleStatus.Links(navUrl(HISTORIAN_PROVIDER, HISTORIAN_PAGE));
    }

    /**
     * Where the gateway keeps a given kind of configuration. Asks the navigation model first, because a page
     * that moves takes its own link with it — but only 19 of the gateway's 63 pages advertise the resource type
     * they edit, and the Historian module's is not one of them. So there is a fallback, and it is checked
     * against the navigation model too rather than trusted blindly: a link that 404s is worse than none.
     */
    private String navUrl(ResourceType resourceType, String fallbackUrl) {
        try {
            NavigationModel nav = context.getWebResourceManager().getNavigationModel();
            Optional<String> byType = nav.findNavLocationForResourceType(resourceType)
                .map(location -> location.mount().url());
            if (byType.isPresent()) {
                return "/app" + byType.get();
            }
            boolean exists = nav.getSections().stream()
                .flatMap(section -> section.getCategories().stream())
                .flatMap(category -> category.pages().stream())
                .anyMatch(page -> page.mount() != null && fallbackUrl.equals(page.mount().url()));
            return exists ? "/app" + fallbackUrl : null;
        } catch (Exception e) {
            logger.debug("Could not resolve a nav location for {}", resourceType, e);
            return null;
        }
    }

    private List<BrokerConnection> connections() {
        synchronized (lock) {
            return running.values().stream().map(Running::connection).toList();
        }
    }

    @Override
    public List<? extends ExtensionPoint<?>> getExtensionPoints() {
        return SparkplugConnections.INSTANCE.getTypes();
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
