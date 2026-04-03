# Contributing to Asmer

## 目录

- [分支策略](#分支策略)
- [Commit 规范](#commit-规范)
- [TDD 工作流](#tdd-工作流)
- [测试要求](#测试要求)
- [Backlog](#backlog)

---

## 分支策略

### 主干

| 分支 | 说明 |
|------|------|
| `main` | 始终可发布；不允许直接 push 代码 |

### 工作分支命名

```
<type>/<scope>/<topic>
```

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `test` | 补充测试，不含新实现 |
| `refactor` | 重构，不改变行为 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `chore` | 构建/依赖/配置 |

| scope | 对应模块 |
|-------|---------|
| `core` | asmer-core |
| `spring` | asmer-spring-boot-starter |
| `caffeine` | asmer-cache-caffeine |
| `redis` | asmer-cache-redis |
| `demo` | demo |

**示例**

```bash
git checkout -b test/core/fetchers
git checkout -b feat/core/async-api
git checkout -b fix/spring/global-default-race
```

### 生命周期

```
main
 └── feat/core/async-api        # 开分支
      ├── commit: test(core): RED - assembleAsync returns future
      ├── commit: feat(core): GREEN - implement assembleAsync
      └── commit: refactor(core): extract async helper   # → merge to main
```

每个分支聚焦单一任务；完成即 merge，不积压。

---

## Commit 规范

遵守 [Conventional Commits 1.0](https://www.conventionalcommits.org/)。

### 格式

```
<type>(<scope>): <简短描述>

[可选 body]

[可选 footer]
```

### 规则

- 标题行不超过 72 字符
- 使用祈使句（"add"，不是 "added"）
- body 说明**为什么**，而不是**做了什么**
- 破坏性变更在 footer 标注 `BREAKING CHANGE:`

### 示例

```
test(core): cover Fetchers.parallel with concurrent assertions

Verifies that parallel fetcher fans out to N goroutines and
collects results without data races.
```

```
feat(core): add assembleAsync returning CompletableFuture<Void>

Allows callers to integrate assembly into reactive pipelines
without blocking the calling thread.
```

```
fix(spring): prevent race on AsmerConfig.setGlobalDefault

BREAKING CHANGE: globalDefault() is now AtomicReference; direct
field access removed.
```

---

## TDD 工作流

每个任务严格遵循 **RED → GREEN → REFACTOR → COMMIT**。

### Step 1 — RED：先写失败测试

```bash
git checkout -b test/core/fetchers   # 或 feat/...

# 编写测试，不写实现
mvn test -pl asmer-core              # 确认红色
```

测试必须因为**被测逻辑缺失或不正确**而失败，而不是因为编译错误。

### Step 2 — GREEN：最少代码让测试通过

```bash
# 只写让当前测试通过的最少代码
mvn test -pl asmer-core              # 确认绿色
```

不允许在此阶段做额外设计或"顺手优化"。

### Step 3 — REFACTOR：清理

```bash
# 消除重复、改善可读性，行为不变
mvn test -pl asmer-core              # 保持绿色
```

### Step 4 — COMMIT：测试与实现同提交

```bash
git add -p
git commit -m "test(core): cover Fetchers.parallel

Asserts fan-out, result collection, and null-key filtering."

# 如果有实现代码一起提交：
git commit -m "feat(core): implement Fetchers.parallel via ForkJoinPool"
```

测试先于或与实现同一 commit，禁止实现在测试之前单独提交。

### Merge checklist

```bash
mvn test                             # 所有模块全绿
git log --oneline main..HEAD         # 审查提交历史
git checkout main
git merge --no-ff feat/xxx           # 保留分支拓扑
git branch -d feat/xxx
```

---

## 测试要求

### 覆盖规则

| 类型 | 要求 |
|------|------|
| 公共 API 新方法 | 必须有对应单元测试 |
| 内部实现（`internal`） | 通过公共 API 间接覆盖即可 |
| Bug 修复 | 必须有能重现该 bug 的回归测试 |
| 重构 | 不允许删除或降低已有测试覆盖 |

### 测试命名规范

```java
// 格式：<被测行为>_<前置条件或输入>_<预期结果>
void parallel_singleKeyFetch_fansOutToAllKeys()
void withTimeout_exceedsDeadline_throwsAssemblyException()
void globalDefault_setByAutoConfig_usedByOfNoArg()
```

### 测试分层

| 层 | 位置 | 特征 |
|----|------|------|
| Unit | `asmer-core/src/test` | 无 Spring，无 IO，毫秒级 |
| Integration | `asmer-spring-boot-starter/src/test` | Spring context，SQLite |
| Cache | `asmer-cache-*/src/test` | 对应缓存依赖 |

---

## Backlog

### 当前状态

```
Sprint 1 [进行中]   — 补齐测试覆盖
Sprint 2 [待开始]   — Redis 模块
Sprint 3 [待开始]   — 可观测性
Sprint 4 [待开始]   — 异步 API
```

---

### Sprint 1 — 测试覆盖补齐

**目标**：消灭所有已实现但未测试的代码路径。

| ID | 分支 | 任务 | 状态 |
|----|------|------|------|
| S1-1 | `test/core/fetchers` | `Fetchers.parallel` / `sequential` / `fromMap` 全路径 | DONE (394005f) |
| S1-2 | `test/core/chained-cache` | `ChainedCache` L1命中、L2命中、L2促进、写穿透、evict | DONE (70febac) |
| S1-3 | `test/core/concurrency-advanced` | `Concurrency.withTimeout` 超时抛异常；`perCall` 每次新建池 | DONE (668d4b3) |
| S1-4 | `test/core/global-default` | `setGlobalDefault` 影响 `of()` 无参；Spring 启动后自动设置 | DONE (fdbf7d4) |

**完成标准**：`mvn test` 全绿，上述四个分支全部 merge。

---

### Sprint 2 — Redis 模块

**目标**：`asmer-cache-redis` 达到与 `asmer-cache-caffeine` 同等质量。

| ID | 分支 | 任务 | 状态 |
|----|------|------|------|
| S2-1 | `test/redis/integration` | 审查 `RedisCache` 实现；本地 Redis 集成测试（18 tests） | DONE (629a2d5) |
| S2-2 | `fix/redis/<topic>` | 修复审查中发现的问题 | TODO |

**完成标准**：`RedisCache` 覆盖 get/put/getAll/putAll/evict；pipeline/批量操作正确。

---

### Sprint 3 — 可观测性

**目标**：让使用方能观察 assembly 的执行情况。

| ID | 分支 | 任务 | 状态 |
|----|------|------|------|
| S3-1 | `feat/core/metrics-spi` | `AssemblyListener` SPI：rule 执行耗时、批量大小、缓存命中率 | TODO |
| S3-2 | `feat/spring/metrics-actuator` | Spring Boot Actuator 集成，暴露 Micrometer 指标 | TODO |

**完成标准**：SPI 有单元测试；Actuator 集成有 Spring Boot Test 验证。

---

### Sprint 4 — 异步 API

**目标**：支持非阻塞调用场景。

| ID | 分支 | 任务 | 状态 |
|----|------|------|------|
| S4-1 | `feat/core/async-api` | `Asmer.assembleAsync()` 返回 `CompletableFuture<Void>` | TODO |
| S4-2 | `test/core/async-api` | 并发安全、异常传播、取消语义测试 | TODO |

**完成标准**：async API 覆盖正常路径、异常路径、超时取消。

---

### 更新 Backlog

完成一个任务后，将对应行的 `TODO` 改为 `DONE`，并记录完成的 commit hash：

```markdown
| S1-1 | `test/core/fetchers` | ... | DONE (abc1234) |
```
