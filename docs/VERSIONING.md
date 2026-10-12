# 版本线与发布流程

EasyElevator 同时维护两条 Minecraft 版本线：用**分支**隔离源码，用**标签与产物名**隔离发布。
本文只讲流程；代码层面的逐项差异见 [构建与打包说明书](BUILD_AND_PACKAGING.md) 第 9 节。

## 1. 分支

| 分支 | Minecraft | 定位 |
| --- | --- | --- |
| `main` | 1.21.1 | 主线：新功能、修复先落这里 |
| `mc/1.20.1` | 1.20.1 | 维护线：只接受从 `main` backport 的改动 |

两条线**共用同一份源码**（同一份 `logic/`、`entity/`、文档骨架），差异集中在构建配置、
数据包目录名与网络层收发——也就是上面那份清单里列出的文件。

## 2. 铁律与 backport 流程

1. **永不双向合并**。把 `main` 整体 merge 进维护线（或反向），会把另一侧的适配代码也带过去；
   之后每次同步都要重新解一遍同样的冲突。这是两条线互相污染的唯一根源。
2. 新功能一律先进 `main`，在 `main` 上验证（历史上这里跑的是 `-PriderTests` 的 37 项 GameTest，
   测试源集已随清理移除，改为按 [人工验收清单](TESTING.md) 验收），确认后再按需 backport。
3. backport 用 cherry-pick，并用 `-x` 在提交信息里记下来源：

```powershell
git switch mc/1.20.1
git cherry-pick -x <main 上的提交 sha>

# 冲突只会出现在 BUILD_AND_PACKAGING.md 第 9 节列出的文件上。
# cherry-pick 期间 "ours" = 当前分支（维护线），版本号文件应当保留本线的值：
git checkout --ours -- gradle.properties
git add gradle.properties
git cherry-pick --continue
```

4. backport 完成后**先在维护线跑一次 `gradlew build`**：编译就是最便宜的 API 漂移探测器。
   改动涉及注册表、数据包、Mixin 或网络层时，再补一次 `gradlew runServer` 冒烟
   （几十秒内可确认模组加载、required Mixin 注入与数据包解析）。

## 3. 标签与产物

| 分支 | 标签示例 | 产物 |
| --- | --- | --- |
| `main` | `EasyElevator_v2.3.0+1.21.1` | `easyelevator-2.3.0+1.21.1.jar` |
| `mc/1.20.1` | `EasyElevator_v2.3.0+1.20.1` | `easyelevator-2.3.0+1.20.1.jar` |

- `mod_version` 一律写成 `<逻辑版本>+<MC 版本>`：同一次逻辑改动在两条线上版本号相同、
  JAR 名不同，玩家不会下错，也不会互相覆盖 `build/libs` 里的文件。
- 一个标签只能指向一个提交，因此**两条线各建一个 GitHub Release**，正文里互相加一句
  「1.20.1 线见另一个 Release」；Release 资产只挂本线 `packageRelease` 的产物。
- `fabric.mod.json` 的 `minecraft` 依赖已收窄为 `~<本线版本>`：装错实例时 Fabric Loader 直接拒绝加载。

## 4. 工作副本

两条线并存时建议用 git worktree，避免来回切分支让 `build/`、`run/`、`.gradle/loom-cache` 互相覆盖：

```powershell
git worktree add ..\EasyElevator-1.20.1 mc/1.20.1
```

`run/` 里是开发用的世界，而世界格式**不可降级**：1.21.1 的存档在 1.20.1 的开发端打不开（反之亦然）。
两条线各用自己的工作副本，就不会踩到这件事。
