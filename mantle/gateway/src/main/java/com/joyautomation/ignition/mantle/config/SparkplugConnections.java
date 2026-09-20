package com.joyautomation.ignition.mantle.config;

import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointCollection;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.joyautomation.ignition.mantle.MantleGatewayHook;

/** The connection types this module offers, and the resource type they are stored under. */
public final class SparkplugConnections implements ExtensionPointCollection<ExtensionPoint<?>> {
    public static final ResourceType RESOURCE_TYPE =
        new ResourceType(MantleGatewayHook.MODULE_ID, "connection");

    public static final SparkplugConnections INSTANCE = new SparkplugConnections();

    private static final List<ExtensionPoint<?>> TYPES = List.of(new MqttConnectionExtensionPoint());

    private SparkplugConnections() {
    }

    public static ResourceTypeMeta<ExtensionPointConfig<SparkplugConnectionProfile, ?>> meta() {
        return ResourceTypeMeta.newExtensionPointBuilder(SparkplugConnectionProfile.class)
            .resourceType(RESOURCE_TYPE)
            .extensionPointCollection(INSTANCE)
            .categoryName("Sparkplug Connections")
            .description("Sparkplug B host connections. Every metric that arrives becomes a tag.")
            .withDefaultProfile(SparkplugConnectionProfile.DEFAULT)
            .build();
    }

    @Override
    public boolean hasType(String typeId) {
        return getType(typeId).isPresent();
    }

    @Override
    public Optional<ExtensionPoint<?>> getType(String typeId) {
        return TYPES.stream().filter(t -> t.typeId().equals(typeId)).findFirst();
    }

    @Override
    public List<ExtensionPoint<?>> getTypes() {
        return TYPES;
    }
}
