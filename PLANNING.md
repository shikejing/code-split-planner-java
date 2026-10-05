# 资源分包与按需加载策略服务

上层提交模块依赖图（模块 + 导入关系 + 入口标记），服务输出**确定的**分包方案与加载计划：
哪些模块随首屏立即加载（EAGER）、哪些按需懒加载（LAZY）、哪些提前预取（PREFETCH）。

## 快速开始

```bash
mvn spring-boot:run          # 启动服务
mvn test                     # 运行全部测试
```

## 核心语义

### 1. 确定性（需求 1）

同一份依赖图，无论模块/边/入口以什么顺序提交、多少并发度下生成，输出逐项一致：

- 所有遍历前先按模块 id / 入口 id **字典序**排序，不依赖任何 Map/Set 迭代顺序；
- 包内成员按模块 id 字典序排列；
- 包标识 `chunk-<sha256前16位>` 由**排序后的成员列表**哈希推导，与输入顺序无关；
- 计划对象不可变，生成过程无任何随机源、时间戳。

### 2. 共享依赖归组与去重边界（需求 2）

- 对每个可达模块计算 **owner 集合** = 能（传递）到达它的入口集合；
- **owner 集合完全相同的模块归为一个包**；
- owner 集合大小 ≥ 2 的模块即共享依赖，只出现在**唯一一个**共享包中，绝不重复打包；
- 去重边界：模块级别。一个模块在整份计划中只出现在一个包里；两个入口共享
  传递依赖时，该依赖随共享包加载一次；
- 共享包默认随首屏（EAGER），配置 `planner.shared-chunk-prefetch=true` 后改为 PREFETCH。

### 3. 循环依赖与不可达模块（需求 3）

- **不可达模块**（任何入口都无法到达）：不进入任何包、不计入首屏集合，
  在 `skipped` 中以 `UNREACHABLE` 记录；
- **依赖环**：在可达子图上跑 Tarjan 强连通分量。环（大小>1 或自环）**整体降级**
  为一个 LAZY 包，生成不会失败也不会死循环；环成员在 `skipped` 中以 `IN_CYCLE`
  记录，其落位 `reason` 以 `cycle:` 开头并列出环成员，可定位；
- 指向不存在模块的导入边（dangling import）被忽略。

### 4. 首屏预算与降级（需求 4）

预算只统计 EAGER 模块。超限（模块数或总体积任一）时按既定策略降级：

1. 候选 = 非入口的 EAGER 模块（**入口模块永不降级**，保证可启动）；
2. 每轮按 **体积降序、id 升序** 取一个候选，从原包拆出、单独形成 LAZY 包；
3. 重复直到满足预算或无候选；若仅剩入口仍超预算，保持现状（下限行为，不静默失败——
   预算约束本身不适用于入口）；
4. 每个被降级模块的 `reason` 记录触发条件，例如
   `budget-demoted: eagerBytes=940>maxBytes=100`，可解释到具体模块。

### 5. 增量更新与并发（需求 5）

- 服务缓存每个入口的可达集合。一批变更提交时，先算**受影响范围** =
  变更模块 ∪ 其在变更前/后图上的反向可达闭包（所有传递依赖者）；
- 只有可达集合与受影响范围相交的入口重新遍历，其余复用缓存；
  最终计划与全量重算**逐项一致**（`PlannerServiceTest.incrementalResultEqualsFullRecompute` 保证）；
- `applyUpdate` 返回受影响模块集合；
- 并发：公平读写锁。写线程在写锁内完成“变更→重算→持久化→提交”，
  提交只是原子替换当前计划引用；读线程只能看到某个**已提交的完整计划**，
  不会读到半更新状态。

### 6. 版本化、历史对比与持久化（需求 6）

- 每次提交生成单调递增的版本号（从 1 开始，重启后续接）；
- 每个计划以 JSON 原子落盘（先写临时文件再改名）到 `planner.data-dir`，
  图状态存 `graph.json`；重启后全部已提交计划恢复、可复现；
- `GET /api/planner/plans/{version}` 读历史版本；
- `GET /api/planner/diff?from=A&to=B` 输出：新增/移除模块、加载时机变化、
  新增/移除分包。

### 7. 按入口查询加载集合（需求 7）

`GET /api/planner/entries/{entry}/load-set` 返回该入口**真正需要加载**的模块及各自时机：

- 边界行为：入口的**传递闭包**（含入口自身），重复引用只出现一次；
- 不包含其它入口的私有模块；不可达模块永不出现；
- 环内模块若可达，以 LAZY 时机列出（可定位降级结果）；
- 未知入口返回 404。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/planner/updates` | 提交一批图变更，返回 `{version, affectedModules}` |
| GET | `/api/planner/plans/current` | 当前已提交计划 |
| GET | `/api/planner/plans` | 全部版本号 |
| GET | `/api/planner/plans/{version}` | 指定版本计划 |
| GET | `/api/planner/diff?from=&to=` | 两版本差异 |
| GET | `/api/planner/entries/{entry}/load-set` | 入口实际加载集合 |

变更操作 JSON 形如 `{"type":"addImport","importer":"a","imported":"b"}`，
`type` 取值：`upsertModule / removeModule / addEntry / removeEntry / addImport / removeImport`。

## 配置项（前缀 `planner`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `planner.budget-max-modules` | 0 | 首屏 EAGER 模块数上限；≤0 不限制 |
| `planner.budget-max-bytes` | 0 | 首屏 EAGER 总体积（字节）上限；≤0 不限制 |
| `planner.shared-chunk-prefetch` | false | true 时共享包标记为 PREFETCH 而非 EAGER |
| `planner.data-dir` | `data/plans` | 计划与图状态持久化目录 |

## 加载时机定义

| 时机 | 含义 |
|---|---|
| EAGER | 随首屏立即加载，计入预算 |
| PREFETCH | 首屏之后提前预取，不计入预算 |
| LAZY | 按需懒加载（预算降级或依赖环产物），不计入预算 |
