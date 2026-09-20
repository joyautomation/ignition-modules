package com.joyautomation.ignition.mantle.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;

/**
 * One Sparkplug B host application connection to one broker: the settings half of the MQTT connection type
 * (see {@link MqttConnectionExtensionPoint}). Name, enabled and description come from the resource system and
 * are not repeated here. Every annotation below is a field in the gateway's configuration form.
 * <p>
 * The defaults are the point: a broker URL is the only thing that has to be filled in. Everything the broker says
 * becomes tags, and those tags are historized.
 */
public record BrokerConnectionConfig(
    @FormCategory("BROKER")
    @Label("Broker URL")
    @FormField(FormFieldType.TEXT)
    @DefaultValue("tcp://localhost:1883")
    @Required
    @Description("tcp://host:1883, ssl://host:8883, ws://host:8083/mqtt or wss://host:8084/mqtt")
    String brokerUrl,

    @FormCategory("BROKER")
    @Label("Username")
    @FormField(FormFieldType.TEXT)
    String username,

    @FormCategory("BROKER")
    @Label("Password")
    @FormField(FormFieldType.SECRET)
    SecretConfig password,

    @FormCategory("BROKER")
    @Label("Client ID")
    @FormField(FormFieldType.TEXT)
    @Description("Leave blank to derive one from the host ID.")
    String clientId,

    @FormCategory("BROKER")
    @Label("Keep Alive (s)")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("30")
    Integer keepAliveSeconds,

    @FormCategory("SPARKPLUG")
    @Label("Host ID")
    @FormField(FormFieldType.TEXT)
    @Description("Sparkplug host application ID, published on spBv1.0/STATE/<id>. Edge nodes configured with this "
        + "as their primary host will hold data in store-and-forward while it is offline. Leave blank to use the "
        + "connection's name.")
    String hostId,

    @FormCategory("SPARKPLUG")
    @Label("Group IDs")
    @FormField(FormFieldType.TEXT)
    @Description("Comma-separated Sparkplug group IDs to consume. Leave blank for every group on the broker.")
    String groupIds,

    @FormCategory("SPARKPLUG")
    @Label("Reorder Timeout (ms)")
    @FormField(FormFieldType.NUMBER)
    @DefaultValue("5000")
    @Description("How long to wait for a missing sequence number before giving up and requesting a rebirth.")
    Integer reorderTimeoutMs,

    @FormCategory("TAGS")
    @Label("Tag Provider")
    @FormField(FormFieldType.TEXT)
    @DefaultValue("Sparkplug")
    @Required
    @Description("Realtime tag provider the tags are created in. It is created if it doesn't exist. These are "
        + "ordinary tags: add alarms, scaling, scripts and security to them directly.")
    String tagProvider,

    @FormCategory("HISTORY")
    @Label("Historize By Default")
    @FormField(FormFieldType.CHECKBOX)
    @DefaultValue("true")
    @Description("New tags get history enabled when they are first created. Turn history off on any individual tag "
        + "and it stays off. Metrics the edge marks is_transient always start with history off.")
    Boolean historizeByDefault,

    @FormCategory("HISTORY")
    @Label("History Provider")
    @FormField(FormFieldType.TEXT)
    @Description("Historian to store to. Leave blank to use the gateway's first available historian.")
    String historyProvider
) {
    public static final BrokerConnectionConfig DEFAULT = new BrokerConnectionConfig(
        "tcp://localhost:1883", null, null, null, 30, null, null, 5000, "Sparkplug", true, null);

    /** Called by the extension point when the gateway validates an edit. */
    public static void validate(BrokerConnectionConfig config, ValidationErrors.Builder validator) {
        validator.checkField(config.brokerUrl() != null && config.brokerUrl()
                .matches("^(tcp|mqtt|ssl|tls|mqtts|ws|wss)://[^\\s/:]+(:\\d+)?(/.*)?$"),
            "brokerUrl", "must look like tcp://host:1883, ssl://host:8883, ws://host/path or wss://host/path");
        validator.checkField(config.tagProvider() != null && !config.tagProvider().isBlank(),
            "tagProvider", "is required");
    }

    public Set<String> groupIdSet() {
        if (groupIds == null || groupIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(groupIds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public int keepAliveOrDefault() {
        return keepAliveSeconds == null || keepAliveSeconds <= 0 ? 30 : keepAliveSeconds;
    }

    public long reorderTimeoutOrDefault() {
        return reorderTimeoutMs == null || reorderTimeoutMs <= 0 ? 5000 : reorderTimeoutMs;
    }

    public boolean historize() {
        return historizeByDefault == null || historizeByDefault;
    }
}
