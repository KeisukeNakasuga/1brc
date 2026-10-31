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
```

| 実装 | 平均 [s] | 最速 [s] | 最遅 [s] | user [s] | system [s] | 倍率 |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 95.660 ± 0.865 | 94.476 | 96.811 | 93.713 | 3.087 | 1.00 |
| Solution1 | 80.151 ± 0.688 | 79.562 | 81.317 | 77.801 | 3.253 | 1.19 |
| Solution2 | 62.571 ± 0.299 | 62.086 | 62.823 | 60.225 | 3.017 | 1.53 |
| Solution3 | 61.376 ± 1.298 | 59.942 | 62.999 | 59.066 | 2.913 | 1.56 |
| Solution4 | 54.666 ± 2.372 | 52.881 | 58.693 | 52.281 | 2.879 | 1.75 |

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
