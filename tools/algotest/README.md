# 算法回归测试（不依赖 Android）

`AlgoTest.java` 是一个纯 JVM 测试，直接调用 `Recruit` 的公开 API，
用 `res/values/operators.xml` 的真实数据跑一遍必出星级断言。

期望值来源：PRTS 公开招募页（2026-08-01 池）+ operators.xml 实际成员，
不是照抄代码逻辑 —— 否则测试只会复述 bug。

`Recruit.java` 没有 import 任何 `android.*` 类，所以能脱离 SDK 单独编译。

## 跑法

需要一个 JDK 17（`javac` / `java` 在 PATH 上即可）。

**Linux / macOS**

```bash
cd tools/algotest
mkdir -p /tmp/arktest
javac -encoding UTF-8 -d /tmp/arktest ../../src/com/ark/recruit/Recruit.java AlgoTest.java
java -cp /tmp/arktest AlgoTest ../../res/values/operators.xml
```

**Windows (PowerShell)**

```powershell
$w="$env:TEMP\arktest"
New-Item -ItemType Directory -Force -Path $w | Out-Null
javac -encoding UTF-8 -d $w '..\..\src\com\ark\recruit\Recruit.java' AlgoTest.java
java -cp $w AlgoTest '..\..\res\values\operators.xml'
```

Windows 控制台默认是 GBK 代码页，中文输出可能乱码但 `OK / FAIL` 标记可读；
要看清中文可以先 `chcp 65001`，或把输出重定向到文件再读：

```powershell
java -Dfile.encoding=UTF-8 -cp $w AlgoTest '..\..\res\values\operators.xml' *> result.txt
```

## 退出码

全绿时 `exit 0`，任何一条失败 `exit 1`，可直接接进 CI。

## 覆盖的断言

| 标签 | 期望必出 | 说明 |
|---|---|---|
| 新手 | 2★ | 曾经写死成 1★ 的 bug |
| 支援机械 | 1★ | 8 位全是 1★ |
| 资深干员 / 高级资深干员 | 5★ / 6★ | 标签锁星，不被普通池拉低 |
| 爆发 / 控场 / 召唤 / 位移 / 削弱 / 快速复活 | 4★ | 由 3~5★ 池过滤得出 |
| 防护 / 治疗 / 减速 / 输出 / 近战位 | 3★ | |
| 资深干员+治疗、高级资深干员+输出 | 5★ / 6★ | 组合下仍锁星 |
| 召唤+辅助干员+远程位 | 5★ | 凯尔希/梅尔；豆苗是先锋所以排除 |
| 新手 countText | 含「仅2★」 | 文案与星级一致 |
| 支援机械 countText | 含「仅1★」 | 同上 |

当前结果：**21 通过 / 0 失败**。

改过 `Recruit.java` 或 `operators.xml` 之后都应该跑一次。
