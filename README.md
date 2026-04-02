# Asmer

Asmer 是一个用于在应用层“组装数据”、缓解 N+1 查询问题的轻量框架。

## 模块

- `asmer-core`：核心组装引擎与 API
- `asmer-cache-caffeine`：基于 Caffeine 的本地缓存实现
- `asmer-cache-redis`：Redis 缓存实现（可选依赖）
- `asmer-spring-boot-starter`：Spring Boot 自动配置与 JPA 辅助能力
- `demo`：示例应用（Spring Boot + JPA + SQLite in-memory）

## 构建

要求：JDK 17+

```bash
mvn -q -DskipTests=false test
```

## 运行 demo

`demo` 为独立的 Spring Boot 工程（使用了本仓库的 `1.0-SNAPSHOT` 依赖）。

```bash
cd demo
./mvnw spring-boot:run
```

默认端口：`8081`（见 `demo/src/main/resources/application.yaml`）。

## License

MIT License，详见 `LICENSE`。

