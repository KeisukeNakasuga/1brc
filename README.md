# 1brc

## 実行環境

| 項目 | 内容 |
|---|---|
| CPU | AMD Ryzen 9 7950X（16コア / 32スレッド、最大 5.88 GHz） |
| キャッシュ | L1d 512 KiB / L2 16 MiB / L3 64 MiB |
| メモリ | 62 GiB |
| ストレージ | Corsair MP700（NVMe SSD 1TB）、ext4 |
| OS | Gentoo Linux（kernel 6.18.57） |
| JDK | Temurin 21.0.12.1（`/opt/openjdk-bin-21`） |

## 計測方法

`time` で実行時間を計測する。結果の出力は計測に含めないよう `/dev/null` に捨てる。

```sh
export JAVA_HOME=/opt/openjdk-bin-21 PATH=/opt/openjdk-bin-21/bin:$PATH
time ./calculate_average_baseline.sh > /dev/null
```

| 項目 | 意味 |
|---|---|
| `total` | 実際の経過時間（wall-clock time）。比較にはこれを使う |
| `user` | 全コアで CPU が使った時間の合計 |
| `cpu` | `user` ÷ `total`。100% なら1コア分、3200% なら32コアをフルに使っている |

- データ：`./create_measurements.sh 1000000000` で作成した10億行（13GB）の `measurements.txt`
- ファイルはページキャッシュに収まるため、ディスク I/O の影響はほぼない
- 1回が数秒になってきたら、[hyperfine](https://github.com/sharkdp/hyperfine) で複数回の平均を取る
  - 参考：公式の評価（`evaluate.sh`）は8コアに制限し、10回実行して最速と最遅を除いた平均を取っている

## 計測結果

hyperfine でウォームアップ1回 + 5回実行した平均。

```sh
hyperfine --warmup 1 --runs 5 './calculate_average_baseline.sh > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution1 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution2 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution3 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution4 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution5 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution7 > /dev/null'
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution8 > /dev/null'
```

| 実装 | 平均 [s] | 最速 [s] | 最遅 [s] | user [s] | system [s] | 倍率 |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 95.660 ± 0.865 | 94.476 | 96.811 | 93.713 | 3.087 | 1.00 |
| Solution1 | 80.151 ± 0.688 | 79.562 | 81.317 | 77.801 | 3.253 | 1.19 |
| Solution2 | 62.571 ± 0.299 | 62.086 | 62.823 | 60.225 | 3.017 | 1.53 |
| Solution3 | 61.376 ± 1.298 | 59.942 | 62.999 | 59.066 | 2.913 | 1.56 |
| Solution4 | 54.666 ± 2.372 | 52.881 | 58.693 | 52.281 | 2.879 | 1.75 |
| Solution5 | 20.007 ± 0.353 | 19.632 | 20.375 | 19.389 | 0.659 | 4.78 |
| Solution6 | 1.358 ± 0.024 | 1.341 | 1.398 | 38.357 | 1.292 | 70.44 |
| Solution7 | 1.676 ± 0.044 | 1.645 | 1.752 | 41.757 | 0.658 | 57.08 |
| Solution8 | 1.705 ± 0.027 | 1.664 | 1.734 | 42.641 | 0.672 | 56.11 |

- baseline：`user` ≈ 経過時間なので、1コアだけで処理している（32スレッド中31スレッドは空いている）
- 処理速度は約 144 MB/秒（約 1,050 万行/秒）。ディスクではなく、1行ごとの文字列解析と集計が遅い
- Solution1：`split(";")` を `indexOf` + `substring` に置き換えた。1行ごとの `ArrayList` と `String[]` の生成がなくなり、約 15.5 秒（16%）短縮
  - 処理速度は約 172 MB/秒（約 1,250 万行/秒）。まだ1コアのみ
- Solution2：気温を `Double.parseDouble` ではなく自作パースで10倍した `int` として読み、集計も `int` / `long` にした。数値部分の `substring` もなくなり、Solution1 から約 17.6 秒（22%）短縮
  - 処理速度は約 220 MB/秒（約 1,600 万行/秒）。まだ1コアのみ
- Solution3：`Measurement` record と `Collector` / `groupingBy` をやめ、自前の `HashMap<String, MeasurementAggregator>` の箱を直接更新するようにした。Solution2 との差は約 1.2 秒で、ばらつき（σ 1.3 秒）と同程度のため誤差の範囲
  - JIT のエスケープ解析で `Measurement` の生成が既に消えていた可能性がある
- Solution4：`HashMap` を自作のハッシュテーブル `StationTable`（オープンアドレス法、サイズ 2^16）に置き換えた。`;` を探すループで同時にハッシュを計算し、既存の地点は元の行と直接比較するため、地点名の `substring` は新しい地点のときだけ（約400回）になった。JITでも消せないオブジェクト生成がなくなった。Solution3 から約 6.7 秒（11%）短縮
  - 処理速度は約 252 MB/秒（約 1,830 万行/秒）。まだ1コアのみ
  - σ が大きめ（最遅の 58.7 秒が突出）。計測中に他の処理が動いていた可能性がある
- Solution5：`Files.lines` をやめ、`FileInputStream` で 1MB ずつ `byte[]` に読み込み、`;` と `\n` を自分で探すようにした。UTF-8 のデコードと1行ごとの `String` 生成がなくなり、1行あたりのオブジェクト生成がゼロになった。バッファの境目で切れた行は先頭に移して次の読み込みとつなげる。地点名は `byte[]` のままハッシュ・比較し、`String` にするのは出力時だけ。Solution4 から約 34.7 秒（63%）短縮
  - 処理速度は約 690 MB/秒（約 5,000 万行/秒）。まだ1コアのみ
  - system も 2.9 秒 → 0.7 秒に減った。ヒープの使用量が激減し、メモリ確保に伴う OS とのやり取りが減ったためと考えられる
- Solution6：ファイルをスレッド数（32）で等分し、各区切りを次の改行の直後までずらして、スレッドごとに担当範囲を処理するようにした。読み込みは `FileChannel` の位置指定 `read` を使い、解析ループは Solution5 と同じ。`StationTable` はスレッドごとに持ち、最後に `TreeMap` でまとめる。Solution5 から約 18.6 秒（93%）短縮し、14.7 倍になった
  - 処理速度は約 10.2 GB/秒（約 7.4 億行/秒）。ディスクの速度を超えているので、ページキャッシュから読んでいる
  - user が 19.4 秒 → 38.4 秒と約 2 倍になった。16 コア / 32 スレッドで 14.7 倍なので、SMT の 2 本目のスレッドは物理コア 1 つ分には届いていない
- Solution7：`read` で `byte[]` にコピーするのをやめ、`FileChannel.map` で担当範囲を `MappedByteBuffer` としてマップして直接読むようにした（1回のマップは最大 1GB）。マップ範囲の最後の改行までを処理し、次のマップをその直後から始めるので、途中で切れた行のコピーも不要になった。解析ループは Solution6 と同じ。Solution6 から約 0.32 秒（23%）**遅くなった**
  - 処理速度は約 8.2 GB/秒（約 6.0 億行/秒）
  - system は 1.29 秒 → 0.66 秒と半分になり、カーネルから `byte[]` へのコピーがなくなった効果は出ている
  - 一方で user が 38.4 秒 → 41.8 秒と約 3.4 秒増え、system の減り分を上回った。`buf[pos]` が `buf.get(pos)` になり、1バイトごとに範囲チェックやマップの有効性チェックが入るためと考えられる。地点名の比較も、まとめて比較する `Arrays.equals` から1バイトずつのループになった
  - ページキャッシュに載っている状態では `read` のコピーはもともと安く、それより1バイトずつの読み込みのコストのほうが効く
- Solution8：Solution7 の `buf.get(pos)` をやめ、`sun.misc.Unsafe` でマップ領域のアドレスを直接読むようにした（`UNSAFE.getByte(アドレス)`）。範囲チェックとマップの有効性チェックがなくなる。アドレスは `MappedByteBuffer` 内部の `Buffer.address` フィールドから取る。`buf` が GC で回収されるとマップが解除されて JVM ごと落ちるので、`Reference.reachabilityFence(buf)` で読み終わるまで生かしておく。Solution7 との差は約 0.03 秒で、ばらつき（σ 0.03〜0.04 秒）と同程度のため誤差の範囲
  - 処理速度は約 8.1 GB/秒（約 5.9 億行/秒）
  - チェックをなくしても速くならなかったので、Solution7 で考えた「`buf.get()` のチェックが重い」という原因は外れ（下の「調査：mmap が遅い原因」を参照）

## 調査：mmap が遅い原因（Solution6〜8）

`perf stat` で Solution6（`read` + `byte[]`）と Solution8（mmap + `Unsafe`）を1回ずつ比べた。

```sh
perf stat -e page-faults,dTLB-load-misses,cache-misses java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6 > /dev/null
perf stat -e page-faults,dTLB-load-misses,cache-misses java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution8 > /dev/null
```

| 項目 | Solution6 | Solution8 |
|---|---:|---:|
| page-faults | 37,104 | 216,898 |
| dTLB-load-misses | 2,217,101 | 4,008,232 |
| cache-misses | 138,275,975 | 95,243,841 |
| 経過時間 [s] | 1.500 | 1.704 |
| user [s] | 39.569 | 37.916 |
| sys [s] | 1.531 | 0.658 |
| 並列度（(user + sys) / 経過時間） | 約 27.4 | 約 22.7 |

- イベント名の `:u` は user モードの分だけ数えているという意味。Solution6 の `read` で kernel がコピーするときのミスは入っていない
- 仮説1「mmap にすると、メモリ待ち（キャッシュミス・TLB ミス）が kernel 側から user 側に移って増える」は外れ
  - cache-misses はむしろ減り、dTLB-load-misses も約 1.8 倍にとどまる
- page-faults は約 18 万回増えた。ファイルのページは 13.8GB ÷ 4KB ＝ 約 340 万あるが、Linux はページフォルト1回で周辺 64KB（16 ページ）をまとめてマップする（fault-around）ため、13.8GB ÷ 64KB ＝ 約 21 万回とほぼ一致する
- この計測では Solution8 のほうが CPU 時間（user + sys）は少ないのに、経過時間は長い。並列度が約 27 → 約 23 に下がっていて、スレッドが何かを待って CPU が遊んでいる
  - ただし user は hyperfine では 42.6 秒、この計測では 37.9 秒とばらつきが大きい。1回だけの計測なので割り引いて見る
- 仮説2（未検証）：ページフォルトと `ch.map()` でスレッドどうしが待ち合っている
  - ページフォルトの処理ではプロセス全体のメモリ管理用ロック（`mmap_lock`）が使われ、`map()` はこれを書き込みで取る。32 スレッドが同時にフォルトを起こすとロック待ちで眠る時間が生まれ、眠っている時間は CPU 時間に数えられない
  - `perf stat -e context-switches` は `perf_event_paranoid=2` のため user モードの分（`:u`）しか数えられず、0 になって使えなかった。代わりに `/usr/bin/time -v` で比べた

```sh
/usr/bin/time -v java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6 > /dev/null
/usr/bin/time -v java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution8 > /dev/null
```

| 項目 | Solution6 | Solution8 |
|---|---:|---:|
| 経過時間 [s] | 1.45 | 1.69 |
| user + sys [s] | 40.97 | 40.82 |
| Percent of CPU | 2817% | 2408% |
| CPU が遊んでいた時間（32 × 経過時間 − CPU 時間）[s] | 約 5.4 | 約 13.3 |
| Voluntary context switches | 1,787 | 2,698 |
| Involuntary context switches | 2,785 | 3,115 |
| Minor page faults | 37,304 | 217,489 |
| Maximum resident set size | 154 MB | 13.5 GB |

- CPU の仕事量はほぼ同じで、CPU が遊んでいた時間だけが約 8 秒増えた
- 仮説2はほぼ外れ。自分から眠った回数（Voluntary）は 911 回しか増えていない。約 8 秒の空きをロック待ちで説明するには1回あたり平均 9ms 眠る必要があり、ロック待ちとしては長すぎる
- RSS 13.5GB はマップしたファイルのページ（ページキャッシュそのもの）が数えられているだけで、新しくメモリを確保しているわけではない。`MappedByteBuffer` は GC されるまでマップを解除しないので、最後にはファイル全体がマップされた状態になる
- 仮説3：スレッド間の負荷の偏り。早く終わったスレッドは眠るのではなく終了するので Voluntary context switches には現れず、遅いスレッドを待つ間 CPU が遊ぶ。Solution6 でも約 5.4 秒遊んでいるので偏りはもともとあり、mmap で大きくなった可能性がある

### 仮説3の検証：スレッドごとの処理時間

Solution6 と Solution8 のコピー（Solution6Timing / Solution8Timing）に、各スレッドの `process()` の前後で `System.nanoTime()` を取って標準エラーに出す計測を足し、1回ずつ実行した（コピーは scratchpad に置き、リポジトリには入れていない）。

| 項目 | Solution6 | Solution8 |
|---|---:|---:|
| 処理時間 min / avg / max [ms] | 1,130 / 1,300 / 1,397 | 1,057 / 1,359 / 1,586 |
| max − min [ms] | 267 | 528 |
| 最後のスレッドの終了時刻 [ms] | 1,416 | 1,589 |
| 偏りで遊んだ時間（32 × (最後の終了 − 平均の終了)）[s] | 約 3.5 | 約 7.3 |

- 仮説3は裏づけられた。偏りが約 2 倍になり、偏りで遊んだ時間が約 3.8 秒増えた。`/usr/bin/time` で見た「遊んでいた時間が約 8 秒増えた」の半分ほどがこれで説明できる
- Solution6 でも偏りで約 3.5 秒遊んでいて、遊んでいた約 5.4 秒の大半はこれで説明できる。残りは JVM の起動や最後の集計・出力など1スレッドで動く時間と考えられる
- スレッドの開始時刻のずれは数 ms〜19 ms で、起動の遅れはほぼ問題にならない
- Solution8 は二極化している。thread 0, 7, 12, 14, 15, 18, 31 の 7 本だけが 1,057〜1,138 ms と速く、残りは 1,355〜1,480 ms（thread 27 だけ 1,586 ms）。担当サイズはすべて 431 MB なので、データ量の偏りではない
- 1スレッドあたりの平均も 1,300 → 1,359 ms と約 5% 遅い
- 経過時間の差（hyperfine で約 0.32 秒）は最後の終了時刻の差（0.17 秒）より大きい。スレッドの外、たとえば終了時に 13.5GB 分のマップを解除する時間がかかっている可能性がある（未検証）
- 二極化の理由は未検証。CPU は Ryzen 9 7950X（16 コア / 32 スレッド、8 コアずつの CCD が 2 つで L3 も別）
  - SMT で物理コアを相方と分け合うと遅く、相方が空いていると速い。7 本だけ速いのは OS のスケジューリングによる組み合わせ次第かもしれない
  - mmap ではページフォルトで kernel の処理が割り込むので、スレッドごとの差が出やすいのかもしれない
  - 数回実行して、速いスレッドの番号が毎回同じ（ファイルの位置やデータが理由）か、実行ごとに変わる（スケジューリングや SMT が理由）かを見ると絞り込める
- 対策：ファイルを数 MB の小さなチャンクに分け、空いたスレッドが次のチャンクを取りに行く（ワークスティーリング）と、原因によらず偏りを吸収できる。Solution6 にも効くはず
