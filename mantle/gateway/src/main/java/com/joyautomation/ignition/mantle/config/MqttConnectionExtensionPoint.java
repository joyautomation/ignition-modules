package com.joyautomation.ignition.mantle.config;

import java.util.Optional;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.AbstractExtensionPoint;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;

/**
 * A Sparkplug host connection over MQTT — the only kind today. The form the gateway renders is generated from
 * the annotations on {@link BrokerConnectionConfig}, so the fields, their labels, defaults and the password's
 * secret handling are all declared in one place.
 */
public class MqttConnectionExtensionPoint extends AbstractExtensionPoint<BrokerConnectionConfig> {
    public static final String TYPE_ID = "MQTT";

    /**
     * The two strings after the type id are bundle KEYS, not text: the gateway resolves them through
     * BundleUtil, and renders an unresolved one as {@code ¿Mantle.Connection.MQTT.name?} on the page. They
     * live in Mantle.properties, which MantleGatewayHook registers.
     */
    public MqttConnectionExtensionPoint() {
        super(TYPE_ID, "Mantle.Connection.MQTT.name", "Mantle.Connection.MQTT.desc");
    }

    @Override
    public ResourceType resourceType() {
        return SparkplugConnections.RESOURCE_TYPE;
    }

    @Override
    public Optional<BrokerConnectionConfig> defaultSettings() {
        return Optional.of(BrokerConnectionConfig.DEFAULT);
    }

    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(new ExtensionPointResourceForm(
            SparkplugConnections.RESOURCE_TYPE,
            "Sparkplug Connection",
            TYPE_ID,
            SchemaUtil.fromType(SparkplugConnectionProfile.class),
            SchemaUtil.fromType(BrokerConnectionConfig.class)));
    }

    @Override
    protected void validate(BrokerConnectionConfig settings, ValidationErrors.Builder errors) {
        super.validate(settings, errors);
        BrokerConnectionConfig.validate(settings, errors);
    }
}
