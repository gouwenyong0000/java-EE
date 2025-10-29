# 为什么必须使用类路径加载

在 java应用中，从类路径（Classpath）加载文件是一项核心且基础的功能，普遍用于读取配置文件、JSON 数据、模板、证书、静态资源等。这种方式将资源与代码打包在一起，确保了应用的平台独立性、安全性和部署的便捷性。



#### **为什么必须使用类路径加载？**

在深入技术细节之前，理解其“为什么”至关重要。

1. **平台独立性与可移植性**: 当你的应用被打包成一个可执行的 JAR 文件后，内部的文件（如 `src/main/resources/` 下的文件）不再是传统意义上的文件系统中的独立文件。它们是 JAR 包这个“压缩文件”的一部分。使用标准的文件系统路径 (`java.io.File`) 将无法定位到它们。而类路径加载机制懂得如何在 JAR 包内部进行寻址。
2. **简化部署**: 所有必需的资源都内嵌于一个单一的部署单元（JAR/WAR）中。你只需要复制这一个文件到服务器并运行它，而无需担心外部配置文件、资源的路径是否正确。
3. **安全性**: 不会暴露服务器文件系统的具体结构，减少了因路径遍历等漏洞带来的潜在风险。



# 加载文件三种方式对比

| 加载方式                          | 起始路径                    | 是否依赖 classpath | 是否支持jar内资源 | 路径书写                   | 是否推荐 |
| --------------------------------- | --------------------------- | ------------------ | ----------------- | -------------------------- | -------- |
| `FileInputStream`                 | 文件系统绝对路径            | ❌ 否               | ❌ 否              | 写死物理路径               | ❌ 不推荐 |
| `Class.getResourceAsStream`       | 类的相对路径或 classpath 根 | ✅ 是               | ✅ 是              | `"name"` / `"/name"`       | ✅ 推荐   |
| `ClassLoader.getResourceAsStream` | classpath 根路径            | ✅ 是               | ✅ 是              | 不加 `/`（开头不能带 `/`） | ✅ 推荐   |



## 方式一：使用绝对路径加载 (不推荐)

直接写死路径，使用 FileInputStream 加载资源文件，但是路径就不能动了

```java
public static void main(String[] args) throws IOException {

	// 下面2种写法都可以
    FileInputStream fis1 = new FileInputStream("D:/projects/demo-perm/src/main/java/com/zzhua/a.txt"); // 加载不到文件, 这里会报错
    FileInputStream fis2 = new FileInputStream("D:\\projects\\demo-perm\\src\\main\\java\\com\\zzhua\\a.txt"); // 加载不到文件, 这里会报错

    Properties props = new Properties();
    props.load(fis1);
    props.load(fis2);
    System.out.println(props);
}
```

- **问题**：不便于部署，路径易变；不适用于 JAR 包内文件。
- **适用场景**：临时开发测试、外部文件读取。



## 方式二：使用 `Class.getResourceAsStream(String path)` 加载资源文件

使用 `Class` 加载资源文件是 Java 中读取 classpath 内部资源的常见方式，区别于 `ClassLoader`，它支持**相对路径**和**绝对路径**两种写法

#### 原理：

- 调用 `Class.resolveName()` 处理路径：
  - 不以 `/` 开头：相对类所在包。
  - 以 `/` 开头：从类路径根开始（但内部会去掉开头的 `/`）。
- 最终调用 `ClassLoader.getResourceAsStream()` 实现。

------

### ✅ 一、常用方法

```java
InputStream is = YourClass.class.getResourceAsStream(String name);
```

- **返回**：资源的 `InputStream`
- **资源范围**：**classpath 内的资源**
- **路径解释**：
  - 不以 `/` 开头 → 相对路径（相对于当前类所在的包）
  - 以 `/` 开头 → 绝对路径（从 classpath 根开始）

------

### ✅ 二、路径规则详解

| 写法                 | 路径类型 | 查找位置                           |
| -------------------- | -------- | ---------------------------------- |
| `"a.txt"`            | 相对路径 | `YourClass` 所在包中查找资源       |
| `"/a.txt"`           | 绝对路径 | 从 classpath 根路径查找资源        |
| `"/com/zzhua/a.txt"` | 绝对路径 | classpath 根下的 `com/zzhua/a.txt` |

------

### ✅ 三、实际目录结构示例

项目结构如下：

```
src/
  main/
    java/
      com/zzhua/DemoPermApplication.java
    resources/
      a.txt
      com/zzhua/a.txt
```

构建后出现在：

```
target/classes/a.txt
target/classes/com/zzhua/a.txt
```

------

### ✅ 四、使用示例

#### ☑️ 相对路径（类所在包内）

```java
InputStream is = DemoPermApplication.class.getResourceAsStream("a.txt");
```

- 假设类路径为 `com.zzhua.DemoPermApplication`
- 查找的是：`target/classes/com/zzhua/a.txt`

------

#### ☑️ 绝对路径（classpath 根）

```java
InputStream is = DemoPermApplication.class.getResourceAsStream("/a.txt");
```

- 查找的是：`target/classes/a.txt`

------

#### ☑️ 绝对路径 + 子包路径

```java
InputStream is = DemoPermApplication.class.getResourceAsStream("/com/zzhua/a.txt");
```

- 查找的是：`target/classes/com/zzhua/a.txt`

------

### ⚠️ 五、注意事项

| 问题                        | 说明                                          |
| --------------------------- | --------------------------------------------- |
| ❌ 找不到文件时不抛异常      | `getResourceAsStream()` 返回 `null`           |
| ✅ 必须判空处理              | 否则使用时抛 `NullPointerException`           |
| ✅ 资源必须位于 classpath 下 | 最好放 `src/main/resources`，Maven 会自动打包 |

------

### ✅ 六、正确用法模板

```java
InputStream is = DemoPermApplication.class.getResourceAsStream("/config/app.properties");
if (is == null) {
    throw new FileNotFoundException("资源未找到！");
}
Properties props = new Properties();
props.load(is);
System.out.println(props.getProperty("my.key"));
```

------

### 🔍 七、底层源码解析（JDK）

- 本质：**还是委托给 `ClassLoader`**
- 只是多了一步路径处理（是否带 `/`，是否相对）

```java
public final class Class<T> implements ... { 
	public InputStream getResourceAsStream(String name) {
	   // 根据传过来的名字, 解析绝对路径和相对路径
	   name = resolveName(name);
	   ClassLoader cl = getClassLoader0();
	   
	   // 最终还是使用类加载器加载资源
	   if (cl==null) {
	       // A system class.
	       return ClassLoader.getSystemResourceAsStream(name);
	   }
	   
	   return cl.getResourceAsStream(name);  // 通过类加载器加载
	}
	// 处理绝对路径还是相对路径
	private String resolveName(String name) {
	
		// name不能为null
        if (name == null) {
            return name;
        }

		// 如果name不是以 “/” 开头, 则认为它是相对路径, 相对于当前类所在包路径
        if (!name.startsWith("/")) {
            Class<?> c = this;
            while (c.isArray()) {
                c = c.getComponentType();
            }

			// 获取当前类全路径
            String baseName = c.getName();

			// 找到最后一个.，即当前类的包路径
            int index = baseName.lastIndexOf('.');
            
            // 将包名的.分隔改为/分隔, 并拼接上传过来的name, 组装为最终的路径名
            if (index != -1) {
                name = baseName.substring(0, index).replace('.', '/')+"/"+name;
            }
        } else {
        
        	// 如果带了“/”, 则认为是绝对路径, 但最终是会把开头的这个“/”给去掉
            name = name.substring(1);
        }
        return name;
    }
	
}
```



------

### 🧠 八、与 ClassLoader 的区别总结

| 特点                | `Class.getResourceAsStream()` | `ClassLoader.getResourceAsStream()` |
| ------------------- | ----------------------------- | ----------------------------------- |
| 是否支持相对路径    | ✅ 支持相对类路径              | ❌ 只支持绝对路径（从 classpath 根） |
| 路径能否以 `/` 开头 | ✅ 支持（表示绝对路径）        | ❌ 不支持，不能以 `/` 开头           |
| 是否推荐使用        | ✅ 简单场景推荐                | ✅ 框架开发推荐                      |

------

### ✅ 九、小技巧

#### 获取类路径下某个资源的真实路径：

```java
URL url = DemoPermApplication.class.getResource("/config/app.properties");
System.out.println(url.getPath()); // 打印真实文件路径
```

------

### 🔚 十、总结

| 核心点                                                       | 内容                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| 使用 `Class.getResourceAsStream()` 可加载相对或绝对 classpath 路径下的资源 | 非 `/` 开头路径是相对于当前类所在包<br>`/` 开头路径是从 classpath 根路径 |
| 推荐资源放 `resources/`，确保被打包                          | 找不到资源时要注意判空处理                                   |



## 方式三：使用 `ClassLoader.getResourceAsStream` 加载资源文件



以下是一份关于 Java 中 `ClassLoader` 的 `getResource()` 与 `getResources()` 方法的系统化笔记，涵盖用法、对比、源码逻辑、实战场景和注意事项。

### 🧠 一、方法简介

#### 特点：

- 总是**从类路径根开始**。
- 路径前 **不能加 `/`**。
- 可用于加载 JAR 包内资源。



#### 1️⃣ `getResource(String name)`

* **功能**：查找类路径下某个资源的 URL（**只返回第一个匹配的**）。
* **返回**：`URL`
* **常用场景**：加载配置文件、模板、单一资源文件。

```java
URL url = ClassLoader.getSystemClassLoader().getResource("config/app.properties");
```

---

#### 2️⃣ `getResources(String name)`

* **功能**：查找类路径下所有与名称匹配的资源。
* **返回**：`Enumeration<URL>`
* **常用场景**：SPI、Spring 自动配置、多模块资源合并。

```java
Enumeration<URL> urls = ClassLoader.getSystemClassLoader().getResources("META-INF/spring.factories");
```

---

### 📌 二、路径解析规则（重点）

* 资源路径是 **相对于 classpath 根目录的路径**
* **不要以 `/` 开头**（即使看起来是绝对路径也不要）

  * ✅ 正确：`getResource("config/app.properties")`
  * ❌ 错误：`getResource("/config/app.properties")`

---

### 🔍 三、方法区别对比

| 比较项       | `getResource(String name)`     | `getResources(String name)`                 |
| ------------ | ------------------------------ | ------------------------------------------- |
| 返回类型     | `URL`                          | `Enumeration<URL>`                          |
| 匹配结果数量 | 第一个匹配的资源               | 所有匹配的资源                              |
| 使用场景     | 加载单一资源文件               | 需要合并多个模块资源（如 SPI、Spring 配置） |
| 底层机制     | 父类加载器查找并返回第一个资源 | 父类加载器查找并返回所有资源                |

---

### 📦 四、实际资源结构示例

```
src/main/resources/
  └── config/app.properties

jar1.jar
  └── META-INF/spring.factories

jar2.jar
  └── META-INF/spring.factories
```

使用 `getResources("META-INF/spring.factories")` 会返回两个 `URL`。

---

### 🔄 五、常见用法示例

#### ✅ 加载资源文件内容：

```java
InputStream is = Thread.currentThread()
                       .getContextClassLoader()
                       .getResourceAsStream("config/app.properties");
Properties props = new Properties();
props.load(is);
```

#### ✅ 遍历所有 SPI 实现配置：

```java
Enumeration<URL> urls = ClassLoader.getSystemClassLoader()
    .getResources("META-INF/services/java.sql.Driver");

while (urls.hasMoreElements()) {
    URL url = urls.nextElement();
    System.out.println("找到 SPI 配置文件: " + url);
}
```

---

### 🔧 六、源码简析

#### `getResource(String name)` 源码片段（简化）：

```java
public URL getResource(String name) {
    if (parent != null) {
        URL url = parent.getResource(name);
        if (url != null) return url;
    }
    return findResource(name); // 当前类加载器找
}
```

#### `getResources(String name)` 源码片段（简化）：

```java
public Enumeration<URL> getResources(String name) throws IOException {
    Enumeration<URL> parentResources = parent.getResources(name);
    Enumeration<URL> localResources = findResources(name);
    return new CompoundEnumeration<>(new Enumeration[] { parentResources, localResources });
}
```

---

### ⚠️ 七、常见错误和注意事项

| 问题                 | 说明                                                         |
| -------------------- | ------------------------------------------------------------ |
| 路径加了 `/`         | `ClassLoader` 中路径不能以 `/` 开头                          |
| 文件未打包进 classes | 资源文件必须放在 `src/main/resources` 或被 `<resources>` 指定 |
| jar 包中读取不到资源 | 使用 `new File()` 是无效的，应使用 `getResourceAsStream()`   |
| 资源顺序不确定       | 多个 jar 加载顺序由类路径顺序决定，尽量避免依赖顺序          |

---

### 🚀 八、小技巧

#### ✅ 获取 classpath 根目录：

```java
String path = ClassLoader.getSystemClassLoader().getResource("").getPath();
```

#### ✅ 获取某个资源路径字符串：

```java
URL url = ClassLoader.getSystemClassLoader().getResource("config/app.properties");
System.out.println(url.getPath());
```

---

### ✅ 九、Spring 扩展工具：`PathMatchingResourcePatternResolver`

Spring 提供通配符式加载所有匹配资源：

```java
Resource[] resources = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:META-INF/spring.factories");
```

* `classpath:` → 加载第一个匹配项
* `classpath*:` → 加载所有匹配资源（等价于 `getResources()`）

---

### ✅ 十、总结语

| 核心理解点                                                   |
| ------------------------------------------------------------ |
| `getResource()` → 找到第一个资源                             |
| `getResources()` → 找到所有匹配资源（支持多 jar）            |
| 两者路径不能以 `/` 开头（不同于 `Class.getResource()`）      |
| 多模块合并资源、自动配置、SPI 等场景强烈推荐使用 `getResources()` |



# 拓展：Java 加载 JAR 包内资源文件的案例

------

## ✅ 一、JAR 中资源结构示例

假设你的项目结构为：

```
src/main/resources/
  ├── config/
  │    └── app.properties
```

构建 JAR 后资源会被打包进：

```
demo-app.jar
  └── config/app.properties
```

------

## ✅ 二、读取 JAR 内资源文件

### ✔️ 方式 1：使用 `Class.getResourceAsStream()`（推荐）

- 

```java
public class ResourceInJarExample {
    public static void main(String[] args) throws IOException {
        // 从 classpath 根路径读取
        InputStream is = ResourceInJarExample.class.getResourceAsStream("/config/app.properties");
        if (is == null) {
            throw new FileNotFoundException("资源未找到！");
        }

        Properties props = new Properties();
        props.load(is);
        System.out.println("读取属性: " + props.getProperty("my.key"));
    }
}
```

------

### ✔️ 方式 2：使用 `ClassLoader.getResourceAsStream()`

```java
InputStream is = Thread.currentThread()
                       .getContextClassLoader()
                       .getResourceAsStream("config/app.properties");
```

> ✅ `ClassLoader` 加载路径不能以 `/` 开头

------

### ✔️ 方式 3：从 JAR 内获取 URL（可打印路径或复制资源）

```java
URL url = ResourceInJarExample.class.getResource("/config/app.properties");
System.out.println("资源 URL: " + url);
```

JAR 运行时的输出类似：

```java
jar:file:/path/to/demo-app.jar!/config/app.properties
```

------

## 📌 三、JAR 内资源加载注意事项

| 问题                                                        | 说明                                                      |
| ----------------------------------------------------------- | --------------------------------------------------------- |
| ❌ `new File()` 无法访问 JAR 内资源                          | 因为 JAR 内部不是文件系统，需使用 `getResourceAsStream()` |
| ✅ 推荐放在 `resources/` 目录                                | Maven 会自动打包到 `classes/`                             |
| ❌ `FileInputStream` 不支持读取 JAR 内文件                   | 因为它只能操作操作系统真实路径                            |
| ✅ 可以使用 `Files.copy()` 将资源从 JAR 内提取到本地临时目录 |                                                           |

------

## 🧪 四、验证资源是否被打入 JAR

执行命令：

```sh
jar tf target/demo-app.jar
```

输出：

```
config/app.properties
```

表示资源已正确打包。

------

## 🧠 五、Spring Boot 打包场景注意事项

Spring Boot 使用 fat jar（可执行 JAR），其结构更复杂：

```
BOOT-INF/classes/config/app.properties
```

这时候：

- ✅ `ClassLoader.getResourceAsStream("config/app.properties")` 仍然有效
- ❌ `new File("config/app.properties")` 无效
- ✅ Spring 的 `ResourceLoader` 推荐使用 `classpath:` 方式读取

------

## 🛠 六、拓展：将 JAR 中的资源复制到磁盘

```java
InputStream is = YourClass.class.getResourceAsStream("/config/app.properties");
Files.copy(is, Paths.get("output/app.properties"), StandardCopyOption.REPLACE_EXISTING);
```

------

## ✅ 七、总结

| 加载方式                            | 是否支持 JAR 内资源 | 路径格式                | 备注             |
| ----------------------------------- | ------------------- | ----------------------- | ---------------- |
| `Class.getResourceAsStream()`       | ✅                   | 绝对 `/path` 或相对路径 | 推荐方式         |
| `ClassLoader.getResourceAsStream()` | ✅                   | 不带 `/` 的路径         | 常用于框架       |
| `new File()` / `FileInputStream`    | ❌                   | 不支持                  | 只能用于磁盘资源 |

# speingboot Classpath 加载文件

Spring Boot 项目中“类路径下文件加载”的方式有很多种，取决于使用场景（读取配置、读取资源文件、Jar 包内访问等）。以下是一个结构化的总结，涵盖**加载方式、原理、用法、适用场景和注意事项**。

---

## 一、类路径（Classpath）简介

### 1. 什么是类路径

* **类路径（classpath）**：指 JVM 加载类或资源时查找的根路径。
* Spring Boot 中通常指 `/src/main/resources` 目录以及 Jar 包内的资源。

### 2. 常见路径说明

| 路径形式      | 说明                                     |
| ------------- | ---------------------------------------- |
| `classpath:`  | Spring 中的资源前缀，表示类路径          |
| `classpath*:` | 表示从**所有的类路径**中加载（多个 Jar） |
| `/xxx`        | 表示从类路径根开始                       |
| `xxx`         | 相对于当前类或包的相对路径               |

---

## 二、加载类路径资源的常见方式

### 1. 使用 `ClassPathResource`

```java
Resource resource = new ClassPathResource("config/myfile.txt");
InputStream is = resource.getInputStream();
```

* **特点**：读取类路径文件，支持 Spring 的 `Resource` 抽象。
* **适合场景**：适合从 `src/main/resources` 读取文件，部署成 Jar 后依然可用。

---

### 2. 使用 `ResourceLoader`

```java
@Autowired
private ResourceLoader resourceLoader;

Resource resource = resourceLoader.getResource("classpath:config/myfile.txt");
```

* **优点**：可以用 `classpath:`、`file:`、`http:` 等统一资源前缀。
* **自动注入**：在 Spring Bean 中自动注入 `ResourceLoader` 使用。

---

### 3. 使用 `@Value` 注解

```java
@Value("classpath:config/myfile.txt")
private Resource resource;
```

或直接读取内容：

```java
@Value("classpath:config/version.txt")
private String version;
```

* **适合简单文件/配置**，如读取配置项、版本号、模板等。
* 注意不能用于大文件或非文本资源。

---

### 4. 使用 `Class.getResourceAsStream`

```java
InputStream is = getClass().getResourceAsStream("/config/myfile.txt");
```

* `/` 表示从类路径根开始
* 无`/`相对路径从当前类所在包起。
* **适合工具类内部封装加载逻辑**。

---

### 5. 使用 `ClassLoader.getResourceAsStream`

```java
InputStream is = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream("config/myfile.txt");
```

* 不以 `/` 开头，从类路径根开始查找。
* 与 `Class.getResourceAsStream` 不同点在于加载器来源。

---

## 三、Spring Boot 特有方式

### 1. `application.properties` 中使用 classpath

```properties
my.config=classpath:config/app.properties
```

然后在 Java 中加载：

```java
@Value("${my.config}")
private Resource config;
```

---

### 2. `Environment.getProperty` + `@ConfigurationProperties`

```java
@ConfigurationProperties(prefix = "custom")
public class MyProperties {
    private Resource file;
    // getter, setter
}
```

---

## 四、classpath vs classpath\*

| 前缀          | 说明                                          |
| ------------- | --------------------------------------------- |
| `classpath:`  | 只查找当前 classloader 的类路径               |
| `classpath*:` | 查找所有 classloader 的类路径（包括多个 jar） |

示例（多个 jar 中查找配置文件）：

```java
Resource[] resources = 
  new PathMatchingResourcePatternResolver().getResources("classpath*:META-INF/spring.factories");
```

---

## 五、Jar 包内资源访问注意事项

1. 打包成 Jar 后，`File` 方式读取资源会失败，应使用 `InputStream` 或 `Resource`。
2. `File file = new File("classpath:...")` 会失败。
3. 推荐使用：

   * `ClassLoader.getResourceAsStream`
   * `ClassPathResource`
   * `resource.getInputStream()`

---

## 六、常见用途示例

| 用途         | 方法                                                   |
| ------------ | ------------------------------------------------------ |
| 加载文本模板 | `ClassPathResource + InputStreamReader`                |
| 读取配置文件 | `@Value` / `@ConfigurationProperties`                  |
| 动态加载资源 | `ResourceLoader.getResource()`                         |
| 多个资源     | `classpath*:` + `PathMatchingResourcePatternResolver`  |
| 静态资源     | 放在 `static/` 或 `public/` 目录，Spring Boot 自动映射 |

---

## 七、常见问题总结

| 问题                    | 原因 & 解决方法                                |
| ----------------------- | ---------------------------------------------- |
| `FileNotFoundException` | 打成 jar 后不能用 `File` 加载类路径资源        |
| 读取不到资源            | 路径未以 `classpath:` 开头或写法错误           |
| 相对路径错乱            | 用 `getClass().getResource` 时路径未加 `/`     |
| 多个 jar 中找不到资源   | 应使用 `classpath*:` 方式加载                  |
| 二进制文件乱码或损坏    | 用字符流读取了二进制文件，应使用 `InputStream` |

---

## 八、总结对比表

| 方式                              | 优点                         | 缺点或限制                 |
| --------------------------------- | ---------------------------- | -------------------------- |
| `ClassPathResource`               | 简单直接，适配 Spring        | 只支持类路径               |
| `ResourceLoader.getResource`      | 支持多种协议（file, http等） | 需要注入 ResourceLoader    |
| `@Value("classpath:...")`         | 注入方便                     | 不适合复杂逻辑或大资源文件 |
| `getClass().getResourceAsStream`  | 类内资源访问灵活             | 相对路径容易混淆           |
| `ClassLoader.getResourceAsStream` | 通用、适配 jar               | 不支持相对路径             |

---





# maven build 打包资源文件详解

在 Maven 构建项目时，`<build><resources>` 配置用于指定哪些资源文件（如 `*.properties`、`*.xml`、`*.yml` 等）会被复制到最终的 `target/classes` 目录中。以下是一些常见配置的完整示例：

------

## ✅ 1. **默认资源配置**

如果你没有显式配置，Maven 默认会将 `src/main/resources` 下的所有文件复制到 `target/classes`。

```xml
<build>
  <resources>
    <resource>
      <directory>src/main/resources</directory>
      <includes>
        <include>**/*</include>
      </includes>
    </resource>
  </resources>
</build>
```

------

## ✅ 2. **包含/排除部分文件**

只复制指定的文件，比如 `.properties` 文件，并排除 `.bak` 文件：

```xml
<build>
  <resources>
    <resource>
      <directory>src/main/resources</directory>
      <includes>
        <include>**/*.properties</include>
      </includes>
      <excludes>
        <exclude>**/*.bak</exclude>
      </excludes>
    </resource>
  </resources>
</build>
```

------

## ✅ 3. **启用资源过滤**

资源过滤（resource filtering）允许你使用 `${}` 占位符动态替换属性值（如 `${project.version}`）：

```xml
<build>
  <resources>
    <resource>
      <directory>src/main/resources</directory>
      <filtering>true</filtering>
    </resource>
  </resources>
</build>
```

并在 `src/main/resources/config.properties` 中写：

```properties
version=${project.version}
```

最终打包后替换为：

```properties
version=1.0.0
```

如果你需要在过滤中使用自定义变量，可以在 `pom.xml` 中定义：

```xml
<properties>
  <my.env>dev</my.env>
</properties>
```

------

## ✅ 4. **多资源目录**

如果项目中有多个资源文件夹，比如 `src/main/resources` 和 `src/main/extra-resources`：

```xml
<build>
  <resources>
    <resource>
      <directory>src/main/resources</directory>
    </resource>
    <resource>
      <directory>src/main/extra-resources</directory>
      <targetPath>extra</targetPath> <!-- 指定输出路径 -->
    </resource>
  </resources>
</build>
```

最终资源会被拷贝到：

```
target/classes/           ← 默认目录
target/classes/extra/     ← extra-resources 的输出目录
```

------

如需更具体的例子，比如和 Spring Boot、环境区分、Profile 配合的用法，也可以继续问我！