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
```

| 実装 | 平均 [s] | 最速 [s] | 最遅 [s] | user [s] | system [s] | 倍率 |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 95.660 ± 0.865 | 94.476 | 96.811 | 93.713 | 3.087 | 1.00 |

- baseline：`user` ≈ 経過時間なので、1コアだけで処理している（32スレッド中31スレッドは空いている）
- 処理速度は約 144 MB/秒（約 1,050 万行/秒）。ディスクではなく、1行ごとの文字列解析と集計が遅い
