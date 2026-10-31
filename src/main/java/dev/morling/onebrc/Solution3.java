package dev.morling.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;

public class Solution3 {

    private static final String FILE = "./measurements.txt";

    private static int asIntTemperature(String line, int pos) {
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
        Map<String, MeasurementAggregator> map = new HashMap<>();

        try (var lines = Files.lines(Paths.get(FILE))) {
            lines.forEach(line -> {
                int i = line.indexOf(';');
                String st = line.substring(0, i);
                int val = asIntTemperature(line, i+1);

                MeasurementAggregator agg = map.get(st);
                if (agg == null) {
                    agg = new MeasurementAggregator();
                    map.put(st, agg);
                }
                agg.min = Math.min(agg.min, val);
                agg.max = Math.max(agg.max, val);
                agg.sum += val;
                agg.count++;
            });
        }

        Map<String, ResultRow> res = new TreeMap<>();
        for (var e : map.entrySet()) {
            var a = e.getValue();
            res.put(
                e.getKey(),
                new ResultRow(
                    a.min / 10.0,
                    (a.sum / 10.0) / a.count,
                    a.max / 10.0
                  ));
        }


        System.out.println(res);
    }
}
