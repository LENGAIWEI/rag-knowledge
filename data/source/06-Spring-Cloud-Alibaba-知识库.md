#6. Spring Cloud Alibaba 知识库
## 1. 概述
Spring Cloud Alibaba 是阿里提供的微服务实现，是 Spring Cloud 规范的实现，国内项目使用广泛。
核心组件：
- Nacos：注册中心 + 配置中心（最核心）
- Sentinel：流量控制、熔断降级
- Dubbo：RPC 远程调用（替代 Feign，性能更高）
- Seata：分布式事务
- RocketMQ：消息队列
## 2. Nacos
- 注册中心：AP 模型，适合服务发现；支持临时实例 / 持久实例
- 配置中心：动态配置推送，@RefreshScope 实现配置动态刷新
- 命名空间 namespace：隔离环境 dev/test/prod；分组 group 隔离业务
## 3. Sentinel
流量防护：限流、熔断降级、热点限流、系统保护、授权规则。
支持注解 @SentinelResource 定义资源，配置兜底方法。
## 4. Dubbo
高性能 RPC 框架，TCP 协议，相比 Feign (http) 性能更好。
提供者 Provider、消费者 Consumer，注册到 Nacos。
## 5. Seata 分布式事务
解决微服务跨库事务问题，四种模式：AT、TCC、SAGA、XA。AT 模式无侵入，业务最常用。
## 6. 面试问答
Q：SpringCloud 和 SpringCloudAlibaba 关系？
A：SpringCloud 是一套规范，SpringCloudAlibaba 是这套规范的阿里实现。
---
