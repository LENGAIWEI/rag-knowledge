# 1. Spring 核心知识库
## 1. Spring 概述
Spring 是一个**轻量级 Java 开发框架**，核心两大思想：**IOC 控制反转、AOP 面向切面**。
核心模块：Core Container（核心容器）、AOP、Aspects、JDBC、ORM、Web、Test。
## 2. IOC（控制反转 Inversion of Control）
- 定义：**将对象创建、依赖管理交给 Spring 容器，而非开发者 new 对象**，控制权反转。
- DI 依赖注入：IOC 的实现手段，容器自动给 Bean 注入依赖。
- 好处：解耦、便于单元测试、统一管理对象生命周期。
### 注入方式
1. 构造器注入（推荐，Spring4.3 后推荐，不可变对象）
2. Setter 注入
3. 字段注入 @Autowired（不推荐，单元测试困难）
## 3. Bean 相关
### Bean 作用域
- singleton：单例（默认，容器创建时实例化，整个容器一个对象）
- prototype：多例，每次获取 new 新对象
- request：web，一次请求一个 bean
- session：web，一次会话一个 bean
### Bean 生命周期
实例化 → 属性填充 → 初始化（BeanPostProcessor 前置→afterPropertiesSet→init-method→BeanPostProcessor 后置） → 使用 → 销毁（destroy-method）
>
> 完整流程：扫描 class→BeanDefinition→实例化对象→填充属性→初始化→放入单例池。
### Bean 循环依赖
多个 Bean 互相引用（A 依赖 B，B 依赖 A）。
- SpringBoot2 默认支持，**三级缓存解决**：
singletonObjects（成品单例池）、earlySingletonObjects（半成品 Bean）、singletonFactories（对象工厂）
- SpringBoot3 默认关闭循环依赖，优先重构代码。
>
> 限制：仅支持**单例、setter 注入**；构造器注入循环依赖无法解决。
## 4. AOP 面向切面编程
- 核心：在不修改原有业务代码的前提下，增强方法功能（日志、权限、事务、埋点）
- 术语
- 连接点 JoinPoint：可以被增强的方法
- 切入点 Pointcut：匹配要增强的连接点
- 通知 Advice：增强逻辑
- 切面 Aspect = 切入点 + 通知
- 目标对象 Target：原始对象
- 代理 Proxy：AOP 生成的代理对象
### 通知类型
1. @Before 前置通知：方法执行前
2. @AfterReturning 返回通知：方法正常返回后
3. @AfterThrowing 异常通知：方法抛出异常
4. @After 最终通知：无论正常 / 异常都会执行
5. @Around 环绕通知（最强，手动控制目标方法执行）
### AOP 代理方式
- JDK 动态代理：目标**实现接口**，代理实现接口
- CGLIB 代理：目标**无接口**，继承目标类生成子类代理
>
> Spring5 + 默认优先 CGLIB
## 5. Spring 事务底层
事务本质：数据库事务，Spring 只是对事务做统一管理。
核心接口：PlatformTransactionManager 事务管理器。
### @Transactional
生效前提：
1. 方法 public
2. 外部对象调用（同类 this 调用，AOP 代理失效，事务无效）
3. 数据库支持事务（InnoDB）
### 传播行为（7 种）
1. REQUIRED (默认)：有事务就加入，没有新建事务
2. REQUIRES_NEW：新建独立事务，挂起原有
3. SUPPORTS：有就加入，没有就非事务执行
4. NOT_SUPPORTED：不支持事务，挂起当前事务
5. MANDATORY：必须存在事务，否则抛异常
6. NEVER：不能存在事务，存在抛异常
7. NESTED：嵌套事务，保存点，子事务回滚不影响外层
### 隔离级别
对应数据库隔离级别：DEFAULT、READ_UNCOMMITTED、READ_COMMITTED、REPEATABLE_READ、SERIALIZABLE
### 回滚规则
默认只对**RuntimeException、Error**回滚；普通 Checked 异常不会回滚，可通过 rollbackFor 指定。
## 6. Spring 核心注解
- @Component / @Service / @Repository / @Controller：注册 Bean
- @Configuration：配置类
- @Bean：注册第三方对象到容器
- @Autowired：按类型注入
- @Qualifier：按名称匹配
- @Primary：优先 Bean
- @Value：读取配置
- @Scope：指定 Bean 作用域
- @Lazy：懒加载，第一次获取 Bean 才实例化
## 7. 高频面试问答
Q：IOC 和 DI 区别？
A：IOC 是思想，DI 是实现手段，IOC 是控制权反转，DI 自动注入依赖。
Q：AOP 两种代理区别？
A：JDK 代理需要目标实现接口；CGLIB 继承类生成代理。
Q：@Transactional 失效场景？
A：非 public、同类内部调用、捕获异常不抛出、数据库引擎不支持事务。
---
