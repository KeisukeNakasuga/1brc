package dev.morling.onebrc;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public class Solution5 {

    private static final String FILE = "./measurements.txt";

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
        private final byte[][] keys = new byte[SIZE][];
        private final MeasurementAggregator[] vals = new MeasurementAggregator[SIZE];

        MeasurementAggregator get(byte[] buf, int start, int len, int hash) {
            int idx = hash & (SIZE - 1);
            while (true) {
                byte[] key = keys[idx];
                if (key == null) {
                    keys[idx] = Arrays.copyOfRange(buf, start, start+len);
                    vals[idx] = new MeasurementAggregator();
                    return vals[idx];
                }
                if (Arrays.equals(key, 0, key.length, buf, start, start+len)) {
                    return vals[idx];
                }
                idx = (idx + 1) & (SIZE - 1);  // アクセスしたインデックスが埋まっていたら隣を使う
            }
        }
    }

    public static void main(String[] args) throws IOException {
        StationTable table = new StationTable();
        byte[] buf = new byte[1 << 20];  // 1MB
        int len = 0;

        try (var in = new FileInputStream(FILE)) {
            int n;
            // ①
            while ((n = in.read(buf, len, buf.length - len)) > 0) {
                len += n;

                // 改行の位置(= 改行以降を次回のループで使う)をメモする
                // endには改行を手がかりに「途中で切れたバイト」分読み戻しておく
                int end = len - 1;
                while (buf[end] != '\n') end--;

                int pos = 0;
                while (pos <= end) {
                    int start = pos;
                    int hash = 0;
                    byte b;
                    while ((b = buf[pos]) != ';') {
                        hash = 31 * hash + b;
                        pos++;
                    }
                    MeasurementAggregator agg = table.get(buf, start, pos - start, hash);
                    pos++;

                    boolean neg = buf[pos] == '-';
                    if (neg) pos++;
                    int v = 0;
                    while ((b = buf[pos]) != '\n') {
                        if (b != '.') v = v * 10 + (b - '0');
                        pos++;
                    }
                    pos++;
                    int val = neg ? -v : v;

                    agg.min = Math.min(agg.min, val);
                    agg.max = Math.max(agg.max, val);
                    agg.sum += val;
                    agg.count++;
                }

                int rest = len - (end + 1);
                System.arraycopy(buf, end+1, buf, 0, rest);
                len = rest;
            }
        }

        Map<String, ResultRow> res = new TreeMap<>();
        for (int idx = 0; idx < StationTable.SIZE; idx++) {
            if (table.keys[idx] == null) continue;
            var a = table.vals[idx];
            res.put(
                new String(table.keys[idx], StandardCharsets.UTF_8),
                new ResultRow(a.min / 10.0, (a.sum / 10.0) / a.count, a.max / 10.0));
        }


        System.out.println(res);
    }
}
