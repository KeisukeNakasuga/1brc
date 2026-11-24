package dev.morling.onebrc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;

public class Solution7 {

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
        private static final int SIZE = 1 << 16; // 65536 stationは最大10000なので大きさは十分
        private final byte[][] keys = new byte[SIZE][];
        private final MeasurementAggregator[] vals = new MeasurementAggregator[SIZE];

        MeasurementAggregator get(ByteBuffer buf, int start, int len, int hash) {
            int idx = hash & (SIZE - 1);
            while (true) {
                byte[] key = keys[idx];
                if (key == null) {
                    // 新しい地点のときだけ、マップ領域から地点名をbyte[]にコピーする
                    byte[] newKey = new byte[len];
                    buf.get(start, newKey);
                    keys[idx] = newKey;
                    vals[idx] = new MeasurementAggregator();
                    return vals[idx];
                }
                if (equalsKey(key, buf, start, len)) {
                    return vals[idx];
                }
                idx = (idx + 1) & (SIZE - 1); // アクセスしたインデックスが埋まっていたら隣を使う
            }
        }

        // マップ領域はbyte[]ではないのでArrays.equalsが使えない。1バイトずつ比較する
        private static boolean equalsKey(byte[] key, ByteBuffer buf, int start, int len) {
            if (key.length != len)
                return false;
            for (int i = 0; i < len; i++) {
                if (key[i] != buf.get(start + i))
                    return false;
            }
            return true;
        }
    }

    // ファイルをスレッドの個数(nThreads)分に分割し、各区切りを「次の改行の直後」までずらす(綺麗に分割する)
    // bounds[i] ～ bounds[i+1] がi番目のスレッドの担当範囲
    private static long[] splitFile(FileChannel ch, int nThreads) throws IOException {
        long size = ch.size();
        long[] bounds = new long[nThreads + 1];
        bounds[nThreads] = size;
        ByteBuffer bb = ByteBuffer.allocate(256); // 1行は最大でも 100+1+5+1 バイト程度
        for (int i = 1; i < nThreads; i++) {
            long pos = Math.max(size * i / nThreads, bounds[i - 1]);
            bb.clear();
            ch.read(bb, pos);
            int j = 0;
            while (j < bb.position() && bb.get(j) != '\n')
                j++;
            bounds[i] = Math.min(pos + j + 1, size);
        }
        return bounds;
    }

    // MappedByteBufferはint添字なので1回にマップできるのは2GB未満。余裕をもって1GBずつマップする
    private static final long MAP_WINDOW = 1L << 30;

    // start ～ endの範囲をmmapして集計する(解析ループはSolution6と同じ)
    // read()でbyte[]にコピーせず、OSのページキャッシュを直接メモリとして読む
    private static StationTable process(FileChannel ch, long start, long end) throws IOException {
        StationTable table = new StationTable();
        long filePos = start;

        while (filePos < end) {
            long size = Math.min(MAP_WINDOW, end - filePos);
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, filePos, size);

            // マップした範囲の最後の改行までを処理する。続きは次のマップの先頭から読み直すので
            // Solution6のような「途中で切れた行のコピー」は不要
            // (endは改行の直後なので、最後のマップは必ず改行で終わる)
            int last = (int) size - 1;
            while (buf.get(last) != '\n')
                last--;

            int pos = 0;
            while (pos <= last) {
                int lineStart = pos;
                int hash = 0;
                byte b;
                while ((b = buf.get(pos)) != ';') {
                    hash = 31 * hash + b;
                    pos++;
                }
                MeasurementAggregator agg = table.get(buf, lineStart, pos - lineStart, hash);
                pos++;

                boolean neg = buf.get(pos) == '-';
                if (neg)
                    pos++;
                int v = 0;
                while ((b = buf.get(pos)) != '\n') {
                    if (b != '.')
                        v = v * 10 + (b - '0');
                    pos++;
                }
                pos++;
                int val = neg ? -v : v;

                agg.min = Math.min(agg.min, val);
                agg.max = Math.max(agg.max, val);
                agg.sum += val;
                agg.count++;
            }

            filePos += last + 1;
        }
        return table;
    }

    /**
     * 使用するスレッド数を返却する
     * 指定がなければ論理コア数を取得して返却する
     */
    private static int getNThreads(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : Runtime.getRuntime().availableProcessors();
    }

    public static void main(String[] args) throws Exception {
        int nThreads = getNThreads(args);

        try (var ch = FileChannel.open(Paths.get(FILE), StandardOpenOption.READ)) {
            long[] bounds = splitFile(ch, nThreads);

            // 各スレッドが自分専用のStationTableに集計する(共有しないのでロック不要)
            StationTable[] tables = new StationTable[nThreads];
            Thread[] threads = new Thread[nThreads];
            for (int i = 0; i < nThreads; i++) {
                final int t = i;
                threads[i] = new Thread(() -> {
                    try {
                        tables[t] = process(ch, bounds[t], bounds[t + 1]);
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                threads[i].start();
            }
            for (Thread th : threads)
                th.join();

            // スレッドごとの結果を地点名ごとにまとめる
            Map<String, MeasurementAggregator> merged = new TreeMap<>();
            for (StationTable table : tables) {
                for (int idx = 0; idx < StationTable.SIZE; idx++) {
                    if (table.keys[idx] == null)
                        continue;
                    var a = table.vals[idx];
                    var m = merged.computeIfAbsent(
                            new String(table.keys[idx], StandardCharsets.UTF_8), k -> new MeasurementAggregator());
                    m.min = Math.min(m.min, a.min);
                    m.max = Math.max(m.max, a.max);
                    m.sum += a.sum;
                    m.count += a.count;
                }
            }

            Map<String, ResultRow> res = new TreeMap<>();
            for (var e : merged.entrySet()) {
                var a = e.getValue();
                res.put(e.getKey(), new ResultRow(a.min / 10.0, (a.sum / 10.0) / a.count, a.max / 10.0));
            }

            System.out.println(res);
        }
    }
}
