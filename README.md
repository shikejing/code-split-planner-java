# code-split-planner-java

资源分包与按需加载策略服务（Spring Boot / Java 17）。

上层提交模块依赖图，服务生成确定性的分包方案与加载计划：
首屏立即加载（EAGER）、按需懒加载（LAZY）、提前预取（PREFETCH）。

详细的分包与加载语义、边界行为、配置项与 REST 接口见 [PLANNING.md](PLANNING.md)。

```bash
mvn test            # 运行测试
mvn spring-boot:run # 启动服务
```
