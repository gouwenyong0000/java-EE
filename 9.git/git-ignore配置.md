可以把 `.gitignore` 的书写规则总结成一句话：

> **`.gitignore` = 用“路径 + 通配符 + 否定规则”告诉 Git：哪些文件不要进入版本控制。**

下面这份可以直接作为你的复习笔记。

## `.gitignore` 规则速查

### 1. 最基本的写法

| 写法        | 含义                   | 示例                    |
| --------- | -------------------- | --------------------- |
| `abc`     | 匹配名为 `abc` 的文件/目录    | `target`              |
| `abc/`    | 匹配目录 `abc`           | `target/`             |
| `*.class` | 匹配任意位置的 `.class` 文件  | `*.class`             |
| `*.log`   | 匹配任意位置的 `.log` 文件    | `*.log`               |
| `/abc`    | **只匹配仓库根目录**下的 `abc` | `/target/`            |
| `abc/def` | 匹配这种路径结构             | `src/test/`           |
| `!abc`    | **取消忽略** `abc`       | `!gradle-wrapper.jar` |

---

# 2. 三个最重要的通配符

## `*`：匹配任意字符

```gitignore
*.class
```

表示：

```text
A.class
Test.class
src/A.class
module/target/A.class
```

都可以匹配。

所以：

```gitignore
*.class
```

非常适合你的 **多项目 Java 仓库**。

---

## `?`：匹配一个字符

```gitignore
test?.java
```

可以匹配：

```text
test1.java
testA.java
test_.java
```

不能匹配：

```text
test12.java
test.java
```

实际使用频率比较低。

---

## `[]`：匹配指定字符之一

例如：

```gitignore
test[123].java
```

匹配：

```text
test1.java
test2.java
test3.java
```

也可以：

```gitignore
file[0-9].txt
```

匹配：

```text
file0.txt
file1.txt
...
file9.txt
```

---

# 3. `/` 的作用非常重要

## `/target/`

```gitignore
/target/
```

表示：

> **只忽略仓库根目录下的 `target` 目录。**

例如：

```text
project/
├── target/       ← 忽略
├── module/
│   └── target/   ← 不一定匹配
```

---

## `target/`

```gitignore
target/
```

表示：

> 忽略名称为 `target` 的目录，不限定在仓库根目录。

例如：

```text
project/
├── target/             ← 忽略
├── module/
│   └── target/         ← 忽略
└── a/
    └── b/
        └── target/     ← 忽略
```

### 因此

如果你的仓库：

```text
一个 Git 仓库
├── Java项目A
├── Java项目B
├── Java项目C
├── 前端项目
└── 其他项目
```

通常：

```gitignore
target/
```

比：

```gitignore
/target/
```

更适合。

---

# 4. `**`：跨目录匹配

`**` 可以理解为：

> **匹配任意层级的目录。**

例如：

```gitignore
**/target/
```

可以匹配：

```text
target/
a/target/
a/b/target/
a/b/c/target/
```

不过对于普通场景：

```gitignore
target/
```

通常已经足够。

所以不要为了使用 `**` 而使用 `**`。

---

# 5. `!`：取消忽略

这是 `.gitignore` 中非常重要、也比较容易搞混的规则。

例如：

```gitignore
*.jar
!gradle/wrapper/gradle-wrapper.jar
```

含义：

> 默认忽略所有 `.jar`，但是 `gradle/wrapper/gradle-wrapper.jar` 除外。

最终：

```text
abc.jar
       ↓
     忽略

lib/test.jar
       ↓
     忽略

gradle/wrapper/gradle-wrapper.jar
       ↓
     不忽略
```

---

# 6. 一个非常重要的原则：后面的规则可以覆盖前面的规则

例如：

```gitignore
*.log
important.log
```

结果：

```text
important.log
```

仍然会被忽略。

如果：

```gitignore
*.log
!important.log
```

那么：

```text
abc.log          → 忽略
important.log    → 不忽略
```

可以记：

> **普通规则 = 忽略**
>
> **`!`规则 = 取消忽略**

---

# 7. 文件和目录的区别

推荐明确写 `/`：

```gitignore
target/
build/
.idea/
```

表示目录。

而：

```gitignore
*.class
*.log
*.tmp
```

表示文件。

这样可读性更好。

---

# 8. 注释

使用：

```gitignore
# Java
*.class
target/

# IntelliJ IDEA
.idea/
*.iml

# Logs
*.log
```

`#` 后面是注释，不参与匹配。

建议按照功能分组：

```gitignore
# ====================
# Java
# ====================

*.class
target/

# ====================
# IDE
# ====================

.idea/
*.iml
```

---

# 9. 常见 Java 项目规则

例如：

```gitignore
# Maven
target/

# Gradle
.gradle/
build/

# Java 编译产物
*.class

# IntelliJ IDEA
.idea/
*.iml
*.iws
*.ipr

# Eclipse
.classpath
.project
.settings/

# 日志
*.log

# 临时文件
*.tmp
*.bak
*.swp
```

---

# 10. `.gitignore` 最容易犯的几个错误

### 错误 1：以为 `.gitignore` 可以删除已经提交的文件

例如：

```gitignore
*.class
```

只能告诉 Git：

> **以后不要跟踪 `.class`。**

如果 `.class` 已经被提交：

```bash
git add .
git commit
```

之后再添加 `.gitignore`：

```gitignore
*.class
```

Git **不会自动把已经跟踪的 `.class` 删除**。

需要：

```bash
git rm --cached *.class
```

或者针对目录：

```bash
git rm -r --cached target/
```

然后重新提交。

---

### 错误 2：`/target/` 和 `target/` 混淆

```gitignore
/target/
```

≈ 只针对根目录。

```gitignore
target/
```

≈ 不限定目录层级。

对于你的 **多项目 Git 仓库**，一般更推荐：

```gitignore
target/
build/
out/
```

而不是：

```gitignore
/target/
/build/
/out/
```

---

### 错误 3：滥用 `**`

例如：

```gitignore
**/target/
```

虽然可以，但很多时候：

```gitignore
target/
```

就已经足够。

原则：

> **能用简单规则解决，就不要增加复杂度。**

---

# 11. 你的场景可以重点记住这张表

| 规则           | 实际含义              |
| ------------ | ----------------- |
| `*.class`    | 所有目录中的 `.class`   |
| `*.jar`      | 所有目录中的 `.jar`     |
| `target/`    | 所有层级的 `target` 目录 |
| `build/`     | 所有层级的 `build` 目录  |
| `out/`       | 所有层级的 `out` 目录    |
| `.idea/`     | IDEA 项目目录         |
| `*.iml`      | IDEA 模块文件         |
| `/target/`   | 仓库根目录的 target     |
| `**/target/` | 任意层级的 target      |
| `!xxx`       | 将 xxx 从忽略规则中排除    |

---

# 12. 最终形成一个思维模型

看到一个 `.gitignore`：

```gitignore
*.class
target/
.idea/
!gradle/wrapper/gradle-wrapper.jar
```

可以按照下面的方式理解：

```text
                .gitignore
                    │
          ┌─────────┴─────────┐
          ↓                   ↓
       匹配规则             排除规则
          │                   │
    ┌─────┼─────┐             │
    ↓     ↓     ↓             ↓
   *     /     **             !
    │     │      │             │
 任意   路径   任意层级       取消忽略
 字符   关系
```

### 最值得记住的 6 个符号

```text
*      任意字符
?      一个字符
[]     字符集合
/      路径/目录层级
**     任意层级目录
!      取消忽略
```

再加一个：

```text
#      注释
```

**实际写 `.gitignore` 时，80% 的场景只需要掌握 `*`、`/`、`!` 和目录末尾的 `/`。**
