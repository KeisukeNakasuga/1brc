package dev.morling.onebrc;

import static java.util.stream.Collectors.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collector;

public class Solution2 {

    private static final String FILE = "./measurements.txt";

    private static record Measurement(String station, int value) {
        private Measurement(String line) {
            this(line.substring(0, line.indexOf(";")), asIntTemperature(line));
        }

        private static int asIntTemperature(String line) {
            // 気温の開始位置
            int pos = line.indexOf(';') + 1;
            // 気温がマイナスかどうかのフラグ
            // マイナスであればtrue
            boolean neg = line.charAt(pos) == '-';
            if (neg) pos++;

            int v = 0;
            for (; pos < line.length(); pos++) {
                char c = line.charAt(pos);
                if (c != '.') {
                   v = v * 10 + (c - '0');
                }
            }
            return neg ? -v : v;
        }
    }

    private static record ResultRow(double min, double mean, double max) {

        public String toString() {
            return round(min) + "/" + round(mean) + "/" + round(max);
        }

        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    };

    private static class MeasurementAggregator {
        private int min = Integer.MAX_VALUE;
        private int max = Integer.MIN_VALUE;
        private long sum;
        private long count;
    }

    public static void main(String[] args) throws IOException {
        Collector<Measurement, MeasurementAggregator, ResultRow> collector = Collector.of(
                MeasurementAggregator::new,
                (a, m) -> {
                    a.min = Math.min(a.min, m.value);
                    a.max = Math.max(a.max, m.value);
                    a.sum += m.value;
                    a.count++;
                },
                (agg1, agg2) -> {
                    var res = new MeasurementAggregator();
                    res.min = Math.min(agg1.min, agg2.min);
                    res.max = Math.max(agg1.max, agg2.max);
                    res.sum = agg1.sum + agg2.sum;
                    res.count = agg1.count + agg2.count;

                    return res;
                },
                agg -> {
                    return new ResultRow(
                        agg.min / 10.0,  // int → double
                        (agg.sum / 10.0) / agg.count,  // long → double
                        agg.max / 10.0);  // int → double
                });

        Map<String, ResultRow> measurements = new TreeMap<>(Files.lines(Paths.get(FILE))
                .map(l -> new Measurement(l))
                .collect(groupingBy(m -> m.station(), collector)));

        System.out.println(measurements);
    }
}
