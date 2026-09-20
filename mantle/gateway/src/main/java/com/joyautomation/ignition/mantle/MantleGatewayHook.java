package com.joyautomation.ignition.mantle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.joyautomation.ignition.mantle.config.BrokerConnectionConfig;
import com.joyautomation.ignition.mantle.mqtt.BrokerConnection;
import com.joyautomation.ignition.mantle.tags.ManagedTagSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MantleGatewayHook extends AbstractGatewayModuleHook {
    public static final String MODULE_ID = "com.joyautomation.mantle";
    private static final Logger logger = LoggerFactory.getLogger("Mantle");

    private record Running(BrokerConnection connection, ManagedTagSink sink, BiPredicate<String, Object> writer) {
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
                running.put(name, new Running(connection, sink, writer));
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

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
