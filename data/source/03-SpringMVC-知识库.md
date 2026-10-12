# 2. SpringMVC 知识库
## 1. SpringMVC 概述
SpringMVC 是 Spring 体系下 Web 层框架，基于 MVC 设计模式，用于开发 Web 接口。
**核心组件：DispatcherServlet（前端控制器，总调度）**
MVC：Model 模型、View 视图、Controller 控制器；现在前后端分离，View 一般不用，直接返回 JSON。
## 2. 完整请求执行流程
1. 客户端发送请求，请求到达 DispatcherServlet
2. DispatcherServlet 调用 HandlerMapping，查找匹配的 Handler（Controller 方法）
3. 获取 HandlerAdapter 处理器适配器，执行 Controller 方法
4. Controller 执行，返回 ModelAndView
5. 视图解析器 ViewResolver 解析视图（前后端分离一般不使用）
6. 渲染视图，返回响应；接口项目直接返回 JSON，跳过视图渲染
## 3. 接收请求参数
- @PathVariable：获取路径变量 /user/{id}
- @RequestParam：获取 url 查询参数？name=xxx
- @RequestBody：读取 POST 请求体 JSON，转实体（只能一次读取 body）
- @RequestHeader：获取请求头
- @CookieValue：获取 Cookie
- 表单提交：直接实体接收，无需注解
## 4. 常用注解
- @Controller：返回页面
- @RestController = @Controller + @ResponseBody，返回 JSON
- @RequestMapping / @GetMapping / @PostMapping / @PutMapping / @DeleteMapping
- @ResponseBody：将返回对象序列化为 JSON
- @CrossOrigin：解决跨域 CORS
## 5. 拦截器 HandlerInterceptor
拦截请求，在 Controller 执行前后做处理，**区别于 Filter**
Filter：Servlet 规范，在 Servlet 执行前；拦截器属于 SpringMVC，只能拦截 Controller 请求。
三个方法：
preHandle：Controller 执行前，返回 true 放行，false 拦截
postHandle：Controller 执行完成，视图渲染前
afterCompletion：整个请求结束之后
>
> 注册：实现 WebMvcConfigurer，addInterceptors 添加拦截器，配置放行路径。
## 6. 异常处理
- @ExceptionHandler：Controller 内局部异常
- @ControllerAdvice / @RestControllerAdvice：全局统一异常处理，捕获所有 Controller 异常
## 7. WebMvcConfigurer
自定义 MVC 配置，添加拦截器、跨域、静态资源、消息转换器、日期格式化。
⚠️ 不要加`@EnableWebMvc`，会覆盖 SpringBoot 自动 MVC 配置。
## 8. 文件上传
MultipartFile 接收文件，配置上传文件大小限制。
## 9. 面试问答
Q：Filter 和 Interceptor 区别？
A：Filter 是 Servlet 规范，作用在 Servlet 之前；Interceptor 属于 SpringMVC，只能拦截 Controller，可拿到 Spring 容器对象。
Q：@RequestBody 原理？
A：使用消息转换器 HttpMessageConverter 读取请求体 JSON，反序列化为 Java 对象。
---
