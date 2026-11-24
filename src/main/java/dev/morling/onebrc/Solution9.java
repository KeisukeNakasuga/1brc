package dev.morling.onebrc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public class Solution9 {

    private static final String FILE = "./measurements.txt";

    // ファイルをこの大きさのチャンクに分け、手が空いたスレッドが次のチャンクを取りに行く
    private static final long CHUNK_SIZE = 8L << 20; // 8MB

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

        MeasurementAggregator get(byte[] buf, int start, int len, int hash) {
            int idx = hash & (SIZE - 1);
            while (true) {
                byte[] key = keys[idx];
                if (key == null) {
                    keys[idx] = Arrays.copyOfRange(buf, start, start + len);
                    vals[idx] = new MeasurementAggregator();
                    return vals[idx];
                }
                if (Arrays.equals(key, 0, key.length, buf, start, start + len)) {
                    return vals[idx];
                }
                idx = (idx + 1) & (SIZE - 1); // アクセスしたインデックスが埋まっていたら隣を使う
            }
        }
    }

    // ファイルをnChunks個に分割し、各区切りを「次の改行の直後」までずらす(綺麗に分割する)
    // bounds[i] ～ bounds[i+1] がi番目のチャンクの範囲
    // Solution6はスレッド数で分割していたが、ここではスレッド数よりずっと多いチャンクに分ける
    private static long[] splitFile(FileChannel ch, int nChunks) throws IOException {
        long size = ch.size();
        long[] bounds = new long[nChunks + 1];
        bounds[nChunks] = size;
        ByteBuffer bb = ByteBuffer.allocate(256); // 1行は最大でも 100+1+5+1 バイト程度
        for (int i = 1; i < nChunks; i++) {
            long pos = Math.max(size * i / nChunks, bounds[i - 1]);
            bb.clear();
            ch.read(bb, pos);
            int j = 0;
            while (j < bb.position() && bb.get(j) != '\n')
                j++;
            bounds[i] = Math.min(pos + j + 1, size);
        }
        return bounds;
    }

    // start ～ endの範囲をSolution6と同じループで集計する
    // 1スレッドが複数のチャンクを処理するので、tableとbufは呼び出し側(スレッド)で持ち回す
    private static void process(FileChannel ch, long start, long end, StationTable table, byte[] buf) throws IOException {
        int len = 0;
        long filePos = start;

        while (filePos < end) {
            // 担当範囲の終わりを越えて読まないように、読む量を制限する
            int want = (int) Math.min(buf.length - len, end - filePos);
            int n = ch.read(ByteBuffer.wrap(buf, len, want), filePos);
            if (n <= 0)
                break;
            filePos += n;
            len += n;

            // 改行の位置(= 改行以降を次回のループで使う)をメモする
            // lastには改行を手がかりに「途中で切れたバイト」分読み戻しておく
            int last = len - 1;
            while (buf[last] != '\n')
                last--;

            int pos = 0;
            while (pos <= last) {
                int lineStart = pos;
                int hash = 0;
                byte b;
                while ((b = buf[pos]) != ';') {
                    hash = 31 * hash + b;
                    pos++;
                }
                MeasurementAggregator agg = table.get(buf, lineStart, pos - lineStart, hash);
                pos++;

                boolean neg = buf[pos] == '-';
                if (neg)
                    pos++;
                int v = 0;
                while ((b = buf[pos]) != '\n') {
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

            int rest = len - (last + 1);
            System.arraycopy(buf, last + 1, buf, 0, rest);
            len = rest;
        }
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
            int nChunks = (int) Math.max(nThreads, (ch.size() + CHUNK_SIZE - 1) / CHUNK_SIZE);
            long[] bounds = splitFile(ch, nChunks);

            // 次に処理するチャンクの番号。全スレッドで共有し、getAndIncrementで1つずつ取り出す
            // (アトミックなので同じチャンクを2つのスレッドが取ることはない)
            AtomicInteger nextChunk = new AtomicInteger();

            // 各スレッドが自分専用のStationTableに集計する(共有しないのでロック不要)
            StationTable[] tables = new StationTable[nThreads];
            Thread[] threads = new Thread[nThreads];
            for (int i = 0; i < nThreads; i++) {
                final int t = i;
                threads[i] = new Thread(() -> {
                    try {
                        StationTable table = new StationTable();
                        byte[] buf = new byte[1 << 20]; // 1MB
                        // チャンクがなくなるまで「1つ取る → 処理する」を繰り返す
                        int c;
                        while ((c = nextChunk.getAndIncrement()) < nChunks) {
                            process(ch, bounds[c], bounds[c + 1], table, buf);
                        }
                        tables[t] = table;
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
