package dev.morling.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

public class Solution4 {

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

    private static class StationTable {
        private static final int SIZE = 1 << 16;  // 65536 stationは最大10000なので大きさは十分
        private final String[] keys = new String[SIZE];
        private final MeasurementAggregator[] vals = new MeasurementAggregator[SIZE];

        MeasurementAggregator get(String line, int len, int hash) {
            int idx = hash & (SIZE - 1);
            while (true) {
                String key = keys[idx];
                if (key == null) {
                    keys[idx] = line.substring(0, len);
                    vals[idx] = new MeasurementAggregator();
                    return vals[idx];
                }

                if (key.length() == len && line.startsWith(key)) {
                    return vals[idx];
                }

                idx = (idx + 1) & (SIZE - 1);  // アクセスしたインデックスが埋まっていたら隣を使う
            }
        }
    }

    public static void main(String[] args) throws IOException {
        StationTable table = new StationTable();

        try (var lines = Files.lines(Paths.get(FILE))) {
            lines.forEach(line -> {
                int hash = 0;
                int i = 0;
                char c;
                while ((c = line.charAt(i)) != ';') {
                    hash = 31 * hash + c;
                    i++;
                }
                MeasurementAggregator agg = table.get(line, i, hash);
                int val = asIntTemperature(line, i+1);

                agg.min = Math.min(agg.min, val);
                agg.max = Math.max(agg.max, val);
                agg.sum += val;
                agg.count++;
            });
        }

        Map<String, ResultRow> res = new TreeMap<>();
        for (int idx = 0; idx < StationTable.SIZE; idx++) {
            if (table.keys[idx] == null) continue;
            var a = table.vals[idx];
            res.put(
                table.keys[idx],
                new ResultRow(
                    a.min / 10.0,
                    (a.sum / 10.0) / a.count,
                    a.max / 10.0
                  ));
        }


        System.out.println(res);
    }
}
