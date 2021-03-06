package com.zendesk.maxwell.metrics;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zendesk.maxwell.MaxwellConfig;
import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.producer.AbstractAsyncProducer;
import com.zendesk.maxwell.util.StoppableTask;
import org.apache.commons.lang.StringUtils;
import org.coursera.metrics.datadog.DatadogReporter;
import org.coursera.metrics.datadog.transport.HttpTransport;
import org.coursera.metrics.datadog.transport.Transport;
import org.coursera.metrics.datadog.transport.UdpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.coursera.metrics.datadog.DatadogReporter.Expansion.*;

public class MaxwellMetrics implements Metrics {
	private final MetricRegistry metricRegistry;
	private final HealthCheckRegistry healthCheckRegistry;

	public static final String reportingTypeSlf4j = "slf4j";
	public static final String reportingTypeJmx = "jmx";
	public static final String reportingTypeHttp = "http";
	public static final String reportingTypeDataDog = "datadog";

	static final Logger LOGGER = LoggerFactory.getLogger(MaxwellMetrics.class);
	private final MaxwellConfig config;

	private String metricsPrefix;

	public MaxwellMetrics(MaxwellConfig config) {
		healthCheckRegistry = new HealthCheckRegistry();
		metricRegistry = new MetricRegistry();
		this.config = config;
		setup(config);
	}

	private void setup(MaxwellConfig config) {
		if (config.metricsReportingType == null) {
			LOGGER.warn("Metrics will not be exposed: metricsReportingType not configured.");
			return;
		}

		metricsPrefix = config.metricsPrefix;

		if (config.metricsReportingType.contains(reportingTypeSlf4j)) {
			final Slf4jReporter reporter = Slf4jReporter.forRegistry(metricRegistry)
					.outputTo(LOGGER)
					.convertRatesTo(TimeUnit.SECONDS)
					.convertDurationsTo(TimeUnit.MILLISECONDS)
					.build();

			reporter.start(config.metricsSlf4jInterval, TimeUnit.SECONDS);
			LOGGER.info("Slf4j metrics reporter enabled");
		}

		if (config.metricsReportingType.contains(reportingTypeJmx)) {
			final JmxReporter jmxReporter = JmxReporter.forRegistry(metricRegistry)
					.convertRatesTo(TimeUnit.SECONDS)
					.convertDurationsTo(TimeUnit.MILLISECONDS)
					.build();
			jmxReporter.start();
			LOGGER.info("JMX metrics reporter enabled");

			if (System.getProperty("com.sun.management.jmxremote") == null) {
				LOGGER.warn("JMX remote is disabled");
			} else {
				String portString = System.getProperty("com.sun.management.jmxremote.port");
				if (portString != null) {
					LOGGER.info("JMX running on port " + Integer.parseInt(portString));
				}
			}
		}

		if (config.metricsReportingType.contains(reportingTypeDataDog)) {
			Transport transport;
			if (config.metricsDatadogType.contains("http")) {
				LOGGER.info("Enabling HTTP Datadog reporting");
				transport = new HttpTransport.Builder()
						.withApiKey(config.metricsDatadogAPIKey)
						.build();
			} else {
				LOGGER.info("Enabling UDP Datadog reporting with host " + config.metricsDatadogHost
						+ ", port " + config.metricsDatadogPort);
				transport = new UdpTransport.Builder()
						.withStatsdHost(config.metricsDatadogHost)
						.withPort(config.metricsDatadogPort)
						.build();
			}

			final DatadogReporter reporter = DatadogReporter.forRegistry(metricRegistry)
					.withTransport(transport)
					.withExpansions(EnumSet.of(COUNT, RATE_1_MINUTE, RATE_15_MINUTE, MEDIAN, P95, P99))
					.withTags(getDatadogTags(config.metricsDatadogTags))
					.build();

			reporter.start(config.metricsDatadogInterval, TimeUnit.SECONDS);
			LOGGER.info("Datadog reporting enabled");
		}
	}

	private static ArrayList<String> getDatadogTags(String datadogTags) {
		ArrayList<String> tags = new ArrayList<>();
		for (String tag : datadogTags.split(",")) {
			if (!StringUtils.isEmpty(tag)) {
				tags.add(tag);
			}
		}

		return tags;
	}

	public String metricName(String... names) {
		return MetricRegistry.name(metricsPrefix, names);
	}

	@Override
	public MetricRegistry getRegistry() {
		return metricRegistry;
	}

	public void startBackgroundTasks(MaxwellContext context) throws IOException {
		String metricsReportingType = config.metricsReportingType;
		if (metricsReportingType != null && metricsReportingType.contains(reportingTypeHttp)) {
			LOGGER.info("Metrics http server starting");
			new MaxwellHTTPServer(config.metricsHTTPPort, metricRegistry, healthCheckRegistry, context);
			healthCheckRegistry.register("MaxwellHealth", new MaxwellHealthCheck(context.getProducer()));
			LOGGER.info("Metrics http server started on port " + config.metricsHTTPPort);
		}
	}
}
