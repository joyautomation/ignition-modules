package com.joyautomation.ignition.mantle.config;

import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointCollection;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.actions.ResourceActionSet;
import com.joyautomation.ignition.mantle.MantleGatewayHook;
import com.joyautomation.ignition.mantle.status.ConnectionHealth;

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
            // Without this the type defaults to ResourceActionSet.EMPTY and the gateway's configuration REST
            // API does not serve it at all — no list, no create, no edit, for any UI or script.
            .withActionSet(ResourceActionSet.DEFAULT)
            // ...and this is what puts the type on the gateway's configuration REST API. Without a route
            // delegate the type has no routes at all, whatever else is configured, so nothing — no UI, no
            // script, no other module — can list or create a connection.
            .buildRouteDelegate(routes -> routes
                .profileSchema(SparkplugConnectionProfile.class)
                .openApiGroupName("Mantle")
                .openApiTagName("Sparkplug Connections"))
            // Puts each connection's health in the resource listing, under "status", which is what the
            // configuration page's Status column reads. The %s is filled in with the connection's own name;
            // ConnectionHealth registers the matching check when the connection starts.
            .buildStatusDelegate(status -> status.instanceHealthCheck("status", ConnectionHealth.NAME_TEMPLATE))
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
