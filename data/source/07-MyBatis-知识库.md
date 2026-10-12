#7. MyBatis 知识库
## 1. MyBatis 概述
持久层框架，**半自动 ORM**，自定义 SQL，Java 对象和数据库记录映射。
对比 JPA 全自动：MyBatis SQL 自己写，灵活，适合复杂查询。
## 2. 核心组件
- SqlSessionFactory：工厂，生产 SqlSession
- SqlSession：会话对象，执行 SQL，操作数据库
- Mapper 接口：动态代理，不用写实现类
- Mapper.xml：SQL 映射文件，存放 sql 语句
- ResultMap：自定义结果映射，解决字段名和实体属性名不一致
## 3. 核心配置
mybatis-config.xml：全局配置（环境、类型别名、插件）
Mapper.xml：映射文件，CRUD SQL
## 4. 动态 SQL（高频）
标签：<if> <where> <choose> <foreach> <set>
解决拼接 SQL 繁琐，避免多余 and、逗号。
```
<where>
<if test="name != null"> name = #{name} </if>
</where>
```
## {} 和 ${} 区别
- #{ }：预编译，PreparedStatement，防止 SQL 注入，推荐
- ${ }：字符串直接拼接，存在 SQL 注入风险；仅用于表名 / 列名
## 5. 一级缓存 & 二级缓存
- 一级缓存：SqlSession 级别，默认开启，同一个会话查询同一条 SQL，缓存结果；会话关闭清空。
- 二级缓存：Mapper 级别，跨 SqlSession；需要手动开启。
## 6. 关联查询
一对一 association，一对多 collection
## 7. SpringBoot 整合 MyBatis
starter：mybatis-spring-boot-starter
@MapperScan 扫描 mapper 接口；mapper-locations 指定 xml 文件路径。
## 8. 面试问答
Q：#{} 和\({}区别？
A：#{}预编译防注入；\){} 直接字符串拼接，有注入风险。
Q：一级缓存什么时候失效？
A：SqlSession 关闭、执行增删改操作、手动 clearCache。
---
