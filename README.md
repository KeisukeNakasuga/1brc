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
hyperfine --warmup 1 --runs 5 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution9 > /dev/null'

# Solution10 以降は Solution6 と同じ hyperfine で続けて測る（表は 32 スレッドの値）
hyperfine --warmup 1 --runs 5 -N \
  -L sol Solution6,Solution10 -L threads 16,32 \
  -n '{sol} {threads}T' \
  'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.{sol} {threads}'
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
| Solution9 | 1.703 ± 0.060 | 1.647 | 1.802 | 47.467 | 1.686 | 56.17 |
| Solution10 | 1.298 ± 0.028 | 1.255 | 1.330 | 35.534 | 1.527 | 73.70 |

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
- Solution9：Solution6 を基に、ファイルを 8MB のチャンク（約 1,650 個）に分け、手が空いたスレッドが次のチャンクを取りに行くようにした（動的スケジューリング。ワークスティーリングと呼ばれることもあるが、厳密には共有カウンタから取り出す方式）。次に処理するチャンクの番号を `AtomicInteger` で全スレッドが共有し、`getAndIncrement()` で1つずつ取り出す。`StationTable` と 1MB のバッファはスレッドごとに持ち回す。Solution6 から約 0.23 秒（16%）**遅くなった**（同じ hyperfine で続けて測った Solution6 は 1.472 ± 0.082 秒）
  - user が 38.4 秒 → 47.5 秒と約 9 秒増えた。遊ぶ時間が減るどころか、CPU の仕事量そのものが増えている
  - チャンクの大きさを変えて調べた（下の「調査：チャンクの大きさ（Solution9）」を参照）
- Solution10：user領域の計算量改善。Solution6 を基に、気温のパースを書式（`-?\d?\d\.\d`）決め打ちにした。`\n` まで1バイトずつループして `.` かどうかを毎回調べるのをやめ、2文字目が `.` かどうかで整数部の桁数を決めて、位置を指定して読む。データで変わる分岐は「負の数か」と「1桁か2桁か」の2つだけになった。Solution6 から約 0.13 秒（9%）短縮（同じ hyperfine で続けて測った Solution6 は 1.427 ± 0.036 秒、16 スレッドでは 1.610 → 1.496 秒）
  - 処理速度は約 10.6 GB/秒（約 7.7 億行/秒）
  - `perf stat`（16 スレッド、3 回の平均）で、1行あたりの命令数は約 367 → 338（−8%）、分岐数は約 78 → 65（−17%）、サイクル数は約 120 → 115（−4%）に減った。IPC は 3.06 → 2.95 と少し下がったが、命令数の減りのほうが大きい
  - 狙っていた分岐予測の失敗は、1行あたり約 1.41 → 1.42 回で減らなかった。速くなったのは命令数と分岐数が減ったため
  - 解釈（未検証）：ランダムで予測できない決めごと（符号、桁数、地点名の長さ）は Solution6 と同じ。Solution6 のループでも、最初の分岐で桁数が分かれば残りは分岐の履歴から当てられていたと考えられる。予測の失敗を減らすには、分岐そのものをなくす必要がある

### CPU カウンタ（perf stat）

Solution10 以降は、何が効いたかを切り分けるために `perf stat` で命令数などを記録する。SMTの影響を除くため、物理コアと同じ16スレッドで3回実行した平均を使う。カウンタの±は 0.4% 以下で安定している。経過時間は1回ごとのばらつきが大きいので、速さの比較には上のhyperfineの値を使う。

```sh
perf stat -r 3 -e task-clock,cycles:u,instructions:u,branches:u,branch-misses:u \
  java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution10 16 > /dev/null
```

1行あたりの値（10 億行で割った値）

| 実装 | 命令数 | 分岐数 | 分岐予測の失敗 | サイクル数 | IPC |
|---|---:|---:|---:|---:|---:|
| Solution6 | 約 367 | 約 78 | 約 1.41 | 約 120 | 3.06 |
| Solution10 | 約 338 | 約 65 | 約 1.42 | 約 115 | 2.95 |

計測値

| 実装 | task-clock [ms] | cycles:u | instructions:u | branches:u | branch-misses:u |
|---|---:|---:|---:|---:|---:|
| Solution6 | 24,138.15 | 119,976,856,269 | 366,950,234,045 | 77,568,330,422 | 1,407,520,838 |
| Solution10 | 23,178.51 | 114,697,020,522 | 338,219,094,480 | 64,636,701,009 | 1,423,308,988 |

- IPC は instructions ÷ cycles
- Solution6 の値は Solution10 と同じ日に続けて測ったもの（「調査：Solution6を8/16/32スレッドで計測した結果」の 16 スレッドの値とは別の回）

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

## 調査：チャンクの大きさ（Solution9）

Solution9.java の `CHUNK_SIZE` の行だけを書き換えたコピーを scratchpad で `javac` してクラスパスを差し替え、同じ hyperfine の中で続けて測った（`-N` はシェルを通さずに実行するオプションで、出力は hyperfine が捨てる）。

```sh
hyperfine --warmup 1 --runs 5 -N \
  -n Solution6 'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6' \
  -n chunk8MB 'java --class-path <8MB版のクラス> dev.morling.onebrc.Solution9' \
  -n chunk64MB 'java --class-path <64MB版のクラス> dev.morling.onebrc.Solution9' \
  -n chunk1GB 'java --class-path <1GB版のクラス> dev.morling.onebrc.Solution9'
```

| 実装 | チャンク数 | 平均 [s] | 最速 [s] | 最遅 [s] | user [s] | system [s] |
|---|---:|---:|---:|---:|---:|---:|
| Solution6（32等分） | 32 | 1.440 ± 0.019 | 1.423 | 1.468 | 38.334 | 1.596 |
| chunk 8MB | 約 1,650 | 1.618 ± 0.032 | 1.573 | 1.655 | 46.657 | 1.636 |
| chunk 64MB | 約 206 | 1.430 ± 0.024 | 1.416 | 1.473 | 38.734 | 1.609 |
| chunk 1GB | 32（スレッド数が下限） | 1.482 ± 0.077 | 1.415 | 1.606 | 38.850 | 1.611 |

- 1GB はチャンク数がスレッド数で下限になり、Solution6 と同じ 32 等分になる。速さも Solution6 とほぼ同じなので、「`process()` を何度も呼ぶ」コードの構造は遅さの原因ではない
- 遅いのは 8MB だけで、user が約 8 秒増える。小さいチャンク特有のコストがある。原因は未検証
  - 候補：`process()` の呼び出しが短く多くなり、JIT の最適化の状態（OSR のコードか通常のコンパイルか）が変わる
  - 候補：各スレッドが飛び飛びの位置を読むので、キャッシュや先読みが効きにくくなる
- 64MB は Solution6 と同じ速さで、偏りがなくなったはずなのに速くならなかった
- 仮説（未検証）：SMT のおかげで、Solution6 の偏りは実害が小さかった
  - 物理コアは 16 個。早く終わったスレッドがあると、同じコアの相方がコアを独占して速く走れるので、論理 CPU が空いていても物理コアとしてはほとんど遊んでいない
  - 「32 × 経過時間 − CPU 時間」で見積もった遊んでいた時間は、論理 CPU 32 個で 32 倍になる前提なので、偏りの損失を大きく見積もりすぎていた
  - Solution8 は偏りが大きく、最後の 1 本が遅れたので、相方の独占でも埋めきれなかったと考えられる
  - 確かめるには、スレッド数 16（物理コアと同じ数）で Solution6 と chunk 64MB を比べる。相方による補い合いがなくなるので、偏りの影響がそのまま出て、64MB が勝つはず
- Solution6 の計測値は今回 1.44〜1.47 秒で、表の 1.358 秒より遅め。計測ごとの環境のばらつきがあるので、比べるときは同じ hyperfine で続けて測る

## 調査：Solution6 ~ 9で期待したよりも性能改善しなかった理由を考察
- mmap
  - mmapで削れるのはカーネルがreadでデータコピーする時間でこれは期待通り機能していた
    - コピー自体は省略されていた → sys時間
    - ページテーブルの作成も十分に高速だった → sys時間
  - mmapでコピーゼロを実現した結果、短くなる時間はCPU時間の理論値
    - user 38.36s, sys 1.29s, 処理量13.8G
      - 解析ループ(user) → 1バイトあたり 約2.8ns 1行あたり 約38ns
      - コピー(sys) → 1バイトあたり 約0.09ns 1行あたり 約1.3ns
        - コピーが占める割合がかなり少ない mmapが効いても後者に対してなので効きづらい
          - とはいえ性能が劣化したことを説明できないので、追加で実験が必要
      - user領域における処理の実行時間が大きいのでここを改善するほうが先か

## 調査：Solution6を8/16/32スレッドで計測した結果
コマンド
```bash
# スレッド数 8 / 16 / 24 / 32 で計測
hyperfine --warmup 1 --runs 5 -N \
  -P threads 8 32 -D 8 \
  -n 'Solution6 {threads}T' \
  'java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6 {threads}' \
  --export-markdown step0_hyperfine.md

# perf stat（16 スレッドと 32 スレッド、各 3 回の平均）
perf stat -r 3 -e task-clock,cycles:u,instructions:u,branches:u,branch-misses:u \
  java --class-path target/average-1.0.0-SNAPSHOT.jar dev.morling.onebrc.Solution6 16 > /dev/null
perf stat -r 3 -e task-clock,cycles:u,instructions:u,branches:u,branch-misses:u \
```

結果

Solution6 のスレッド数ごとの計測（hyperfine）

| スレッド数 | 平均 [s] | 最速 [s] | 最遅 [s] | user [s] | system [s] | 8 スレッド比 |
|---:|---:|---:|---:|---:|---:|---:|
| 8 | 2.868 ± 0.038 | 2.811 | 2.908 | 21.874 | 0.508 | 1.00 |
| 16 | 1.632 ± 0.047 | 1.560 | 1.673 | 23.632 | 0.666 | 1.76 |
| 24 | 1.554 ± 0.011 | 1.541 | 1.570 | 31.088 | 0.837 | 1.85 |
| 32 | 1.449 ± 0.014 | 1.428 | 1.465 | 39.572 | 1.342 | 1.98 |

Solution6 の perf stat（3 回の平均）

| 項目 | 16 スレッド | 32 スレッド |
|---|---:|---:|
| 経過時間 [s] | 1.678 ± 0.111 | 1.457 ± 0.007 |
| task-clock [ms] | 24,270.88 | 41,676.46 |
| cycles:u | 120,265,215,472 | 199,738,593,399 |
| instructions:u | 367,414,304,867 | 368,976,447,714 |
| branches:u | 77,696,816,941 | 78,351,884,227 |
| branch-misses:u | 1,412,445,393 | 1,419,546,028 |

perf stat から計算した値

| 項目 | 計算方法 | 16 スレッド | 32 スレッド |
|---|---|---:|---:|
| 1 行あたりの命令数 | instructions ÷ 10 億行 | 約 367 | 約 369 |
| 1 行あたりの分岐数 | branches ÷ 10 億行 | 約 78 | 約 78 |
| 1 行あたりの分岐予測の失敗 | branch-misses ÷ 10 億行 | 約 1.41 | 約 1.42 |
| 1 行あたりのサイクル数 | cycles ÷ 10 億行 | 約 120 | 約 200 |
| IPC（論理スレッドあたり） | instructions ÷ cycles | 3.05 | 1.85 |
| 平均クロック [GHz] | cycles ÷ task-clock | 約 4.96 | 約 4.79 |
