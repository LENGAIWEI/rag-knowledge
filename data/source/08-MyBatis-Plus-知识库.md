#8. MyBatis-Plus 知识库
## 1. MP 概述
MyBatis 增强工具，**只增强不修改**MyBatis，减少基础 CRUD 代码。
starter：mybatis-plus-boot-starter
## 2. 核心注解
- @TableName ("t_user") 指定表名
- @TableId：主键
type: AUTO 自增、ASSIGN_ID 雪花算法（默认）
- @TableField：普通字段；exist=false 代表非数据库字段
- @TableLogic：逻辑删除标记字段，删除变成 update，数据保留
## 3. 基础 CRUD
BaseMapper 内置：insert、deleteById、updateById、selectById、selectList 等，无需写 xml。
## 4. 条件构造器 Wrapper
- QueryWrapper：查询条件
- LambdaQueryWrapper：lambda 写法，防字段名写错（推荐）
- UpdateWrapper：更新条件
- LambdaUpdateWrapper
```
LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery();
wrapper.eq(User::getName,"张三");
```
## 5. 分页插件
必须手动注册 PaginationInnerInterceptor，调用 page 方法分页；**不注册插件分页无效**。
## 6. 自动填充
@TableField (fill = FieldFill.INSERT) 创建时间
@TableField (fill = FieldFill.INSERT_UPDATE) 更新时间
自定义 MetaObjectHandler 实现填充逻辑，自动赋值 createTime、updateTime
## 7. 乐观锁
@Version 标记版本号，更新时校验版本号，解决并发更新丢失问题。
## 8. 常用坑
1. 分页插件未注册，total 总数不对
2. 自动填充没有实现 MetaObjectHandler
3. 逻辑删除字段类型错误
## 9. 面试问答
Q：MP 的逻辑删除原理？
A：不是 delete 语句，执行 update 更新删除标记，查询自动追加条件 where deleted=0。
---
