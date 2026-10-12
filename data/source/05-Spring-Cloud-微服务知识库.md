#5. Spring Cloud 微服务知识库
## 1. 微服务概念
单体拆分成多个独立小服务，独立部署、独立数据库，通过网络远程调用。
Spring Cloud：一套微服务规范，整合各类组件，基于 SpringBoot 开发微服务。
## 2. 核心组件
1. 注册中心 Nacos/Eureka/Consul：服务注册、服务发现，保存服务实例地址
2. 配置中心 Nacos/Config：统一管理配置，动态刷新配置
3. 远程调用 OpenFeign：声明式 http 远程调用
4. 负载均衡 LoadBalancer：客户端负载均衡，替代旧 Ribbon
5. 网关 Gateway：API 网关，统一入口，路由、鉴权、限流
6. 熔断降级 Sentinel/Hystrix：服务容错，防止雪崩
## 3. 核心概念
- 服务注册：服务启动，把 ip 端口注册到注册中心
- 服务发现：消费者从注册中心拉取服务列表
- 客户端负载均衡：本地选择实例，再发起调用（区别 Nginx 服务端 LB）
- 服务雪崩：一个服务故障层层传递，导致整个系统瘫痪；熔断降级隔离保护
## 4. Gateway 网关
基于 WebFlux 响应式编程；路由 Route + 断言 Predicate + 过滤器 Filter。
作用：请求路由、权限校验、限流、日志、跨域。
## 5. OpenFeign
声明式远程调用，写接口加注解，像调用本地方法一样调用远程微服务，底层 http。
配合 LoadBalancer 做负载均衡。
## 6. 服务容错 Sentinel
核心：熔断、降级、热点参数限流、系统保护。
- 限流：控制请求速率
- 降级：服务慢 / 异常，返回兜底结果
- 熔断：失败比例达到阈值，直接快速失败，不再调用远程服务
## 7. 微服务问题
分布式事务、接口幂等、分布式锁、分布式 Session、链路追踪 SkyWalking。
## 8. 面试问答
Q：Eureka 和 Nacos 区别？
A：Eureka AP 模型；Nacos 支持 AP/CP 切换，同时支持注册中心 + 配置中心。
Q：Gateway 和 Zuul 区别？
A：Zuul1 阻塞 IO；Gateway 基于 WebFlux 非阻塞，性能更高。
---
