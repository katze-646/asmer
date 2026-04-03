# Asmer — Product Backlog

> 本文件是项目唯一的需求优先级列表。
> CONTRIBUTING.md 定义**工作流规范**，本文件定义**做什么、为什么、做到什么程度**。

---

## 状态图例

| 标记 | 含义 |
|------|------|
| ✅ DONE | 已合并到 main，测试覆盖完整 |
| 🚧 IN PROGRESS | 当前 Sprint 正在进行 |
| 📋 TODO | 已分析，待排期 |
| 💡 IDEA | 待评估，尚未承诺 |

---

## Sprint 总览

| Sprint | 主题 | 状态 |
|--------|------|------|
| S1 | 测试覆盖补齐 | ✅ 全部完成 |
| S2 | Redis 模块 | ✅ 全部完成 |
| S3 | 可观测性 SPI | ✅ 全部完成 |
| S4 | 异步 API + 自定义缓存 SPI 文档 | 🚧 进行中 |
| S5 | 发布准备 | 💡 规划中 |

---

## ✅ Sprint 1 — 测试覆盖补齐

**目标**：消灭所有已实现但未测试的路径，建立回归基线。

| ID | 分支 | 描述 | Commit |
|----|------|------|--------|
| S1-1 | `test/core/fetchers` | `Fetchers.parallel` / `sequential` / `fromMap` 全路径 | 394005f |
| S1-2 | `test/core/chained-cache` | `ChainedCache` L1命中、L2命中、促进、写穿透、evict | 70febac |
| S1-3 | `test/core/concurrency-advanced` | `withTimeout` 超时抛异常；`perCall` 池生命周期 | 668d4b3 |
| S1-4 | `test/core/global-default` | `setGlobalDefault` → `Asmer.of()` 无参自动拿 YAML 配置 | fdbf7d4 |

---

## ✅ Sprint 2 — Redis 模块

**目标**：`asmer-cache-redis` 达到与 `asmer-cache-caffeine` 同等质量。

| ID | 分支 | 描述 | Commit |
|----|------|------|--------|
| S2-1 | `test/redis/integration` | 本地 Redis DB-1，18 tests：factory / get/put / batch / evict | 629a2d5 |
| S2-2 | `fix/redis/evict-scan` | `evict()` 用 SCAN 替换 KEYS，避免生产阻塞 | 08faa81 |

---

## ✅ Sprint 3 — 可观测性

**目标**：让调用方能观察 assembly 执行情况，对接 Micrometer。

| ID | 分支 | 描述 | Commit |
|----|------|------|--------|
| S3-1 | `feat/core/metrics-spi` | `AssemblyEvent` record + `AssemblyListener` SPI，11 tests | 5520219 |
| S3-2 | `feat/spring/metrics-actuator` | `MicrometerAssemblyListener` 自动配置，`@ConditionalOnBean`，5 tests | 48012b8 |

**已暴露指标**

| 指标名 | 类型 | 标签 | 含义 |
|--------|------|------|------|
| `asmer.rule` | Timer | `rule`, `success` | 每条 rule 执行耗时（隐含调用次数） |

---

## 📋 Sprint 4 — 异步 API

**目标**：支持非阻塞调用，让 assembly 可以融入 reactive / virtual-thread 管道。

**背景**：当前 `assemble()` 同步阻塞调用线程。
在 WebFlux / Loom 场景下，调用方希望拿到一个 `CompletableFuture<Void>`，
自行决定何时 `join()` 或链式组合多个 future。

### 任务列表

| ID | 分支 | 描述 | 优先级 | Commit |
|----|------|------|--------|--------|
| S4-1 | `feat/core/async-api` | `Asmer.assembleAsync()` 返回 `CompletableFuture<Void>` | P0 | 02691a8 |
| S4-2 | — | 测试随 S4-1 同步提交（8 tests in AsmerAsyncTest） | P0 | 02691a8 |
| S4-3 | `docs/core/custom-cache-guide` | 自定义缓存 SPI 示例：Javadoc 补全 + Demo 端点 | P1 | 🚧 |

### S4-3 详情 — 自定义缓存 SPI 示例

**背景**：`AsmerCache` 接口已完整（`get / put / getAll / putAll / evict`），
但缺少面向外部开发者的官方示例，导致"如何接入自定义缓存"不够显眼。

**交付物**：

| 交付 | 说明 |
|------|------|
| Javadoc 补全 | 在 `AsmerCache` 接口顶部用 `@implSpec` 标注实现契约（线程安全、null 语义、namespace 隔离） |
| Demo 端点 | `GET /orders/custom-cache` — 用匿名内部类实现 `AsmerCache`（`ConcurrentHashMap` 存储），展示两次调用命中率的变化 |
| 完成标准 | `mvn test` 全绿；Demo 响应中包含 `cacheHits` 字段变化 |

---

### 设计约束

- `assembleAsync()` 不引入新的线程池；使用调用方传入的 `Concurrency` 策略
- 异常语义与 `assemble()` 一致（通过 `CompletableFuture.completeExceptionally` 传递）
- `AssemblyListener` 在 future 完成前触发（与同步版本行为一致）

### 完成标准

```java
CompletableFuture<Void> f = Asmer.of(orders)
        .on(Order::getUser, userRepo::findByIdIn, User::getId)
        .assembleAsync();
f.join(); // 或与其他 future 组合
```

- `mvn test` 全绿
- 异常通过 `CompletionException` 正确传递
- 与 `ErrorPolicy` 行为一致

---

## 💡 Sprint 5 — 发布准备（规划中）

**目标**：让 Asmer 可以作为正式库发布，供外部项目使用。

| ID | 描述 | 优先级 |
|----|------|--------|
| S5-1 | 语义版本化：`1.0.0-SNAPSHOT` → `1.0.0` | P0 |
| S5-2 | 发布到 Maven Central（`sonatype` staging） | P0 |
| S5-3 | Javadoc 完整覆盖公共 API | P1 |
| S5-4 | BOM 模块：`asmer-bom`，统一版本管理 | P1 |
| S5-5 | GitHub Actions CI：`push` 触发 `mvn test` | P2 |

---

## 💡 未来特性池（未承诺）

以下为收集到的想法，尚未评估优先级和工作量。

| 特性 | 描述 | 潜在影响 |
|------|------|---------|
| Kotlin 扩展 | `orders.assemble { on(...) }` DSL | 开发体验 |
| 注解处理器 | 编译期校验 `@AssembleOne` / `@AssembleMany` 字段合法性 | 安全性 |
| `assembleAll()` | 多种实体类型批量组装 | 使用便利性 |
| 限流支持 | Loader 调用频率限制（防止下游过载） | 稳定性 |
| Redis pipeline | `putAll` 改用 pipeline 批量写入，减少 RTT | 性能 |
| 追踪集成 | `AssemblyListener` → OpenTelemetry Span | 可观测性 |

---

## 维护规则

1. 每完成一个任务，将状态改为 ✅ 并记录 commit hash
2. 新增需求先进「未来特性池」，评估后再提升为正式 Sprint
3. Sprint 内任务顺序即优先级，不得跳过
4. 本文件随每次 merge 同步更新
