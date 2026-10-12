#4. Spring Security 安全框架知识库
## 1. Spring Security 概述
Spring 安全框架，提供**认证、授权、防攻击**（CSRF、XSS、会话固定）。
SpringBoot 整合：`spring-boot-starter-security`，默认自带登录页面。
核心概念：
- 认证 Authentication：你是谁（登录，账号密码校验）
- 授权 Authorization：你能干什么（角色、权限）
## 2. 核心组件
- SecurityContextHolder：保存当前登录用户信息，ThreadLocal 存储
- Authentication：认证对象
- UserDetails：用户信息封装（用户名、密码、权限、账号状态）
- UserDetailsService：加载用户数据接口，查询数据库用户
- PasswordEncoder：密码加密器，**必须加密，不能明文存储**（BCrypt 推荐）
- FilterChain：大量安全过滤器链，底层基于 Servlet Filter
## 3. 登录流程
1. 请求进入 Security 过滤器链
2. UsernamePasswordAuthenticationFilter 捕获登录请求
3. 调用 UserDetailsService 查询用户
4. PasswordEncoder 比对密码
5. 认证成功，存入 SecurityContext；失败返回错误
## 4. 权限控制
基于两种模式：**基于角色、基于权限字符串**
注解：
- @PreAuthorize ("hasAuthority ('user:list')") 方法执行前校验权限
- @Secured ("ROLE_ADMIN") 角色校验
## 5. JWT 整合 Security
无状态登录，不使用 Session；登录成功生成 JWT 令牌，后续请求 header 携带 token。
流程：登录→生成 JWT→前端保存；每次请求携带 token，过滤器解析 token，组装 Authentication。
## 6. 常用配置
- 关闭默认页面，自定义登录接口（前后端分离）
- 关闭 CSRF（前后端分离 API 项目一般关闭）
- 放行接口：登录、验证码、静态资源
- 自定义未登录、无权限返回 JSON
## 7. 常见坑
1. 密码不加密直接比对，直接报错
2. 角色前缀 ROLE_问题
3. 放行路径配置顺序问题（先匹配优先）
4. 跨域和 Security 过滤器顺序冲突
## 8. 面试问答
Q：认证和授权区别？
A：认证确认身份；授权判断身份拥有哪些访问权限。
Q：Security 底层是什么？
A：一系列 Servlet 过滤器组成过滤器链。
---
