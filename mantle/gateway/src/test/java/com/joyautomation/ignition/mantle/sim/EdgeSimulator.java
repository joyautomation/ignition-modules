package com.joyautomation.ignition.mantle.sim;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.PropertyDataType;
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder;
import org.eclipse.tahu.message.model.PropertyValue;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

/**
 * A small Sparkplug B edge node for poking at the module by hand:
 * <pre>./gradlew :gateway:simulate --args="tcp://localhost:1883 Plant Edge1"</pre>
 * It births one device with aliased metrics, then publishes alias-only DDATA with datatypes stripped once a second,
 * which is the hardest shape for a host to decode. It honours rebirth requests and writes to Tank/Setpoint.
 */
public class EdgeSimulator {
    private static final SparkplugBPayloadEncoder ENCODER = new SparkplugBPayloadEncoder();

    private final Mqtt3BlockingClient client;
    private final String group;
    private final String node;
    private final String device = "PLC1";
    private final AtomicLong seq = new AtomicLong();
    private final long bdSeq = System.currentTimeMillis() % 256;

    private volatile float setpoint = 12.0f;
    private volatile int speed = 1200;
    private int counter;

    public static void main(String[] args) throws Exception {
        String broker = args.length > 0 ? args[0] : "tcp://localhost:1883";
        String group = args.length > 1 ? args[1] : "Plant";
        String node = args.length > 2 ? args[2] : "Edge1";
        long seconds = args.length > 3 ? Long.parseLong(args[3]) : Long.MAX_VALUE;
        new EdgeSimulator(broker, group, node).run(seconds);
    }

    EdgeSimulator(String broker, String group, String node) {
        java.net.URI uri = java.net.URI.create(broker);
        this.group = group;
        this.node = node;
        this.client = MqttClient.builder().useMqttVersion3().identifier("sim-" + group + "-" + node)
            .serverHost(uri.getHost()).serverPort(uri.getPort() > 0 ? uri.getPort() : 1883).buildBlocking();
    }

    void run(long seconds) throws Exception {
        client.connectWith().cleanSession(true).keepAlive(30)
            .willPublish().topic(topic("NDEATH", false)).qos(MqttQos.AT_LEAST_ONCE)
            .payload(encode(payload(null, bdSeqMetric()))).applyWillPublish().send();
        client.toAsync().subscribeWith().topicFilter("spBv1.0/" + group + "/NCMD/" + node)
            .callback(this::onCommand).send();
        client.toAsync().subscribeWith().topicFilter("spBv1.0/" + group + "/DCMD/" + node + "/#")
            .callback(this::onCommand).send();
        birth();
        System.out.printf("simulating %s/%s/%s%n", group, node, device);

        long end = seconds == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1000);
            double t = System.currentTimeMillis() / 1000.0;
            float level = (float) (setpoint + 2 * Math.sin(t / 10));
            publish("DDATA", true, true,
                aliased(1, MetricDataType.Float, level),
                aliased(3, MetricDataType.Boolean, level < setpoint),
                aliased(4, MetricDataType.Int32, ++counter));
        }
        // an orderly exit still owes the host a death certificate
        client.publishWith().topic(topic("NDEATH", false)).payload(encode(payload(null, bdSeqMetric()))).send();
        client.disconnect();
    }

    private void birth() throws Exception {
        seq.set(0);
        Metric scratch = new MetricBuilder("Scratch", MetricDataType.Int32, 1).isTransient(true).createMetric();
        publish("NBIRTH", false, false,
            bdSeqMetric(),
            new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, false).createMetric(),
            new MetricBuilder("Node Info/Version", MetricDataType.String, "sim-1.0").createMetric(),
            scratch);

        PropertySetBuilder feet = new PropertySetBuilder()
            .addProperty("engUnit", new PropertyValue<>(PropertyDataType.String, "ft"))
            .addProperty("engLow", new PropertyValue<>(PropertyDataType.Double, 0.0))
            .addProperty("engHigh", new PropertyValue<>(PropertyDataType.Double, 30.0))
            .addProperty("documentation", new PropertyValue<>(PropertyDataType.String, "Clearwell level"));
        Template motor = new TemplateBuilder().templateRef("Motor").definition(false)
            .addMetric(new MetricBuilder("Speed", MetricDataType.Int32, speed).createMetric())
            .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, true).createMetric()).createTemplate();
        publish("DBIRTH", true, false,
            new MetricBuilder("Tank/Level", MetricDataType.Float, setpoint).alias(1L)
                .properties(feet.createPropertySet()).createMetric(),
            new MetricBuilder("Tank/Setpoint", MetricDataType.Float, setpoint).alias(2L).createMetric(),
            new MetricBuilder("Pump/Running", MetricDataType.Boolean, false).alias(3L).createMetric(),
            new MetricBuilder("Counter", MetricDataType.Int32, counter).alias(4L).createMetric(),
            new MetricBuilder("Motor1", MetricDataType.Template, motor).createMetric());
    }

    private void onCommand(Mqtt3Publish publish) {
        String topic = publish.getTopic().toString();
        try {
            SparkplugBPayload cmd = new SparkplugBPayloadDecoder().buildFromByteArray(publish.getPayloadAsBytes(), null);
            for (Metric m : cmd.getMetrics()) {
                System.out.printf("command %s %s = %s%n", topic, m.getName(), describe(m));
                if ("Node Control/Rebirth".equals(m.getName()) && Boolean.TRUE.equals(m.getValue())) {
                    birth();
                } else if ("Tank/Setpoint".equals(m.getName())) {
                    setpoint = ((Number) m.getValue()).floatValue();
                    publish("DDATA", true, true, aliased(2, MetricDataType.Float, setpoint));
                } else if ("Motor1".equals(m.getName()) && m.getValue() instanceof Template partial) {
                    for (Metric member : partial.getMetrics()) {
                        if ("Speed".equals(member.getName())) {
                            speed = ((Number) member.getValue()).intValue();
                        }
                    }
                    Template update = new TemplateBuilder().templateRef("Motor").definition(false)
                        .addMetric(new MetricBuilder("Speed", MetricDataType.Int32, speed).createMetric())
                        .createTemplate();
                    publish("DDATA", true, false,
                        new MetricBuilder("Motor1", MetricDataType.Template, update).createMetric());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String describe(Metric m) {
        if (m.getValue() instanceof Template t) {
            return "Template" + t.getMetrics().stream().map(x -> x.getName() + "=" + x.getValue()).toList();
        }
        return m.getDataType() + ":" + m.getValue();
    }

    private Metric bdSeqMetric() throws Exception {
        return new MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric();
    }

    private static Metric aliased(long alias, MetricDataType type, Object value) throws Exception {
        return new MetricBuilder(alias, type, value).createMetric();
    }

    private synchronized void publish(String kind, boolean forDevice, boolean strip, Metric... metrics)
        throws Exception {
        SparkplugBPayload payload = payload(seq.getAndUpdate(s -> (s + 1) % 256), metrics);
        client.publishWith().topic(topic(kind, forDevice)).qos(MqttQos.AT_MOST_ONCE)
            .payload(ENCODER.getBytes(payload, strip)).send();
    }

    private static SparkplugBPayload payload(Long seq, Metric... metrics) {
        SparkplugBPayloadBuilder builder = seq == null ? new SparkplugBPayloadBuilder() : new SparkplugBPayloadBuilder(seq);
        builder.setTimestamp(new Date());
        builder.addMetrics(List.of(metrics));
        return builder.createPayload();
    }

    private static byte[] encode(SparkplugBPayload payload) throws Exception {
        return ENCODER.getBytes(payload, false);
    }

    private String topic(String kind, boolean forDevice) {
        return "spBv1.0/" + group + "/" + kind + "/" + node + (forDevice ? "/" + device : "");
    }
}
