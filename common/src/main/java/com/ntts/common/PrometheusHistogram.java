package com.ntts.common;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;

final class PrometheusHistogram {
    private static final double[] DEFAULT_BUCKETS = {
            0.05D, 0.1D, 0.25D, 0.5D, 1D, 2.5D, 5D, 10D, 30D, 60D
    };

    private final double[] buckets;
    private final AtomicLong[] bucketCounts;
    private final AtomicLong count = new AtomicLong();
    private final DoubleAccumulator sum = new DoubleAccumulator(Double::sum, 0D);

    PrometheusHistogram() {
        this(DEFAULT_BUCKETS);
    }

    PrometheusHistogram(double[] buckets) {
        this.buckets = buckets.clone();
        this.bucketCounts = new AtomicLong[buckets.length];
        for (int index = 0; index < buckets.length; index++) {
            if (buckets[index] <= 0D || (index > 0 && buckets[index] <= buckets[index - 1])) {
                throw new IllegalArgumentException("Histogram buckets must be positive and strictly increasing");
            }
            bucketCounts[index] = new AtomicLong();
        }
    }

    void observe(double value) {
        if (Double.isNaN(value) || value < 0D) {
            return;
        }
        count.incrementAndGet();
        sum.accumulate(value);
        for (int index = 0; index < buckets.length; index++) {
            if (value <= buckets[index]) {
                bucketCounts[index].incrementAndGet();
            }
        }
    }

    void append(StringBuilder output, String metricName) {
        for (int index = 0; index < buckets.length; index++) {
            output.append(metricName)
                    .append("_bucket{le=\"")
                    .append(format(buckets[index]))
                    .append("\"} ")
                    .append(bucketCounts[index].get())
                    .append('\n');
        }
        output.append(metricName).append("_bucket{le=\"+Inf\"} ")
                .append(count.get()).append('\n');
        output.append(metricName).append("_count ")
                .append(count.get()).append('\n');
        output.append(metricName).append("_sum ")
                .append(format(sum.get())).append('\n');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
