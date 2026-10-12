## 一、SpringBoot 核心基础
### 1. 什么是 SpringBoot
SpringBoot 是基于 Spring 框架的**快速开发脚手架**，核心目标：**简化 Spring 应用搭建与配置，自动装配，开箱即用**。
核心特点：
1. 自动装配 AutoConfiguration
2. 起步依赖 Starter（简化 Maven/Gradle 依赖管理）
3. 内嵌 Web 容器（Tomcat/Jetty/Undertow，无需单独部署 war）
4. 无 XML 配置，注解驱动
5. 内置健康检查、指标监控（Actuator）
>
> 核心口号：**约定大于配置**
### 2. SpringBoot 版本说明
- 2.x：基于 Spring5，JDK8+，广泛商用稳定版本
- 3.x：基于 Spring6，JDK17+，Jakarta EE（包名 javax.* → jakarta.*）
>
> 重点：SpringBoot3 不再支持 javax.servlet，全部改为 jakarta
### 3. 核心注解
表格
| 注解 | 作用 |
| --- | --- |
| `@SpringBootApplication` | 主启动类，复合注解 |
| `@Configuration` | 标记配置类，替代 xml |
| `@Bean` | 在配置类中注册对象到 IOC 容器 |
| `@Component` | 通用组件，交给 Spring 管理 |
| `@Controller` | web 控制器，返回页面 |
| `@RestController` | `@Controller+@ResponseBody`，返回 JSON 接口 |
| `@Service` | 业务层 |
| `@Repository` | 数据层 |
| `@Autowired` | 自动注入 IOC 对象，默认 byType |
| `@Qualifier("beanName")` | 配合 @Autowired，按名称注入 |
| `@Value("${key}")` | 读取配置文件单个属性 |
| `@ConfigurationProperties(prefix="xxx")` | 批量绑定配置到实体类 |
| `@ConditionalOnClass` | 自动装配条件：类存在才装配 |
>
> `@SpringBootApplication` 由 3 个注解组成：
> `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`
>
>
> - `@EnableAutoConfiguration`：开启自动装配，读取 META-INF/spring.factories（SpringBoot2）
> - SpringBoot3 使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
### 4. Starter 起步依赖
Starter 本质是**一组预整合的 Maven 依赖集合**，不用自己手动管理版本冲突。
常用 starter：
```
<!-- web开发，内嵌tomcat -->
spring-boot-starter-web
<!-- mybatis整合 -->
mybatis-spring-boot-starter
<!-- 数据库驱动，mysql8 -->
mysql-connector-java / mysql-connector-j(springboot3)
<!-- 测试 -->
spring-boot-starter-test
<!-- redis -->
spring-boot-starter-data-redis
<!-- 监控端点 -->
spring-boot-starter-actuator
<!-- 配置文件可视化，开发用 -->
spring-boot-devtools
```
**版本管理**：父工程 `spring-boot-starter-parent` 统一管理所有依赖版本，子模块不用写 version。
## 二、配置文件（application.yml/application.properties）
### 1. 两种格式
- application.properties：key=value
```
server.port=8080
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/db
```
- application.yml（yaml，推荐，层级清晰，**冒号后面必须空格**）
```
server:
port: 8080
spring:
datasource:
url: jdbc:mysql://127.0.0.1:3306/db
username: root
password: 123456
```
>
> 加载优先级：`properties > yml > yaml`；同 key 前面覆盖后面。
### 2. 多环境配置
命名规则：`application-{profile}.yml`
例：
- application-dev.yml 开发环境
- application-test.yml 测试环境
- application-prod.yml 生产环境
激活方式：
```
# 在application.yml中指定
spring:
profiles:
active: dev
```
### 3. 配置读取两种方式
1. @Value 单个读取
```
@Value("${spring.datasource.username}")
private String username;
```
2. @ConfigurationProperties 批量绑定（推荐）
```
@Component
@ConfigurationProperties(prefix = "spring.datasource")
public class DataSourceConfig {
private String url;
private String username;
// getter/setter
}
```
## 三、自动装配原理（高频面试）
1. 主启动类`@EnableAutoConfiguration`触发自动装配
2. SpringBoot 读取自动配置文件，加载**AutoConfiguration 自动配置类**
3. 配置类上大量`@ConditionalXXX`条件注解：**满足条件才创建 Bean**
- @ConditionalOnClass：class 存在才生效
- @ConditionalOnMissingBean：容器没有这个 Bean，才创建
4. 自动配置类中通过 @Bean 注入组件
5. 用户自定义 Bean 优先级 > 自动装配 Bean（用户覆盖默认配置）
一句话总结：**根据 classpath 中存在的依赖，自动创建对应的 Bean，没有则不装配**
>
> 自定义 starter：编写 AutoConfiguration + META-INF 导入配置
## 四、Web 开发（SpringMVC 自动配置）
### 1. Web 核心组件
- DispatcherServlet：前端控制器，SpringBoot 自动注册，默认路径 `/`
- 静态资源访问：默认目录 `classpath:/static、/public、/resources、/META-INF/resources`
>
> 访问：`http://localhost:8080/xxx.jpg` 直接读取 static 下文件
### 2. 请求接收
1. 路径变量 `@PathVariable`
```
@GetMapping("/user/{id}")
public User get(@PathVariable Long id){}
```
2. 请求参数 `@RequestParam`
```
@GetMapping("/user")
public User get(@RequestParam String name){}
```
3. JSON 请求体 `@RequestBody`（POST，application/json）
```
@PostMapping("/add")
public Result add(@RequestBody User user){}
```
4. 表单对象直接接收：不需要注解，参数名和表单字段一致
### 3. 全局异常处理器
`@RestControllerAdvice` + `@ExceptionHandler`，统一捕获异常，返回统一 JSON，避免页面报错
```
@RestControllerAdvice
public class GlobalExceptionHandler {
@ExceptionHandler(Exception.class)
public Result error(Exception e){
return Result.fail("服务器异常");
}
}
```
### 4. 跨域 CORS
方式 1：注解 `@CrossOrigin` 加到 Controller
方式 2：全局 CORS 配置，实现 WebMvcConfigurer，重写 addCorsMappings
### 5. WebMvc 配置
新建配置类 `@Configuration` 实现 `WebMvcConfigurer`
可自定义：拦截器、跨域、视图控制器、消息转换器（统一日期格式化）
>
> 注意：不要加`@EnableWebMvc`，加了会**完全接管 MVC，SpringBoot Web 自动配置失效**
### 6. 拦截器 HandlerInterceptor
1. 自定义拦截器实现 HandlerInterceptor
2. 在 WebMvcConfigurer 中注册 `addInterceptors()`
3. 配置拦截路径 `addPathPatterns`、排除路径 `excludePathPatterns`
## 五、数据访问层
### 1. MyBatis 整合 SpringBoot
依赖：`mybatis-spring-boot-starter` + mysql 驱动
yml 配置：
```
mybatis:
mapper-locations: classpath:mapper/*.xml
type-aliases-package: com.xxx.entity
```
Mapper 接口上加 `@Mapper`，或者启动类 `@MapperScan("com.xxx.mapper")`（推荐，批量扫描）
### 2. MyBatis-Plus（常用增强）
starter：`mybatis-plus-boot-starter`
特性：CRUD 封装、分页插件、条件构造器 QueryWrapper、逻辑删除、自动填充（创建时间、更新时间）
### 3. JdbcTemplate / Spring Data JPA
JPA 适合快速开发，ORM 模型；MyBatis 更灵活，适合复杂 SQL 业务
### 4. 事务管理
- `@Transactional` 注解，加在方法 / 类上，SpringBoot 自动装配事务管理器
- 生效前提：方法**public**；调用来自外部对象（同类内 this 调用事务失效）
- 传播行为、隔离级别、回滚规则可注解配置
## 六、Redis & 缓存
starter：`spring-boot-starter-data-redis`
底层 SpringBoot2 默认 Lettuce；SpringBoot3 同样 Lettuce
### Spring Cache 缓存注解（整合 Redis）
- `@Cacheable` 查询：缓存有直接返回，没有执行方法存入缓存
- `@CachePut` 更新：执行方法，更新缓存
- `@CacheEvict` 删除：清除缓存
- `@Caching` 组合多个缓存操作
## 七、Actuator 监控
starter：`spring-boot-starter-actuator`
作用：暴露端点，查看应用健康、内存、bean、日志、环境变量
默认端点：`/actuator/health`健康检查
开启所有端点（开发）
```
management:
endpoints:
web:
exposure:
include: "*"
```
## 八、常用高级特性
### 1. 热部署 devtools
依赖 `spring-boot-devtools`
修改代码自动重启应用；**生产环境务必移除**
### 2. 定时任务 @Scheduled
1. 启动类添加 `@EnableScheduling`
2. 方法上 `@Scheduled(cron = "0 0 2 * * ?")`
>
> 注意：定时任务默认单线程，多任务阻塞，需要配置线程池
### 3. 异步任务 @Async
1. 启动类 `@EnableAsync`
2. 方法加`@Async`，方法异步执行，新开线程
>
> 同类 this 调用异步失效
### 4. 自定义事件监听
Spring 事件机制：ApplicationEvent、ApplicationListener，解耦业务（例如注册成功发送消息）
## 九、SpringBoot 项目打包部署
1. Maven 打包 `mvn clean package`
2. 得到 jar 包，内置 tomcat
```
java -jar demo.jar
# 指定环境
java -jar demo.jar --spring.profiles.active=prod
# 后台运行linux
nohup java -jar demo.jar > log.out 2>&1 &
```
>
> 打 war 包：修改打包类型，排除内嵌 tomcat，放到外部 tomcat（现在很少用）
## 十、常见坑 & 问题库
1. `@Transactional` 失效场景：
- 方法不是 public
- 同类内部 this 调用
- 捕获异常没有抛出（默认只回滚 RuntimeException）
- 数据库引擎不支持事务（MyISAM）
2. @Async / @Scheduled 同类调用失效：本质 AOP 动态代理失效
3. yml 缩进错误，冒号后面缺少空格导致配置读取失败
4. SpringBoot3 javax.*包报错 → 替换 jakarta.*
5. 静态资源访问不到：路径写错，或自定义 WebMvc 配置覆盖资源映射
6. 自动装配不生效：缺少 starter 依赖，条件注解不满足
7. 循环依赖：SpringBoot2 默认允许，SpringBoot3 默认禁止，优先重构代码
## 十一、面试精简问答（可单独抽出来放进 RAG）
Q1：SpringBoot 自动装配原理？
A：通过 @EnableAutoConfiguration 加载自动配置类，借助条件注解，根据 classpath 存在的依赖自动创建 Bean，简化 xml 配置。
Q2：starter 是什么？
A：一组预整合的依赖包，统一版本，引入 starter 即引入开发需要的全套依赖。
Q3：@SpringBootApplication 由哪些注解组成？
A：@SpringBootConfiguration、@EnableAutoConfiguration、@ComponentScan。
Q4：@ConfigurationProperties 和 @Value 区别？
A：@Value 单个属性读取；@ConfigurationProperties 批量绑定，支持校验、复杂对象，适合配置类。
Q5：内嵌容器有哪些？
A：Tomcat (默认)、Jetty、Undertow。
Q6：SpringBoot 如何做全局异常？
A：@RestControllerAdvice + @ExceptionHandler，统一捕获异常返回 JSON。
## 十二、目录索引（RAG 检索用）
1. SpringBoot 基础概念与特点
2. 核心注解大全
3. Starter 起步依赖
4. 配置文件、多环境、配置读取
5. 自动装配原理
6. Web 开发：请求、拦截器、全局异常、跨域、WebMvc
7. 数据访问 MyBatis/MyBatisPlus、事务
8. Redis + SpringCache 缓存
9. Actuator 应用监控
10. 高级特性：异步、定时、事件
11. Jar 包打包部署
12. 常见踩坑集合
13. 面试问答集
