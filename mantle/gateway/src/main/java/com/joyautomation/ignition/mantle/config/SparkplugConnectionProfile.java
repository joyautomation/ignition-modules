package com.joyautomation.ignition.mantle.config;

import com.inductiveautomation.ignition.gateway.config.ExtensionPointProfileConfig;

/**
 * The part of a connection that is the same whatever kind it is. Ignition's configuration system models a
 * user-addable thing as an extension point: a profile saying which type it is, plus that type's own settings.
 * Being one means the gateway gives us a configuration page, a REST API and the Designer's resource browser for
 * free, instead of connections only being creatable by editing files on disk.
 *
 * <p>There is one type today, MQTT. The shape allows for others — a connection over the Gateway Network, say —
 * without the stored form of existing connections changing.
 */
public record SparkplugConnectionProfile(String type) implements ExtensionPointProfileConfig {
    public static final SparkplugConnectionProfile DEFAULT =
        new SparkplugConnectionProfile(MqttConnectionExtensionPoint.TYPE_ID);
}
