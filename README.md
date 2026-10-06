# code-split-planner-java

资源分包与按需加载策略服务。上层提交模块依赖图（模块 + 导入关系 + 入口标记），
服务输出**确定的**分包方案与加载计划：哪些模块随首屏立即加载（EAGER）、哪些提前
预取（PREFETCH）、哪些按需懒加载（LAZY）、哪些被跳过（SKIPPED）及原因。

## 构建与运行

```bash
mvn package          # 构建并运行全部测试
mvn spring-boot:run  # 启动服务，默认端口 8080
```

## 核心语义

### 确定性（Determinism）

同一份依赖图，无论模块/边的提交顺序如何、无论并发度多高，生成的计划在
**包划分、包内成员顺序、包标识**上逐项一致：

- 所有图遍历（可达性、Tarjan SCC、BFS 距离）均按模块 id 字典序迭代；
- 包内成员按 id 字典序排序；
- 包标识 `bundle-<sha256 前16位>`，仅由排序后的成员列表决定；
- 更新串行化、提交原子化（见下文"并发与提交语义"）。

### 分包规则（归组与去重边界）

1. 计算每个模块的**入口集合**（能到达它的入口的集合）与**距最近入口的 BFS 跳数**。
2. 入口集合相同 **且** 跳数相同的模块归入同一个包。
   - 被多个入口同时引用的模块入口集合大小 > 1，自然落入共享包（`shared=true`），
     **只打包一次**；去重边界即"入口集合 + 跳数"这个分组键。
   - 按跳数分层使得"按距离降级"的预算策略对单入口链式依赖同样有效。
3. 每个模块在计划中至多属于一个包（`moduleToBundle` 可查验）。

### 加载时机（Load Timing）

| 时机 | 含义 | 判定规则 |
|---|---|---|
| `EAGER` | 随首屏加载 | 默认全部可达非环模块；在预算内则保持 |
| `PREFETCH` | 提前预取 | 被预算降级、且是某个 EAGER 包的**直接依赖** |
| `LAZY` | 按需懒加载 | 被预算降级且非 EAGER 直接依赖；或处于依赖环中 |
| `SKIPPED` | 不加载 | 从任何入口都不可达，原因见 `skipReasons` |

### 循环依赖

- 策略 `DEGRADE`（默认）：每个环（大小 > 1 的强连通分量或自环）整体折叠为一个
  LAZY 包，不参与首屏；计划正常生成，并产生 `CYCLE_DETECTED` 诊断，诊断中列出
  排序后的环成员，可定位。
- 策略 `REJECT`：整次生成被拒绝，抛出携带 `CYCLE_DETECTED` 诊断的
  `PlanRejectedException`（HTTP 422），不会产出半成品计划。

### 不可达模块

不可达模块标记为 `SKIPPED` + `SkipReason.UNREACHABLE`，绝不计入首屏集合。
跳过原因与"被预算降级"（见 `demotions`）是**不同的通道**，可明确区分。
引用了不存在模块的导入边产生 `MISSING_IMPORT_TARGET` 诊断并被忽略。

### 首屏预算

- 约束：`planner.max-first-screen-modules`（模块数）与
  `planner.max-first-screen-bytes`（总体积），可同时配置。
- 超出时按**整包**降级：候选包按"距入口最大跳数降序、包 id 升序"依次降级，
  直到两个约束都满足。含入口模块的包永不降级。
- 每次降级记录一条 `Demotion`：包 id、成员、触发约束
  （`MODULE_COUNT` / `BYTES`）、降级后的时机，可解释到具体模块与触发条件。
- 若入口自身已超预算：不静默超支，产生 `BUDGET_EXCEEDED_BY_ENTRIES` 诊断，
  入口保持 EAGER 并明确报告溢出。

### 增量更新

模块/导入边增删改后，服务计算**受影响范围**并只重算受影响部分：

- 受影响入口：在旧图或新图中能到达任一变更模块（含变更边端点）的入口；
- 受影响模块：受影响入口在旧图/新图中的可达闭包 ∪ 变更模块本身；
- 未受影响模块的分组键（入口集合 + 跳数）与环归属可证明不变，**直接沿用旧包**，
  只有受影响模块重新分组；预算判定是合并后包集合的确定性函数，全局重跑。
- 因此增量结果与全量重算**逐项一致**（有测试保证），更新响应中携带
  `affectedEntries` / `affectedModules` 说明受影响范围。

### 并发与提交语义

- 所有更新由单锁串行化，先在内存中构建完整的新计划并**落盘**，然后原子交换
  `AtomicReference` 发布；
- 读取不加锁，只能看到某个**已提交的完整计划**，永远不会读到半更新状态；
- 版本号单调递增，每次提交 +1。

### 版本化、历史与持久化

- 每次提交将计划与产生它的图快照写入 `planner.store-dir`
  （`plan-v<n>.json` / `graph-v<n>.json`，临时文件 + 原子移动）；
- 可读取任意历史版本、对比两个版本的差异（新增包、移除包、模块级时机变化）；
- 重启后从存储目录恢复最新已提交计划，历史版本仍可读取、可复现。

### 入口加载集合查询

`GET /api/planner/entries/{entryId}/load-set` 返回该入口真正需要加载的模块：

- 取该入口在图中的可达闭包，按各模块在计划中的时机分组返回；
- 重复引用与传递依赖自然去重：每个模块恰好出现一次；
- 边界行为：模块归属以其所在包的时机为准；`SKIPPED` 模块不出现在加载集合中。

## 配置项（`planner` 前缀）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `planner.max-first-screen-modules` | `-1` | 首屏最大模块数，`-1` 不限 |
| `planner.max-first-screen-bytes` | `-1` | 首屏最大总体积（字节），`-1` 不限 |
| `planner.cycle-strategy` | `DEGRADE` | 环处理策略：`DEGRADE` / `REJECT` |
| `planner.store-dir` | `data/plans` | 计划与图快照的持久化目录 |

## REST API

| 方法 & 路径 | 说明 |
|---|---|
| `PUT /api/planner/graph` | 整体替换依赖图并全量重算，body: `{modules:[{id,sizeBytes,entry}], imports:[{from,to}]}` |
| `POST /api/planner/graph/modules` | 新增/更新模块（增量重算） |
| `DELETE /api/planner/graph/modules/{id}` | 删除模块（增量重算） |
| `POST /api/planner/graph/imports` | 新增导入边 `{from,to}`（增量重算） |
| `DELETE /api/planner/graph/imports` | 删除导入边 `{from,to}`（增量重算） |
| `GET /api/planner/plans/current` | 当前已提交计划 |
| `GET /api/planner/plans` | 全部已提交版本号 |
| `GET /api/planner/plans/{version}` | 读取历史版本 |
| `GET /api/planner/plans/diff?from=&to=` | 对比两个版本 |
| `GET /api/planner/entries/{entryId}/load-set` | 入口实际加载集合 |

错误：查询不存在的版本/入口返回 404；`REJECT` 策略下存在依赖环返回 422 及诊断。

## 测试

`src/test/java/...` 覆盖：乱序输入与并发下的确定性、共享依赖归组去重、
循环（降级/拒绝）与不可达、预算降级（数量/体积/入口超支）、增量重算与全量一致、
并发读写只见完整计划、版本化持久化与重启恢复、入口加载集合查询。
