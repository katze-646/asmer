# Asmer — AI 工作指令

本文件由 Claude 在所有会话中自动加载，定义了本项目的强制工作规范。

## 必读文档

详细规范见 [CONTRIBUTING.md](CONTRIBUTING.md)。本文件仅列出 AI 必须严格遵守的执行规则。

---

## 强制工作规范

### 1. 任何代码变更前，必须先创建 branch

```bash
git checkout -b <type>/<scope>/<topic>
```

禁止直接在 `main` 上写代码。

### 2. 任何新功能/修复，必须先写失败测试（TDD）

顺序：**RED → GREEN → REFACTOR → COMMIT**

- 先运行 `mvn test` 确认测试是红色的
- 再写最少实现让测试变绿
- 再 refactor
- 最后 commit（测试和实现在同一个 commit）

禁止先写实现，再补测试。

### 3. Commit 必须遵守 Conventional Commits

```
<type>(<scope>): <简短描述>
```

- type: `feat` | `fix` | `test` | `refactor` | `perf` | `docs` | `chore`
- scope: `core` | `spring` | `caffeine` | `redis` | `demo`

### 4. Merge 前必须全绿

```bash
mvn test   # 所有模块
```

禁止带失败测试 merge 到 main。

### 5. 按 Backlog 顺序执行

当前 Backlog 见 [CONTRIBUTING.md](CONTRIBUTING.md#backlog)。
不能跳过当前 Sprint 去做低优先级任务，除非用户明确指示。

### 6. 每次 merge 后更新 memory

完成一个任务后，同步更新 `/Users/kayz/.claude/projects/.../memory/` 中的相关记录。

---

## 模块结构

```
asmer-core                   核心引擎，无 Spring 依赖
asmer-spring-boot-starter    Spring Boot 自动配置
asmer-cache-caffeine         Caffeine 本地缓存适配
asmer-cache-redis            Redis 分布式缓存适配
demo                         演示应用（独立 Maven 项目）
```

## 包结构（asmer-core）

```
com.kayz.asmer               公共 API（接口、枚举、异常、配置）
com.kayz.asmer.annotation    @AssembleOne / @AssembleMany
com.kayz.asmer.internal      实现细节（对外不可见）
```

## 关键约定

- 异常全部继承 `AsmerException`（`RuntimeException` 子树）
- `RuleDefinitionException` 不受 `ErrorPolicy` 影响，始终抛出
- `internal` 包内的类虽然 `public`，对外视为私有 API，不得在框架外直接引用
- `AsmerConfig.globalDefault()` 由 Spring auto-configuration 在启动时设置，非 Spring 环境保持 `DEFAULT`
