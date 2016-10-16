package io.github.azagniotov.metrics.reporter.cloudwatch;

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.model.*;
import com.codahale.metrics.*;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jvm.*;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Reports metrics to <a href="http://aws.amazon.com/cloudwatch/">Amazon's CloudWatch</a> periodically.
 * <p>
 * Use {@link CloudWatchReporter.Builder} to construct instances of this class. The {@link CloudWatchReporter.Builder}
 * allows to configure what aggregated metrics will be reported as a single {@link MetricDatum} to CloudWatch.
 * <p>
 * There are a bunch of {@code with*} methods that provide a sufficient fine-grained control over what metrics
 * should be reported
 */
public class CloudWatchReporter extends ScheduledReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudWatchReporter.class);

    private static final String DIMENSION_NAME_TYPE = "Type";

    /**
     * Amazon CloudWatch rejects values that are either too small or too large.
     * Values must be in the range of 8.515920e-109 to 1.174271e+108 (Base 10) or 2e-360 to 2e360 (Base 2).
     * <p>
     * In addition, special values (e.g., NaN, +Infinity, -Infinity) are not supported.
     */
    private static final double SMALLEST_SENDABLE_VALUE = 8.515920e-109;
    private static final double LARGEST_SENDABLE_VALUE = 1.174271e+108;

    /**
     * Each CloudWatch API request may contain at maximum 20 datums
     */
    private static final int MAXIMUM_DATUMS_PER_REQUEST = 20;

    /**
     * We only submit the difference in counters since the last submission. This way we don't have to reset
     * the counters within this application.
     */
    private final Map<Counting, Long> lastPolledCounts;

    private final Builder builder;
    private final String namespace;
    private final AmazonCloudWatchAsync cloudWatchAsyncClient;
    private final StandardUnit rateUnit;
    private final StandardUnit durationUnit;

    private CloudWatchReporter(final Builder builder) {
        super(builder.metricRegistry, "coda-hale-metrics-cloud-watch-reporter", builder.metricFilter, builder.rateUnit, builder.durationUnit);
        this.builder = builder;
        this.namespace = builder.namespace;
        this.cloudWatchAsyncClient = builder.cloudWatchAsyncClient;
        this.lastPolledCounts = new ConcurrentHashMap<>();
        this.rateUnit = builder.cwRateUnit;
        this.durationUnit = builder.cwDurationUnit;
    }

    @Override
    public void report(final SortedMap<String, Gauge> gauges,
                       final SortedMap<String, Counter> counters,
                       final SortedMap<String, Histogram> histograms,
                       final SortedMap<String, Meter> meters,
                       final SortedMap<String, Timer> timers) {

        if (builder.withDryRun) {
            LOGGER.warn("** Reporter is running in 'DRY RUN' mode **");
        }

        try {
            final List<MetricDatum> metricData = new ArrayList<>(
                    gauges.size() + counters.size() + 10 * histograms.size() + 10 * timers.size());

            for (final Map.Entry<String, Gauge> gaugeEntry : gauges.entrySet()) {
                processGauge(gaugeEntry.getKey(), gaugeEntry.getValue(), metricData);
            }

            for (final Map.Entry<String, Counter> counterEntry : counters.entrySet()) {
                processCounter(counterEntry.getKey(), counterEntry.getValue(), metricData);
            }

            for (final Map.Entry<String, Histogram> histogramEntry : histograms.entrySet()) {
                processCounter(histogramEntry.getKey(), histogramEntry.getValue(), metricData);
                processHistogram(histogramEntry.getKey(), histogramEntry.getValue(), metricData);
            }

            for (final Map.Entry<String, Meter> meterEntry : meters.entrySet()) {
                processCounter(meterEntry.getKey(), meterEntry.getValue(), metricData);
                processMeter(meterEntry.getKey(), meterEntry.getValue(), metricData);
            }

            for (final Map.Entry<String, Timer> timerEntry : timers.entrySet()) {
                processCounter(timerEntry.getKey(), timerEntry.getValue(), metricData);
                processMeter(timerEntry.getKey(), timerEntry.getValue(), metricData);
                processHistogram(timerEntry.getKey(), timerEntry.getValue(), metricData);
            }

            final Iterable<List<MetricDatum>> metricDataPartitions = Iterables.partition(metricData, MAXIMUM_DATUMS_PER_REQUEST);
            final List<Future<PutMetricDataResult>> cloudWatchFutures = Lists.newArrayListWithExpectedSize(metricData.size());

            for (final List<MetricDatum> partition : metricDataPartitions) {
                final PutMetricDataRequest putMetricDataRequest = new PutMetricDataRequest()
                        .withNamespace(namespace)
                        .withMetricData(partition);

                if (builder.withDryRun) {
                    LOGGER.debug("Dry run - constructed PutMetricDataRequest: {}", putMetricDataRequest);
                } else {
                    cloudWatchFutures.add(cloudWatchAsyncClient.putMetricDataAsync(putMetricDataRequest));
                }
            }

            for (final Future<PutMetricDataResult> cloudWatchFuture : cloudWatchFutures) {
                try {
                    cloudWatchFuture.get();
                } catch (final Exception e) {
                    LOGGER.error("Error reporting metrics to CloudWatch. The data in this CloudWatch API request " +
                            "may have been discarded, did not make it to CloudWatch.", e);
                }
            }

            LOGGER.debug("Sent {} metric data to CloudWatch. namespace: {}", metricData.size(), namespace);

        } catch (final RuntimeException e) {
            LOGGER.error("Error marshalling CloudWatch metrics.", e);
        }
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } catch (final Exception e) {
            LOGGER.error("Error when stopping the reporter.", e);
        } finally {
            if (!builder.withDryRun) {
                try {
                    cloudWatchAsyncClient.shutdown();
                } catch (final Exception e) {
                    LOGGER.debug("Error shutting down AmazonCloudWatchAsync", cloudWatchAsyncClient, e);
                }
            }
        }
    }

    private void processGauge(final String metricName, final Gauge gauge, final List<MetricDatum> metricData) {
        if (gauge.getValue() instanceof Number) {
            final Number number = (Number) gauge.getValue();
            stageMetricDatum(true, metricName, number.doubleValue(), StandardUnit.None, "gauge", metricData);
        }
    }

    private void processCounter(final String metricName, final Counting counter, final List<MetricDatum> metricData) {
        long currentCount = counter.getCount();
        Long lastCount = lastPolledCounts.get(counter);
        lastPolledCounts.put(counter, currentCount);

        if (lastCount == null) {
            lastCount = 0L;
        }

        // Only submit metrics that have changed - let's save some money!
        final long delta = currentCount - lastCount;
        if (delta != 0L) {
            stageMetricDatum(true, metricName, delta, StandardUnit.Count, "count", metricData);
        }
    }

    private void processMeter(final String metricName, final Metered meter, final List<MetricDatum> metricData) {
        final String dimensionValue = String.format("-rate [in-%ss]", getRateUnit());
        stageMetricDatum(builder.withOneMinuteMeanRate, metricName, convertRate(meter.getOneMinuteRate()), rateUnit, "1-min-mean" + dimensionValue, metricData);
        stageMetricDatum(builder.withFiveMinuteMeanRate, metricName, convertRate(meter.getFiveMinuteRate()), rateUnit, "5-min-mean" + dimensionValue, metricData);
        stageMetricDatum(builder.withFifteenMinuteMeanRate, metricName, convertRate(meter.getFifteenMinuteRate()), rateUnit, "15-min-mean" + dimensionValue, metricData);
        stageMetricDatum(builder.withMeanRate, metricName, convertRate(meter.getMeanRate()), rateUnit, "mean" + dimensionValue, metricData);
    }

    private void processHistogram(final String metricName, final Sampling sampling, final List<MetricDatum> metricData) {
        final Snapshot snapshot = sampling.getSnapshot();
        // Only submit metrics that show some data - let's save some money!
        if (snapshot.size() > 0) {
            for (final Percentile percentile : builder.percentiles) {
                final double convertedDuration = convertDuration(snapshot.getValue(percentile.getQuantile()));
                stageMetricDatum(true, metricName, convertedDuration, durationUnit, percentile.getDesc(), metricData);
            }

            stageMetricDatum(builder.withStatisticSet, metricName, snapshot, durationUnit, "snapshot-summary", metricData);
        }
    }

    private void stageMetricDatum(final boolean metricConfigured,
                                  final String metricName,
                                  final double metricValue,
                                  final StandardUnit standardUnit,
                                  final String dimensionValue,
                                  final List<MetricDatum> metricData) {
        if (metricConfigured) {
            final Collection<Dimension> dimensions = new ArrayList<>();
            dimensions.addAll(builder.globalDimensions);
            dimensions.add(new Dimension().withName(DIMENSION_NAME_TYPE).withValue(dimensionValue));

            metricData.add(new MetricDatum()
                    .withTimestamp(new Date(builder.clock.getTime()))
                    .withValue(cleanMetricValue(metricValue))
                    .withMetricName(metricName)
                    .withDimensions(dimensions)
                    .withUnit(standardUnit));
        }
    }

    private void stageMetricDatum(final boolean metricConfigured,
                                  final String metricName,
                                  final Snapshot snapshot,
                                  final StandardUnit standardUnit,
                                  final String dimensionValue,
                                  final List<MetricDatum> metricData) {
        if (metricConfigured) {
            double scaledSum = convertDuration(LongStream.of(snapshot.getValues()).sum());
            final StatisticSet statisticSet = new StatisticSet()
                    .withSum(scaledSum)
                    .withSampleCount((double) snapshot.size())
                    .withMinimum(convertDuration(snapshot.getMin()))
                    .withMaximum(convertDuration(snapshot.getMax()));

            final List<Dimension> dimensions = new ArrayList<>();
            dimensions.addAll(builder.globalDimensions);
            dimensions.add(new Dimension().withName(DIMENSION_NAME_TYPE).withValue(dimensionValue));

            metricData.add(new MetricDatum()
                    .withTimestamp(new Date(builder.clock.getTime()))
                    .withMetricName(metricName)
                    .withDimensions(dimensions)
                    .withStatisticValues(statisticSet)
                    .withUnit(standardUnit));
        }
    }

    private double cleanMetricValue(final double metricValue) {
        double absoluteValue = Math.abs(metricValue);
        if (absoluteValue < SMALLEST_SENDABLE_VALUE) {
            // Allow 0 through untouched, everything else gets rounded to SMALLEST_SENDABLE_VALUE
            if (absoluteValue > 0) {
                if (metricValue < 0) {
                    return -SMALLEST_SENDABLE_VALUE;
                } else {
                    return SMALLEST_SENDABLE_VALUE;
                }
            }
        } else if (absoluteValue > LARGEST_SENDABLE_VALUE) {
            if (metricValue < 0) {
                return -LARGEST_SENDABLE_VALUE;
            } else {
                return LARGEST_SENDABLE_VALUE;
            }
        }
        return metricValue;
    }

    /**
     * Creates a new {@link Builder} that sends values from the given {@link MetricRegistry} to the given namespace
     * using the given CloudWatch client.
     *
     * @param metricRegistry {@link MetricRegistry} instance
     * @param client         {@link AmazonCloudWatchAsync} instance
     * @param namespace      the namespace. Must be non-null and not empty.
     * @return {@link Builder} instance
     */
    public static Builder forRegistry(final MetricRegistry metricRegistry, final AmazonCloudWatchAsync client, final String namespace) {
        return new Builder(metricRegistry, client, namespace);
    }

    public enum Percentile {
        P50(0.50, "50%"),
        P75(0.75, "75%"),
        P95(0.95, "95%"),
        P98(0.98, "98%"),
        P99(0.99, "99%"),
        P995(0.995, "99.5%"),
        P999(0.999, "99.9%");

        private final double quantile;
        private final String desc;

        Percentile(final double quantile, final String desc) {
            this.quantile = quantile;
            this.desc = desc;
        }

        public double getQuantile() {
            return quantile;
        }

        public String getDesc() {
            return desc;
        }
    }

    public static class Builder {

        private final String namespace;
        private final AmazonCloudWatchAsync cloudWatchAsyncClient;
        private final MetricRegistry metricRegistry;

        private Percentile[] percentiles;
        private boolean withOneMinuteMeanRate;
        private boolean withFiveMinuteMeanRate;
        private boolean withFifteenMinuteMeanRate;
        private boolean withMeanRate;
        private boolean withDryRun;
        private boolean withStatisticSet;
        private boolean withJvmMetrics;
        private MetricFilter metricFilter;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private StandardUnit cwRateUnit;
        private StandardUnit cwDurationUnit;
        private Collection<Dimension> globalDimensions;
        private final Clock clock;

        private Builder(final MetricRegistry metricRegistry, final AmazonCloudWatchAsync cloudWatchAsyncClient, final String namespace) {
            this.metricRegistry = metricRegistry;
            this.cloudWatchAsyncClient = cloudWatchAsyncClient;
            this.namespace = namespace;
            this.percentiles = new Percentile[]{Percentile.P75, Percentile.P95, Percentile.P999};
            this.metricFilter = MetricFilter.ALL;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.globalDimensions = new ArrayList<>();
            this.cwRateUnit = toStandardUnit(rateUnit);
            this.cwDurationUnit = toStandardUnit(durationUnit);
            this.clock = Clock.defaultClock();
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(final TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(final TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param metricFilter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(final MetricFilter metricFilter) {
            this.metricFilter = metricFilter;
            return this;
        }

        /**
         * If the one minute rate should be sent for {@link Meter} and {@link Timer}. Disabled by default.
         * <p>
         * The rate values are converted before reporting based on the rate unit set
         *
         * @return {@code this}
         * @see ScheduledReporter#convertRate(double)
         * @see Meter#getOneMinuteRate()
         * @see Timer#getOneMinuteRate()
         */
        public Builder withOneMinuteMeanRate() {
            withOneMinuteMeanRate = true;
            return this;
        }

        /**
         * If the five minute rate should be sent for {@link Meter} and {@link Timer}. Disabled by default.
         * <p>
         * The rate values are converted before reporting based on the rate unit set
         *
         * @return {@code this}
         * @see ScheduledReporter#convertRate(double)
         * @see Meter#getFiveMinuteRate()
         * @see Timer#getFiveMinuteRate()
         */
        public Builder withFiveMinuteMeanRate() {
            withFiveMinuteMeanRate = true;
            return this;
        }

        /**
         * If the fifteen minute rate should be sent for {@link Meter} and {@link Timer}. Disabled by default.
         * <p>
         * The rate values are converted before reporting based on the rate unit set
         *
         * @return {@code this}
         * @see ScheduledReporter#convertRate(double)
         * @see Meter#getFifteenMinuteRate()
         * @see Timer#getFifteenMinuteRate()
         */
        public Builder withFifteenMinuteMeanRate() {
            withFifteenMinuteMeanRate = true;
            return this;
        }

        /**
         * If the mean rate should be sent for {@link Meter} and {@link Timer}. Disabled by default.
         * <p>
         * The rate values are converted before reporting based on the rate unit set
         *
         * @return {@code this}
         * @see ScheduledReporter#convertRate(double)
         * @see Meter#getMeanRate()
         * @see Timer#getMeanRate()
         */
        public Builder withMeanRate() {
            withMeanRate = true;
            return this;
        }

        /**
         * If lifetime {@link Snapshot} summary of {@link Histogram} and {@link Timer} should be translated
         * to {@link StatisticSet} in the most direct way possible and reported. Disabled by default.
         * <p>
         * The {@link Snapshot} duration values are converted before reporting based on the duration unit set
         *
         * @return {@code this}
         * @see ScheduledReporter#convertDuration(double)
         */
        public Builder withStatisticSet() {
            withStatisticSet = true;
            return this;
        }

        /**
         * If JVM statistic should be reported. Supported metrics include:
         * <p>
         * - Run count and elapsed times for all supported garbage collectors
         * - Memory usage for all memory pools, including off-heap memory
         * - Breakdown of thread states, including deadlocks
         * - File descriptor usage
         * - Buffer pool sizes and utilization (Java 7 only)
         * <p>
         * Disabled by default.
         *
         * @return {@code this}
         */
        public Builder withJvmMetrics() {
            withJvmMetrics = true;
            return this;
        }

        /**
         * Does not actually POST to CloudWatch, logs the {@link PutMetricDataRequest putMetricDataRequest} instead.
         * Disabled by default.
         *
         * @return {@code this}
         */
        public Builder withDryRun() {
            withDryRun = true;
            return this;
        }

        /**
         * The {@link Histogram} and {@link Timer} percentiles to send. If <code>0.5</code> is included, it'll be
         * reported as <code>median</code>.This defaults to <code>0.75, 0.95 and 0.999</code>.
         *
         * @param percentiles the percentiles to send. Replaces the default percentiles.
         * @return {@code this}
         */
        public Builder withPercentiles(final Percentile... percentiles) {
            if (percentiles.length > 0) {
                this.percentiles = percentiles;
            }
            return this;
        }

        /**
         * Global {@link Collection} of {@link Dimension} to send with each {@link MetricDatum}.
         * Defaults to {@code empty} {@link Collection}.
         *
         * @param dimensions the {@link Map} of name-to-value {@link String} pairs. Each pair will be converted to
         *                   an instance of {@link Dimension}
         * @return {@code this}
         */
        public Builder withGlobalDimensions(final Map<String, String> dimensions) {
            this.globalDimensions.addAll(
                    dimensions.entrySet().stream().map(
                            entry -> new Dimension().withName(entry.getKey()).withValue(entry.getValue()))
                            .collect(Collectors.toList()));
            return this;
        }

        public CloudWatchReporter build() {

            if (withJvmMetrics) {
                metricRegistry.register("jvm.uptime", (Gauge<Long>) () -> ManagementFactory.getRuntimeMXBean().getUptime());
                metricRegistry.register("jvm.current_time", (Gauge<Long>) clock::getTime);
                metricRegistry.register("jvm.classes", new ClassLoadingGaugeSet());
                metricRegistry.register("jvm.fd_usage", new FileDescriptorRatioGauge());
                metricRegistry.register("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
                metricRegistry.register("jvm.gc", new GarbageCollectorMetricSet());
                metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet());
                metricRegistry.register("jvm.thread-states", new ThreadStatesGaugeSet());
            }

            cwRateUnit = toStandardUnit(rateUnit);
            cwDurationUnit = toStandardUnit(durationUnit);

            return new CloudWatchReporter(this);
        }

        private StandardUnit toStandardUnit(final TimeUnit timeUnit) {
            switch (timeUnit) {
                case SECONDS:
                    return StandardUnit.Seconds;
                case MILLISECONDS:
                    return StandardUnit.Milliseconds;
                case MICROSECONDS:
                    return StandardUnit.Microseconds;
                default:
                    throw new IllegalArgumentException("Unsupported TimeUnit: " + timeUnit);
            }
        }
    }
}