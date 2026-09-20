package com.joyautomation.ignition.mantle.mqtt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.lifecycle.Mqtt3ClientReconnector;
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect;
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3ConnectBuilder;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.joyautomation.ignition.mantle.sparkplug.HostState;
import com.joyautomation.ignition.mantle.sparkplug.TagSink;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One MQTT session acting as a Sparkplug B host application: STATE birth and death certificates, the subscription,
 * and the pipe between the broker and a {@link HostState}.
 */
public class BrokerConnection implements HostState.Outbound {
    private static final String STATE_PREFIX = "spBv1.0/STATE/";
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    /** What a connection needs to know; kept free of gateway types so it can run in a plain JVM. */
    public record Settings(String name, String brokerUrl, String username, Supplier<byte[]> password,
                           String clientId, int keepAliveSeconds, String hostId, Set<String> groups,
                           long reorderTimeoutMs) {
    }

    private final Logger logger;
    private final Settings settings;
    private final String stateTopic;
    private final HostState host;
    private final Mqtt3AsyncClient client;
    private final SparkplugBPayloadEncoder encoder = new SparkplugBPayloadEncoder();
    /** one thread, so messages reach the host in arrival order and tag work stays off the MQTT IO thread */
    private final ExecutorService inbound;
    private final ScheduledExecutorService timer;

    private volatile boolean closing;
    private volatile boolean connected;
    private volatile long stateTimestamp;
    private volatile Runnable onConnected = () -> { };
    private volatile String lastError;

    public BrokerConnection(Settings settings, TagSink sink) {
        this.settings = settings;
        this.logger = LoggerFactory.getLogger("Mantle.Connection." + settings.name());
        this.stateTopic = STATE_PREFIX + settings.hostId();
        this.inbound = Executors.newSingleThreadExecutor(r -> daemon(r, "mantle-" + settings.name() + "-inbound"));
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mantle-" + settings.name() + "-timer"));
        this.host = new HostState(settings.groups(), settings.reorderTimeoutMs(), sink, this, timer);
        this.client = buildClient();
    }

    public HostState host() {
        return host;
    }

    public Settings settings() {
        return settings;
    }

    public boolean isConnected() {
        return connected;
    }

    public String lastError() {
        return lastError;
    }

    /** Runs on the inbound thread each time the session is established and subscribed. */
    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    public void start() {
        client.connect(buildConnect()).whenComplete((ack, error) -> {
            if (error != null) {
                // the disconnected listener has already scheduled the retry
                logger.warn("Could not connect to {}: {}", settings.brokerUrl(), rootMessage(error));
            }
        });
    }

    public void stop() {
        closing = true;
        try {
            if (connected) {
                // a graceful disconnect discards the will, so say goodbye explicitly
                client.publishWith().topic(stateTopic).qos(MqttQos.AT_LEAST_ONCE).retain(true)
                    .payload(statePayload(false)).send().get(2, TimeUnit.SECONDS);
            }
            client.disconnect().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Unclean disconnect from {}", settings.brokerUrl(), e);
        }
        connected = false;
        host.connectionLost();
        inbound.shutdownNow();
        timer.shutdownNow();
    }

    // ── session ──────────────────────────────────────────────────────────────

    private Mqtt3AsyncClient buildClient() {
        URI uri = URI.create(settings.brokerUrl().trim());
        String scheme = uri.getScheme().toLowerCase();
        boolean tls = Set.of("ssl", "tls", "mqtts", "wss").contains(scheme);
        boolean webSocket = scheme.startsWith("ws");
        int port = uri.getPort() > 0 ? uri.getPort() : webSocket ? (tls ? 443 : 80) : (tls ? 8883 : 1883);

        String clientId = settings.clientId() == null || settings.clientId().isBlank()
            ? "joy-" + settings.hostId() : settings.clientId().trim();

        Mqtt3ClientBuilder builder = MqttClient.builder().useMqttVersion3()
            .identifier(clientId)
            .serverHost(uri.getHost())
            .serverPort(port)
            .addConnectedListener(context -> inbound.execute(this::sessionEstablished))
            .addDisconnectedListener(this::sessionLost);
        if (tls) {
            builder = builder.sslWithDefaultConfig();
        }
        if (webSocket) {
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "mqtt" : uri.getPath().substring(1);
            builder = builder.webSocketConfig().serverPath(path).applyWebSocketConfig();
        }
        return builder.buildAsync();
    }

    /**
     * A new CONNECT for every attempt: the will is part of it, and Sparkplug wants the STATE death certificate to
     * carry the same timestamp as the birth certificate of the session it ends.
     */
    private Mqtt3Connect buildConnect() {
        stateTimestamp = System.currentTimeMillis();
        Mqtt3ConnectBuilder builder = Mqtt3Connect.builder()
            .cleanSession(true)
            .keepAlive(settings.keepAliveSeconds())
            .willPublish().topic(stateTopic).qos(MqttQos.AT_LEAST_ONCE).retain(true)
            .payload(statePayload(false)).applyWillPublish();
        if (settings.username() != null && !settings.username().isBlank()) {
            byte[] password = settings.password().get();
            var auth = builder.simpleAuth().username(settings.username());
            builder = (password == null ? auth : auth.password(password)).applySimpleAuth();
        }
        return builder.build();
    }

    private byte[] statePayload(boolean online) {
        return ("{\"online\":" + online + ",\"timestamp\":" + stateTimestamp + "}").getBytes(StandardCharsets.UTF_8);
    }

    private void sessionEstablished() {
        try {
            // our own STATE topic is in the subscription so a stale death certificate can be corrected
            client.subscribeWith().topicFilter(stateTopic).qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::stateReceived).send().get(10, TimeUnit.SECONDS);
            if (settings.groups().isEmpty()) {
                subscribe("spBv1.0/+/+/#");
            } else {
                for (String group : settings.groups()) {
                    subscribe("spBv1.0/" + group + "/#");
                }
            }
            // subscribe first, then announce: an edge that wakes on our STATE must find us listening
            publishState(true);
            connected = true;
            lastError = null;
            logger.info("Connected to {} as Sparkplug host '{}'", settings.brokerUrl(), settings.hostId());
            onConnected.run();
        } catch (Exception e) {
            lastError = rootMessage(e);
            logger.warn("Could not establish the Sparkplug session on {}", settings.brokerUrl(), e);
        }
    }

    private void subscribe(String filter) throws Exception {
        // QoS 0 for data is what the spec prescribes; ordering is the sequence number's job
        client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_MOST_ONCE)
            .callback(this::messageReceived).send().get(10, TimeUnit.SECONDS);
    }

    private void messageReceived(Mqtt3Publish publish) {
        String topic = publish.getTopic().toString();
        byte[] bytes = publish.getPayloadAsBytes();
        if (topic.startsWith(STATE_PREFIX)) {
            return;
        }
        try {
            inbound.execute(() -> host.handleMessage(topic, bytes));
        } catch (RuntimeException rejected) {
            // shutting down
        }
    }

    private void stateReceived(Mqtt3Publish publish) {
        String body = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
        if (connected && !closing && body.contains("\"online\":false")) {
            // someone's will for our host ID fired while we are alive (an old session, a standby): set it straight
            logger.info("Saw an offline STATE for '{}' while online; republishing", settings.hostId());
            publishState(true);
        }
    }

    private void publishState(boolean online) {
        client.publishWith().topic(stateTopic).qos(MqttQos.AT_LEAST_ONCE).retain(true)
            .payload(statePayload(online)).send();
    }

    private void sessionLost(MqttClientDisconnectedContext context) {
        boolean wasConnected = connected;
        connected = false;
        if (closing) {
            return;
        }
        lastError = rootMessage(context.getCause());
        if (wasConnected) {
            logger.warn("Lost connection to {}: {}", settings.brokerUrl(), lastError);
            try {
                inbound.execute(host::connectionLost);
            } catch (RuntimeException rejected) {
                // shutting down
            }
        }
        Mqtt3ClientReconnector reconnector = (Mqtt3ClientReconnector) context.getReconnector();
        long delay = Math.min(MAX_RECONNECT_DELAY_MS, 1000L << Math.min(reconnector.getAttempts(), 5));
        reconnector.reconnect(true)
            .resubscribeIfSessionExpired(false)
            .republishIfSessionExpired(false)
            .delay(delay, TimeUnit.MILLISECONDS)
            .connect(buildConnect());
    }

    // ── outbound ─────────────────────────────────────────────────────────────

    @Override
    public void publishCommand(String group, String edge, String device, SparkplugBPayload payload) {
        if (!connected) {
            return;
        }
        String topic = device == null
            ? "spBv1.0/" + group + "/NCMD/" + edge
            : "spBv1.0/" + group + "/DCMD/" + edge + "/" + device;
        try {
            client.publishWith().topic(topic).qos(MqttQos.AT_MOST_ONCE).retain(false)
                .payload(encoder.getBytes(payload, false)).send();
        } catch (Exception e) {
            logger.warn("Could not publish to {}", topic, e);
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) {
            return null;
        }
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
