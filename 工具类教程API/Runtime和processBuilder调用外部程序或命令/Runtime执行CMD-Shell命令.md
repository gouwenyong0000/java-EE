# Runtime执行CMD-Shell命令

> **定位**：从入门 → 进阶 → 生产实践
>
> **适用人群**：Java 后端 / 运维自动化 / 工具开发
>
> **目标**：
>
> - 正确、安全地在 Java 中执行 CMD / Shell 命令
> - 避免死锁、乱码、阻塞、注入等经典坑
> - 能在生产环境中“放心用”

## 整体知识地图

```
Java 执行系统命令
│
├── 基础 API
│   ├── Runtime.exec（旧）
│   └── ProcessBuilder（推荐）
│
├── 进程交互
│   ├── 标准输出 stdout
│   ├── 错误输出 stderr
│   └── 标准输入 stdin
│
├── 平台差异
│   ├── Windows（cmd.exe）
│   └── Linux / macOS（/bin/sh / bash）
│
├── 关键问题
│   ├── 死锁（必须并发读流）
│   ├── 中文乱码（字符集）
│   ├── 命令注入（安全）
│   └── 超时与进程回收
│
├── 高级方案
│   ├── Apache Commons Exec
│   └── 工具类封装
│
└── 实战
    ├── 执行系统命令
    ├── 端口检测 / ping
    ├── 构建 / 部署脚本
    └── 运维自动化
```

> **学习建议**：先完整看一遍 → 再回到对应章节查用法。

---

## 前言

在 Java 中，我们可以通过两种主要方式执行系统命令或外部程序：

1. **`Runtime.exec()`** —— 经典方式，简单直接；
2. **`ProcessBuilder`** —— 现代方式，更灵活、可控。

两者最终都返回一个 `Process` 对象，用于与子进程交互，包括输入输出流、退出状态、资源销毁等操作。

---

## 一、Java 执行外部命令的方式对比

| 特性 / 项目      | Runtime.exec()                                           | ProcessBuilder                                               |
| ---------------- | -------------------------------------------------------- | ------------------------------------------------------------ |
| **推荐程度**     | ❌ 不推荐新项目                                           | ✅ **推荐方式**                                               |
| **参数传递**     | 支持字符串或字符串数组                                   | 仅支持字符串列表（更安全）,能防止命令注入问题                |
| **环境变量设置** | 不能直接设置，需通过 `String[] envp` 参数传入            | 可通过 `pb.environment().put(key, value)` 动态修改,更灵活    |
| **工作目录**     | 默认当前进程工作目录，可在第三个参数传入 `File` 对象指定 | 可通过 `pb.directory(File)` 设置,可独立指定运行路            |
| **错误输出合并** | 不支持直接合并                                           | 可通过 `pb.redirectErrorStream(true)` 合并标准输出和错误输出 |
| **输出读取**     | 需手动获取 `Process.getInputStream()`                    | 支持灵活的重定向输出，如 `pb.redirectOutput(File)`           |
| **执行结果**     | 仅返回 `Process` 对象，需要自行处理流                    | 同样返回 `Process`，但支持更多控制                           |
| **可维护性**     | 较差                                                     | 较高                                                         |
| **错误处理**     | 不支持自动捕获                                           | 可通过异常或合并流方式处理                                   |

> ⚙️ **建议：** 新代码中应优先使用 `ProcessBuilder`。

---

## 二、Runtime.exec() 使用详解

### 1. 基本用法

windows

```java
String command = "cmd /c dir";
Process process = Runtime.getRuntime().exec(command);
```

- `/c`：执行后关闭
- `/k`：执行后不关闭（交互场景）

 Linux Shell :

```java
// 数组形式传递参数可避免空格和特殊字符问题
Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", "ls -l"});
```

### 2. 输出流阻塞问题（极其重要）

> **核心事实**：
> 子进程 stdout / stderr 缓冲区是有限的，不及时读取会导致子进程阻塞。

**JDK 9+：**

```java
new Thread(() -> process.getInputStream().transferTo(System.out)).start();
new Thread(() -> process.getErrorStream().transferTo(System.err)).start();
```

**JDK8 兼容方案**

> ```java
> // 工具方法：异步复制流
> private static void copyStream(InputStream in, OutputStream out) {
>        new Thread(() -> {
>            try (BufferedInputStream bis = new BufferedInputStream(in)) {
>                byte[] buffer = new byte[1024];
>                int len;
>                while ((len = bis.read(buffer)) != -1) {
>                    out.write(buffer, 0, len);
>                    out.flush();
>                }
>            } catch (IOException e) {
>                e.printStackTrace();
>            }
>        }).start();
> }
> 
> // 使用方式
> copyStream(process.getInputStream(), System.out);
> copyStream(process.getErrorStream(), System.err);
> ```
> 

### 3. 获取退出值

```java
int exitCode = process.waitFor(); // 阻塞等待执行完成
System.out.println("命令退出码: " + exitCode); // 0表示成功
```

---

### 4.设置环境变量

```java
Process p = Runtime.getRuntime().exec(cmdArray, envp, workDir);
```

windows 

```java
String[] cmd = {"cmd.exe", "/c", "echo %MY_VAR%"};
String[] envp = {"MY_VAR=HelloFromJava"};
Process process = Runtime.getRuntime().exec(cmd, envp, null);
```



linux:

```java
public class RuntimeEnvExample {
    public static void main(String[] args) throws Exception {
        // 命令（Linux 示例，Windows 可改为 cmd /c echo）
        String[] cmd = {"/bin/bash", "-c", "echo $MY_VAR"};

        // 设置环境变量（格式 "KEY=VALUE"）
        String[] envp = {"MY_VAR=HelloFromJava"};

        Process process = Runtime.getRuntime().exec(cmd, envp, null);

        // 读取输出
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream())
        );

        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }

        process.waitFor();
        reader.close();
    }
}
```



### 5. 完整案例（带超时控制）

```java
public class RuntimeExecExample {
  public static void main(String[] args) {
    try {
      String command = getSystemCommand(); // 根据系统生成命令
      System.out.println("执行命令: " + command);
      String output = execCommand(command, 5, TimeUnit.SECONDS);
      System.out.println("命令输出：\n" + output);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // 根据操作系统生成测试命令
  private static String getSystemCommand() {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      return "cmd /c ping 127.0.0.1 -n 3"; // Windows ping 3次
    } else {
      return "/bin/sh -c ping -c 3 127.0.0.1"; // Linux/macOS ping 3次
    }
  }

  /** 执行命令并返回结果（带超时控制） */
  public static String execCommand(String command, long timeout, TimeUnit unit) {
    StringBuilder result = new StringBuilder();
    Process process = null;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      process = Runtime.getRuntime().exec(command);
      String charset = Charset.defaultCharset().name();

      // 异步读取输出流和错误流
      Process finalProcess = process;
      Future<?> outputFuture =
          executor.submit(
              () -> {
                readStream(finalProcess.getInputStream(), result, charset, false);
                readStream(finalProcess.getErrorStream(), result, charset, true);
              });

      // 等待执行完成或超时
      boolean finished = process.waitFor(timeout, unit);
      if (!finished) {
        process.destroyForcibly(); // 超时强制终止
        result.append("[警告] 命令执行超时（已强制终止）\n");
      }

      outputFuture.get(1, TimeUnit.SECONDS); // 等待流读取完成
      result.append("\n[进程退出码]: ").append(process.exitValue());
    } catch (Exception e) {
      result.append("[执行异常]: ").append(e.getMessage()).append("\n");
    } finally {
      executor.shutdownNow();
      if (process != null) process.destroy();
    }

    return result.toString();
  }

  // 读取流内容到字符串
  private static void readStream(
      InputStream is, StringBuilder result, String charset, boolean isError) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset))) {
      String line;
      while ((line = reader.readLine()) != null) {
        synchronized (result) {
          result.append(isError ? "[错误] " : "").append(line).append("\n");
        }
      }
    } catch (IOException e) {
      result.append("[流读取异常]: ").append(e.getMessage()).append("\n");
    }
  }
}
```

| 功能           | 实现说明                                                     |
| -------------- | ------------------------------------------------------------ |
| ⏱ 超时控制     | 使用 `process.waitFor(timeout, TimeUnit.SECONDS)` 控制执行时长 |
| 🧵 异步读取输出 | 通过线程池异步读取 stdout 与 stderr，防止阻塞                |
| 🧹 安全退出     | 超时后强制销毁子进程 `process.destroyForcibly()`             |
| 🔤 编码兼容     | 自动识别系统默认编码，避免中文乱码                           |
| 🪶 可扩展       | 可轻松封装成工具类供业务调用                                 |



## 三、ProcessBuilder 使用详解

### 1. 基础用法

```java
// Windows 示例
ProcessBuilder pb1 = new ProcessBuilder("cmd", "/c", "dir");
Process process = pb1.start();

// Linux/macOS 示例
ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "ls -l");
Process process = pb.start();
```

### 2. 核心功能配置

```java
// 设置工作目录
pb.directory(new File("D:/workspace"));

// 合并错误输出到标准输出（避免死锁）
pb.redirectErrorStream(true);

// 设置环境变量
Map<String, String> env = pb.environment();
env.put("APP_ENV", "test");//添加环境变量
env.remove("TEMP"); // 移除环境变量


// 重定向输出到文件
pb.redirectOutput(new File("command-output.txt"));
```



> **工程建议：**
>
> `redirectErrorStream(true)` 是生产环境默认选择。

```java
ProcessBuilder pb = new ProcessBuilder();

// 创建ProcessBuilder对象并设置命令
pb.command("cmd.exe", "/c", "echo %MY_VAR%");
// pb.command("/bin/bash", "-c", "echo $MY_VAR"); // Linux版本

// 设置环境变量
pb.environment().put("MY_VAR", "HelloFromProcessBuilder");
// 设置工作目录
pb.directory(new File("F:\\gg\\ing"));

// 合并错误流和输出流
pb.redirectErrorStream(true);

// 启动进程
Process process = pb.start();

// 读取输出
try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
    reader.lines().forEach(System.out::println);
}

process.waitFor();
```




### 3.ProcessBuilder IO 处理

#### 1. IO 处理概览

`ProcessBuilder` 在启动外部进程时，**核心难点不在命令本身，而在 IO（输入 / 输出 / 错误流）处理**。

Java 为子进程提供了三类 IO 处理方式：

| 方式          | 说明                |
| ------------- | ------------------- |
| PIPE（默认）  | Java 程序通过流读取 |
| Redirect      | 重定向到文件        |
| **inheritIO** | 直接交给父进程      |

其中，**`inheritIO()` 是最简单、最容易忽略但非常实用的一种方式**。

------

#### 2. inheritIO 是什么？

```java
ProcessBuilder inheritIO()
```

> **作用：让子进程完全继承父进程（当前 JVM）的 IO**

即：

| 子进程流 | 实际流向                |
| -------- | ----------------------- |
| stdin    | 当前 Java 进程的 stdin  |
| stdout   | 当前 Java 进程的 stdout |
| stderr   | 当前 Java 进程的 stderr |

📌 **执行效果等同于你在终端里直接敲这条命令**

------

#### 3. inheritIO 的底层等价写法（原理）

```java
pb.inheritIO();
```

等价于：

```java
pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
pb.redirectError(ProcessBuilder.Redirect.INHERIT);
```

👉 `inheritIO()` 本质是一个 **IO 处理的快捷封装**。

------

#### 4. 最典型使用示例

##### 4.1 像命令行一样执行命令（调试首选）

```java
ProcessBuilder pb =
        new ProcessBuilder("java", "-version");

pb.inheritIO();
Process process = pb.start();
process.waitFor();
```

**特点：**

- 不需要 `getInputStream()`
- 不需要单独处理 stderr
- 输出直接显示在控制台

------

##### 4.2 ffmpeg / 音视频处理（实时进度）

```java
ProcessBuilder pb = new ProcessBuilder(
        "ffmpeg", "-i", "a.m4a", "a.wav"
);

pb.inheritIO();
pb.start().waitFor();
```

📌 非常适合：

- 音视频转码
- 实时进度输出
- CLI 工具封装

------

#### 5. inheritIO 与其他 IO API 的关系

##### 5.1 inheritIO vs redirectErrorStream

| 组合方式                                    | 实际效果                 |
| ------------------------------------------- | ------------------------ |
| `inheritIO()`                               | stdout / stderr 各自输出 |
| `redirectErrorStream(true)`                 | stderr 合并到 stdout     |
| `inheritIO()` + `redirectErrorStream(true)` | ❌ 无意义                 |
| `inheritIO()` + `redirectOutput(File)`      | ⚠️ 后者覆盖前者           |

📌 **inheritIO 是一次性全继承，优先级低于单独 redirect**

------

#### 6. inheritIO 的优缺点（工程视角）

##### ✅ 优点

- 代码极简
- 无需处理流
- 不存在缓冲区阻塞
- 调试体验极好

##### ❌ 缺点

- 无法程序化获取输出
- 不适合日志分析
- 不适合后台 / Web 服务

------

#### 7. 使用场景建议

##### 7.1 推荐使用 inheritIO 的场景

| 场景               |
| ------------------ |
| 本地工具           |
| CLI 程序           |
| 开发调试           |
| 构建 / 编译工具    |
| ffmpeg / git / mvn |

------

##### 7.2 不推荐使用 inheritIO 的场景

| 场景         | 原因       |
| ------------ | ---------- |
| Web 服务     | 输出不可控 |
| 后台任务     | 日志难管理 |
| 需要解析输出 | 无法读取流 |
| 高并发执行   | IO 混乱    |

------

#### 8. inheritIO vs 手动读取流（对照）

| 维度       | inheritIO | 手动处理流 |
| ---------- | --------- | ---------- |
| 代码复杂度 | ⭐         | ⭐⭐⭐        |
| 阻塞风险   | 无        | 有         |
| 可控性     | ❌         | ✅          |
| 可解析输出 | ❌         | ✅          |
| 适合生产   | ❌         | ✅          |

------

#### 9. 常见误区（重点）

##### ❌ 误区 1：使用 inheritIO 后仍读取流

```java
pb.inheritIO();
process.getInputStream(); // ❌ 无意义
```

👉 **继承后，流已由父进程接管**

------

##### ❌ 误区 2：服务端程序使用 inheritIO

> 日志直接输出到控制台，难以监控、归档和分析

------

#### 10. 本节总结（手册级）

> `inheritIO()` 是 `ProcessBuilder` 中**最简单的 IO 处理方式**，
> 适合 **调试、命令行工具、音视频处理等交互式场景**；
> **一旦需要日志、解析或后台运行，应避免使用 inheritIO**。



### 4.完整案例

```java
/** ProcessBuilder 完整用法学习指南. 
假设在 'target/other-program.jar' 存在一个可执行的 JAR 文件. 
*/
public class ProcessBuilderCompleteStudy {

  // --- 全局配置 ---
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
  private static final Path BASE_DIR = Paths.get(System.getProperty("user.dir"));
  private static final Path JAR_PATH = BASE_DIR.resolve("target").resolve("other-program.jar");
  private static final String JAVA_EXECUTABLE =
      Paths.get(System.getProperty("java.home"), "bin", "java").toString();

  public static void main(String[] args) throws Exception {

    // 确保我们的演示 JAR 存在
    if (!JAR_PATH.toFile().exists()) {
      System.err.println("错误: 演示 JAR 文件未找到: " + JAR_PATH.toAbsolutePath());
      System.err.println("请先准备一个可运行的 'target/other-program.jar' 以运行此示例.");
      // return; // 在实际运行中取消注释
    }

    runExample1_RedirectToFileAndEnv(); 
    runExample2_InheritIO();
    runExample3_ReadOutputInMemory();
    runExample4_WriteToInput();
    runExample5_MergeErrorStream();
  }

  /**
   * 示例 1: 重定向 I/O 到文件, 传递参数, 并设置环境变量.
   *
   * <p>适用场景: 运行批处理作业, 记录日志, 不需要与进程实时交互.
   */
  public static void runExample1_RedirectToFileAndEnv() throws IOException {
    printHeader("示例 1: 重定向 I/O 到文件, 设置环境变量, 异步超时");

    Path outputFile = BASE_DIR.resolve("example1.output.log");
    Path errorFile = BASE_DIR.resolve("example1.error.log");

    // 1. 构建命令 (包含 JAR 的参数)
    List<String> command =
        List.of(
            JAVA_EXECUTABLE,
            "-jar",
            JAR_PATH.toString(),
            "arg1", // 传递给 JAR 的参数
            "--error" // 模拟一个产生错误的参数
            );
    ProcessBuilder pb = new ProcessBuilder(command);

    // 2. 设置工作目录
    pb.directory(BASE_DIR.toFile());

    // 3. 设置环境变量 (关键优化)
    Map<String, String> env = pb.environment();
    env.put("MY_CUSTOM_VAR", "HelloFromProcessBuilder"); // 添加或修改环境变量
    // env.remove("PATH"); // 也可以移除变量

    // 4. 重定向 I/O (追加模式)
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(errorFile.toFile()));

    Process process = null;
    try {
      process = pb.start();
      ProcessHandle handle = process.toHandle();
      System.out.println("  [示例 1] 进程启动, PID: " + handle.pid());

      // 5. 异步等待和超时 (v3 优化)
      CompletableFuture<ProcessHandle> onExit =
          handle.onExit().orTimeout(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      onExit.get(); // 阻塞等待, 直到进程退出或超时

      System.out.println("  [示例 1] 进程结束, 退出码: " + process.exitValue());
      System.out.println("  [示例 1] 输出已追加到: " + outputFile.getFileName());
      System.out.println("  [示例 1] 错误已追加到: " + errorFile.getFileName());

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /** 示例 2: 继承 I/O. * 适用场景: 运行一个工具 (如 'mvn', 'git', 'npm'), 并希望其输出/错误/输入直接连接到当前 Java 程序的控制台. */
  public static void runExample2_InheritIO() {
    printHeader("示例 2: 继承 I/O (输出将直接显示在下方)");

    ProcessBuilder pb =
        new ProcessBuilder(JAVA_EXECUTABLE, "-jar", JAR_PATH.toString(), "arg-for-inherit");

    // 关键: 将子进程的 stdout, stderr, stdin
    //       与当前 Java 进程的 System.out, System.err, System.in 绑定.
    pb.inheritIO();

    try {
      Process process = pb.start();

      // 对于 inheritIO, 我们通常只关心它何时结束.
      int exitCode = process.waitFor();

      System.out.println("\n  [示例 2] 继承的进程结束, 退出码: " + exitCode);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * 示例 3: 在内存中读取 I/O (标准输出和标准错误). 
   * 适用场景: 运行一个命令并需要捕获其输出 (例如, 运行 'git status') 以在 Java 程序中解析结果. 
   * 陷阱警告: 必须并发读取 stdout 和 stderr, 否则可能导致死锁! (如果子进程写满了 stdout 缓冲区, 它会等待被读取, 此时如果主线程在等待读取 stderr,
   * 双方将永远等待下去).
   */
  public static void runExample3_ReadOutputInMemory() {
    printHeader("示例 3: 在内存中读取 I/O (并发读取)");

    ProcessBuilder pb =
        new ProcessBuilder(
            JAVA_EXECUTABLE,
            "-jar",
            JAR_PATH.toString(),
            "arg-for-memory",
            "--error" // 确保 stderr 也有输出
            );

    Process process = null;
    try {
      process = pb.start();

      // 关键: 不使用 redirect. 而是获取输入流.
      InputStream stdOutStream = process.getInputStream();
      InputStream stdErrStream = process.getErrorStream();

      // 使用 CompletableFuture 异步读取流 (避免死锁)
      CompletableFuture<String> stdOutFuture = readStreamAsync(stdOutStream);
      CompletableFuture<String> stdErrFuture = readStreamAsync(stdErrStream);

      // 等待进程结束
      boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      if (!finished) {
        System.err.println("  [示例 3] 进程超时!");
        forcefullyTerminate(process);
      } else {
        // 等待异步读取完成
        String output = stdOutFuture.get();
        String error = stdErrFuture.get();

        System.out.println("  [示例 3] 进程结束, 退出码: " + process.exitValue());
        System.out.println(
            "  [示例 3] 捕获的 Stdout:\n--- (stdout) ---\n" + output + "--- (stdout end) ---");
        System.out.println(
            "  [示例 3] 捕获的 Stderr:\n--- (stderr) ---\n" + error + "--- (stderr end) ---");
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * 示例 4: 向进程写入数据 (标准输入 stdin). 
   * 适用场景: 运行需要交互式输入的程序 (例如, 提示输入 'yes/no'). 这里我们使用一个简单的系统命令 'cat -n'
   * (Linux/macOS) 或 'findstr /N ^' (Windows) 来演示, 它们会给输入加上行号并回显.
   */
  public static void runExample4_WriteToInput() {
    printHeader("示例 4: 向进程写入 Stdin (管道输入)");

    // 构造一个能回显 stdin 的 OS 特定命令
    boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    List<String> command = isWindows ? List.of("cmd", "/c", "findstr /N ^") : List.of("cat", "-n");

    ProcessBuilder pb = new ProcessBuilder(command);
    Process process = null;

    try {
      process = pb.start();

      // 1. 获取进程的输出流 (即它的 stdin)
      OutputStream stdin = process.getOutputStream();
      // 2. 获取进程的输入流 (即它的 stdout)
      InputStream stdout = process.getInputStream();

      // 3. 异步读取进程的 stdout (这样我们写入时它就不会阻塞)
      CompletableFuture<String> outputFuture = readStreamAsync(stdout);

      // 4. (在主线程) 向进程的 stdin 写入数据
      // 使用 try-with-resources 确保流被关闭
      try (BufferedWriter writer =
          new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8))) {
        writer.write("这是第一行\n");
        writer.write("这是第二行\n");
        writer.write("Hello Process!\n");

        // 关键: 必须 flush() 和 close()
        // close() 会向子进程发送 EOF (文件结束) 信号,
        // 告诉 'cat' 或 'findstr' 我们已经写完了.
      }

      // 5. 等待进程终止
      process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      // 6. 获取异步读取的结果
      String output = outputFuture.get();
      System.out.println("  [示例 4] 进程结束, 退出码: " + process.exitValue());
      System.out.println("  [示例 4] 进程的响应 (带行号):\n" + output);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /** 示例 5: 合并标准错误到标准输出. * 适用场景: 类似 示例 3, 但你不在乎输出是来自 stdout 还是 stderr, 你只想按时间顺序捕获所有输出. */
  public static void runExample5_MergeErrorStream() {
    printHeader("示例 5: 合并 Stderr 到 Stdout");

    ProcessBuilder pb =
        new ProcessBuilder(
            JAVA_EXECUTABLE, "-jar", JAR_PATH.toString(), "arg-for-merge", "--error" // 产生 stderr 输出
            );

    // 关键: 将 stderr 合并到 stdout
    pb.redirectErrorStream(true);

    Process process = null;
    try {
      process = pb.start();

      // 因为合并了, 我们只需要读取 getInputStream()
      InputStream mergedStream = process.getInputStream();

      // 注意: getErrorStream() 现在将返回 null
      // InputStream stdErrStream = process.getErrorStream(); // (stdErrStream == null)

      CompletableFuture<String> mergedOutputFuture = readStreamAsync(mergedStream);

      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        System.err.println("  [示例 5] 进程超时!");
        forcefullyTerminate(process);
      } else {
        String output = mergedOutputFuture.get();
        System.out.println("  [示例 5] 进程结束, 退出码: " + process.exitValue());
        System.out.println(
            "  [示例 5] 捕获的合并后输出:\n--- (merged output) ---\n" + output + "--- (merged end) ---");
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // --- 辅助方法 ---

  /** (辅助) 打印一个清晰的标题头 */
  private static void printHeader(String title) {
    System.out.println("\n" + "===========================");
    System.out.println(" " + title);
    System.out.println("===========================");
  }

  /** (辅助) 健壮地终止一个进程 (先礼后兵) */
  private static void forcefullyTerminate(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    try {
      System.err.println("  [辅助] 尝试正常终止 (SIGTERM)...");
      process.destroy();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        System.err.println("  [辅助] 正常终止失败, 强制终止 (SIGKILL)...");
        process.destroyForcibly();
      } else {
        System.err.println("  [辅助] 进程已终止.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /**
   * (辅助) 异步读取 InputStream 并返回一个包含其所有内容的 String 的 Future. 这是处理进程流以避免死锁的标准模式 (又名 "StreamGobbler").
   */
  private static CompletableFuture<String> readStreamAsync(InputStream is) {
    // 使用 supplyAsync 在 Java 公共的 ForkJoinPool 中运行此任务
    return CompletableFuture.supplyAsync(
        () -> {
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            // readLine() 是阻塞的, 这就是为什么它必须在单独的线程中
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
          } catch (IOException e) {
            return "读取流时出错: " + e.getMessage();
          }
        });
  }
}

```



## 四、CMD 与 Shell 执行机制

### 命令类型区分

1. **内部命令**（如 `dir`, `cd`, `echo`）
   1. 内置在 `cmd.exe` 或 `shell` 中
   2. 必须通过解释器执行：`cmd /c dir` 或 `/bin/sh -c "cd /tmp"`
2. **外部命令**（如 `ping.exe`, `ls`）
   1. 独立可执行文件（.exe, .com 等）
   2. 可直接调用：`new ProcessBuilder("ping", "127.0.0.1")`

### Windows
- 内部命令如 `dir`, `echo` 必须通过 `cmd.exe` 解释执行。
- 外部程序（如 `notepad.exe ping`）可直接调用。

```cmd
cmd /c dir       // 执行后关闭窗口
cmd /k dir       // 执行后保持窗口

/c → 批处理或快捷方式一次性命令。
/k → 调试或希望执行后继续操作。
/q → 输出干净，不想显示“Microsoft Windows”开头。
/s → 命令里有引号或特殊字符时用，防止报错。
```

### Linux / macOS

- Shell 命令通过 `/bin/sh` 或 `/bin/bash` 执行。

```bash
/bin/sh -c "ls -l | grep txt"
```

---

### 环境变量作用

- **`PATH`**：系统查找可执行文件的目录列表
- **`PATHEXT`**：Windows 下自动补全的文件扩展名（.exe, .bat 等）
- 示例：输入 `ping` 时，系统会在 `PATH` 目录中查找 `ping.exe`
- 对于不同的操作系统来说， 环境变量的处理可能会有一些不统一的地方， 比如说： 不区分大小写 等等。

> 环境变量不生效排查
>
> + 环境变量未生效  通过**检查打印当时环境变量**
> + **检查环境变量是否配置**或者i**dea重启，生效变量**

| 功能                                            | Windows 命令                                        | Linux 命令                                          | 说明                                    |
| ----------------------------------------------- | --------------------------------------------------- | --------------------------------------------------- | --------------------------------------- |
| **查看所有环境变量**                            | `set`                                               | `printenv` 或 `env`                                 | 显示当前用户的所有环境变量              |
| **查看单个环境变量**                            | `echo %VAR_NAME%`                                   | `echo $VAR_NAME`                                    | 查看指定环境变量值，如 PATH、JAVA_HOME  |
| **查看系统信息**                                | `systeminfo`                                        | `uname -a` / `cat /etc/os-release`                  | 包含操作系统版本、内核、安装日期等      |
| **查看内核版本**                                | 无单独命令，`systeminfo` 包含部分信息               | `uname -r`                                          | 仅显示内核版本                          |
| **查看CPU信息**                                 | 无单独命令，部分信息在 `systeminfo`                 | `lscpu`                                             | 显示 CPU 架构、核心数、型号             |
| **查看内存信息**                                | `systeminfo`                                        | `free -h`                                           | 显示总内存、可用内存、缓存等            |
| **查看磁盘信息**                                | `wmic logicaldisk get size,freespace,caption`       | `df -h`                                             | 显示磁盘容量和剩余空间                  |
| **查看网络信息**                                | `ipconfig`                                          | `ifconfig` 或 `ip addr`                             | 显示 IP 地址、子网掩码、网关            |
| **查看当前用户**                                | `echo %USERNAME%`                                   | `whoami`                                            | 显示当前登录用户名                      |
| **查看当前 Shell**                              | 无直接命令                                          | `echo $SHELL`                                       | 显示当前使用的 shell                    |
| **查看用户所有环境变量（包含 shell 脚本配置）** | 无直接命令                                          | `set`                                               | 包括系统变量和用户自定义变量            |
| **配置/修改环境变量**                           | 系统界面：控制面板 → 系统 → 高级系统设置 → 环境变量 | 编辑 `~/.bashrc`, `~/.bash_profile`, `/etc/profile` | 修改后 Linux 用 `source ~/.bashrc` 生效 |



Java 获取环境变量的方式也很简单：

```java
//System.getEnv()  得到所有的环境变量
//System.getEnv(key) 得到某个环境变量的值

Map map = System.getenv();
    Iterator it = map.entrySet().iterator();
    while(it.hasNext()) {
        Entry entry = (Entry)it.next();
        System.out.print(entry.getKey()+"=");
        System.out.println(entry.getValue());
    }
```

 系统变量除了可以获取之外， 还可以通过System.setProperty(key, value)  的方式设置自己需要的系统变量。

默认情况下， java 设置了哪些系统变量：

```java
//System.getProperties()  得到所有的系统变量
//System.getProperty(key)  得到某个系统变量的值   

Properties properties = System.getProperties();
    Iterator it =  properties.entrySet().iterator();
    while(it.hasNext()){
        Entry entry = (Entry)it.next();
        System.out.print(entry.getKey()+"=");
        System.out.println(entry.getValue());
}
```

```java
java.version Java 运行时环境版本
java.vendor Java 运行时环境供应商
java.vendor.url Java 供应商的 URL
java.home Java 安装目录
java.vm.specification.version Java 虚拟机规范版本
java.vm.specification.vendor Java 虚拟机规范供应商
java.vm.specification.name Java 虚拟机规范名称
java.vm.version Java 虚拟机实现版本
java.vm.vendor Java 虚拟机实现供应商
java.vm.name Java 虚拟机实现名称
java.specification.version Java 运行时环境规范版本
java.specification.vendor Java 运行时环境规范供应商
java.specification.name Java 运行时环境规范名称
java.class.version Java 类格式版本号
java.class.path Java 类路径
java.library.path 加载库时搜索的路径列表
java.io.tmpdir 默认的临时文件路径
java.compiler 要使用的 JIT 编译器的名称
java.ext.dirs 一个或多个扩展目录的路径
os.name 操作系统的名称
os.arch 操作系统的架构
os.version 操作系统的版本
file.separator 文件分隔符(在 UNIX 系统中是"/")
path.separator 路径分隔符(在 UNIX 系统中是":")
line.separator 行分隔符(在 UNIX 系统中是"/n")
user.name 用户的账户名称
user.home 用户的主目录
user.dir 用户的当前工作目录
```

### Windows常用命令

#### 一、目录与文件操作

| 命令          | 作用                   | 示例                         |
| ------------- | ---------------------- | ---------------------------- |
| `dir`         | 查看当前目录下的文件   | `dir`                        |
| `cd`          | 进入或返回上级目录     | `cd C:\Users` / `cd ..`      |
| `mkdir`       | 创建文件夹             | `mkdir logs`                 |
| `del`         | 删除文件               | `del test.txt`               |
| `rmdir /s /q` | 删除文件夹（含子目录） | `rmdir /s /q old_backup`     |
| `copy`        | 复制文件               | `copy a.txt D:\backup\a.txt` |
| `move`        | 移动文件               | `move a.txt D:\data`         |
| `ren`         | 重命名                 | `ren old.txt new.txt`        |

------

#### 二、系统与进程命令

| 命令               | 说明               | 示例                          |
| ------------------ | ------------------ | ----------------------------- |
| `tasklist`         | 查看当前进程       | `tasklist`                    |
| `taskkill`         | 结束指定进程       | `taskkill /PID 1234 /F`       |
| `systeminfo`       | 查看系统详细信息   | `systeminfo`                  |
| `set`              | 查看或设置环境变量 | `set JAVA_HOME=C:\Java\jdk17` |
| `echo %JAVA_HOME%` | 查看环境变量值     | `echo %JAVA_HOME%`            |
| `cls`              | 清屏               | `cls`                         |

#### 三、网络相关命令（Java开发常用）

| 命令           | 说明                | 示例                     |
| -------------- | ------------------- | ------------------------ |
| `ping`         | 测试网络连通性      | `ping www.baidu.com`     |
| `ipconfig`     | 查看本机IP信息      | `ipconfig /all`          |
| `netstat -ano` | 查看端口占用与PID   | `netstat -ano            |
| `tracert`      | 跟踪网络路径        | `tracert www.google.com` |
| `nslookup`     | 查询DNS解析         | `nslookup www.baidu.com` |
| `arp -a`       | 查看局域网ARP缓存表 | `arp -a`                 |
| `route print`  | 查看路由表          | `route print`            |

------

#### 四、程序运行相关（Java开发非常常用）

| 命令                | 说明             | 示例                            |
| ------------------- | ---------------- | ------------------------------- |
| `javac`             | 编译 Java 源码   | `javac Hello.java`              |
| `java`              | 运行 Java 程序   | `java Hello`                    |
| `mvn compile`       | Maven 编译       | `mvn compile`                   |
| `mvn clean package` | Maven 打包       | `mvn clean package -DskipTests` |
| `java -jar`         | 运行 jar 文件    | `java -jar app.jar`             |
| `set PATH`          | 临时添加环境变量 | `set PATH=%PATH%;C:\Java\bin`   |

------

####  五、系统管理与调试命令

| 命令           | 说明           | 示例                           |
| -------------- | -------------- | ------------------------------ |
| `chkdsk`       | 检查磁盘       | `chkdsk C:`                    |
| `sfc /scannow` | 系统文件修复   | `sfc /scannow`                 |
| `shutdown`     | 关机/重启      | `shutdown /r /t 0`（立即重启） |
| `taskmgr`      | 打开任务管理器 | `taskmgr`                      |
| `services.msc` | 打开服务管理器 | `services.msc`                 |
| `regedit`      | 打开注册表     | `regedit`                      |

------

#### 六、实用技巧（开发常用快捷用法）

| 功能             | 命令                     | 说明                     |
| ---------------- | ------------------------ | ------------------------ |
| 一行执行多个命令 | `cmd1 && cmd2`           | 前成功才执行后面         |
| 忽略错误继续执行 | `cmd1 & cmd2`            | 不论成功失败都继续       |
| 重定向输出       | `> log.txt / >> log.txt` | 输出写入文件             |
| 管道传递         | `cmd1 | cmd2`            | 前命令输出作为后命令输入 |
| 暂停执行         | `pause`                  | 等待按键继续             |
| 执行脚本         | `mytool.bat`             | 执行批处理文件           |



#### 七、Java 项目常用 CMD 实践示例

##### 1️⃣ 编译 + 运行 Java 文件

```cmd
cd C:\Users\gouwe\Desktop\mytool && javac PortChecker.java && java PortChecker
```

##### 2️⃣ 查看某端口是否被占用

```cmd
netstat -ano | findstr :8080
```

##### 3️⃣ 杀掉占用端口的进程

```cmd
for /f "tokens=5" %a in ('netstat -ano ^| findstr :8080') do taskkill /F /PID %a
```

##### 4️⃣ 打包并运行 Spring Boot 应用

```cmd
mvn clean package -DskipTests && java -jar target\myapp.jar
```

------

####  八、批处理脚本示例（保存为 `.bat`）

```bat
@echo off
echo 编译并运行 Java 程序...
cd C:\Users\gouwe\Desktop\mytool
javac PortChecker.java
if %errorlevel% neq 0 (
    echo 编译失败！
    pause
    exit /b
)
java PortChecker
pause
```

### Linux命令

#### **1️⃣ 文件与目录操作**

| 命令       | 作用                          | 示例                         |
| ---------- | ----------------------------- | ---------------------------- |
| `ls -lh`   | 列出文件/目录，显示大小和权限 | `ls -lh /home/user`          |
| `cd`       | 切换目录                      | `cd /opt/java`               |
| `pwd`      | 显示当前目录路径              | `pwd`                        |
| `mkdir -p` | 创建目录（可创建多级）        | `mkdir -p /opt/java/project` |
| `rm -rf`   | 删除文件或目录                | `rm -rf /tmp/test`           |
| `cp -r`    | 复制文件/目录                 | `cp -r src/ backup/`         |
| `mv`       | 移动或重命名                  | `mv old.jar new.jar`         |

------

#### **2️⃣ 文件查看与编辑**

| 命令              | 作用                 | 示例                                    |
| ----------------- | -------------------- | --------------------------------------- |
| `cat`             | 查看文件内容         | `cat pom.xml`                           |
| `less`            | 分页查看文件         | `less /var/log/syslog`                  |
| `tail -f`         | 实时查看文件新增内容 | `tail -f /opt/tomcat/logs/catalina.out` |
| `head`            | 查看文件前几行       | `head -n 20 build.log`                  |
| `grep`            | 文件内容搜索         | `grep "Exception" app.log`              |
| `vi / vim / nano` | 编辑文件             | `vim application.properties`            |

------

#### **3️⃣ 系统与进程管理**

| 命令          | 作用                   | 示例                 |
| ------------- | ---------------------- | -------------------- |
| `top`         | 实时查看系统进程/资源  | `top`                |
| `htop`        | 增强版 top，可交互操作 | `htop`               |
| `ps -ef`      | 查看进程列表           | `ps -ef              |
| `kill -9 PID` | 强制结束进程           | `kill -9 12345`      |
| `free -h`     | 查看内存使用情况       | `free -h`            |
| `df -h`       | 查看磁盘空间           | `df -h`              |
| `du -sh`      | 查看目录大小           | `du -sh /opt/tomcat` |

------

#### **4️⃣ 网络相关**

| 命令             | 作用                   | 示例                                      |
| ---------------- | ---------------------- | ----------------------------------------- |
| `ping`           | 测试网络连通性         | `ping www.baidu.com`                      |
| `netstat -tulnp` | 查看端口占用           | `netstat -tulnp                           |
| `ss -tulnp`      | 替代 netstat，查看端口 | `ss -tulnp`                               |
| `curl`           | 测试 HTTP 接口         | `curl http://localhost:8080/health`       |
| `wget`           | 下载文件               | `wget https://repo1.maven.org/maven2/...` |

------

#### **5️⃣ 压缩与归档**

| 命令                         | 作用          | 示例                                        |
| ---------------------------- | ------------- | ------------------------------------------- |
| `tar -czvf file.tar.gz dir/` | 打包并压缩    | `tar -czvf backup.tar.gz /opt/java/project` |
| `tar -xzvf file.tar.gz`      | 解压 tar.gz   | `tar -xzvf backup.tar.gz`                   |
| `zip / unzip`                | zip 压缩/解压 | `zip -r project.zip src/`                   |

------

#### **6️⃣ 权限与用户管理**

| 命令    | 作用             | 示例                               |
| ------- | ---------------- | ---------------------------------- |
| `chmod` | 修改权限         | `chmod 755 run.sh`                 |
| `chown` | 修改文件所有者   | `chown tomcat:tomcat catalina.out` |
| `sudo`  | 提升权限执行命令 | `sudo systemctl restart tomcat`    |

------

#### **7️⃣ Java 环境 & 构建相关**

| 命令                | 作用           | 示例                            |
| ------------------- | -------------- | ------------------------------- |
| `java -version`     | 查看 Java 版本 | `java -version`                 |
| `javac -version`    | 查看编译器版本 | `javac -version`                |
| `javac`             | 编译 Java 文件 | `javac HelloWorld.java`         |
| `java`              | 运行 Java 程序 | `java HelloWorld`               |
| `jar -cvf / -xvf`   | 打包/解包 jar  | `jar -cvf app.jar *.class`      |
| `mvn clean install` | Maven 构建项目 | `mvn clean install -DskipTests` |
| `gradle build`      | Gradle 构建    | `gradle build`                  |

------

#### **8️⃣ 日志与调试辅助**

| 命令                 | 作用                   | 示例                                    |
| -------------------- | ---------------------- | --------------------------------------- |
| `tail -f`            | 实时查看日志           | `tail -f /opt/tomcat/logs/catalina.out` |
| `grep -R "关键字" .` | 递归搜索               | `grep -R "Exception" ./target`          |
| `watch`              | 周期执行命令           | `watch -n 2 "ps -ef                     |
| `lsof -i :端口`      | 查看端口被哪个进程占用 | `lsof -i :8080`                         |



## 五、Apache Commons Exec 实践


 `Apache Commons Exec` 是 Apache Commons 项目中的一个专门用于**执行外部进程**的库，它在功能上类似于 `Runtime.exec()` 或 `ProcessBuilder`，但**更强大、更安全、更易扩展**。

### 一、简介

`Apache Commons Exec` 是对 Java 自带进程管理 API 的高级封装，
 提供了以下功能增强：

| 功能                 | 说明                                            |
| -------------------- | ----------------------------------------------- |
| ✅ 超时控制           | 使用 `ExecuteWatchdog` 自动终止长时间运行的进程 |
| ✅ 异步执行           | 支持异步命令执行与结果回调                      |
| ✅ 环境变量与工作目录 | 灵活设置运行环境                                |
| ✅ 跨平台支持         | 同时兼容 Windows / Linux / macOS                |
| ✅ 输出流与错误流管理 | 可自定义日志记录或输出重定向                    |

> 💡 推荐用于需要执行外部命令、脚本、系统工具等的生产环境项目。

------

### 二、依赖引入

如果使用 Maven：

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-exec</artifactId>
    <version>1.4.0</version>
</dependency>
```

Gradle：

```gradle
implementation 'org.apache.commons:commons-exec:1.4.0'
```

------

### 三、基础使用示例

#### 1️⃣ 执行命令并输出结果

```java
CommandLine cmd = new CommandLine("ping");
cmd.addArgument("www.baidu.com");

DefaultExecutor executor = new DefaultExecutor();
int exitCode = executor.execute(cmd);
System.out.println("退出码: " + exitCode);
```

> `DefaultExecutor.execute()` 会阻塞直到命令执行结束。

------

#### 2️⃣ 异步执行命令（非阻塞）

```java
CommandLine cmd = new CommandLine("ping");
cmd.addArgument("www.baidu.com");

// 异步结果处理器
DefaultExecuteResultHandler handler = new DefaultExecuteResultHandler() {
    @Override
    public void onProcessComplete(int exitValue) {
        System.out.println("执行完成，退出码: " + exitValue);
    }
    
    @Override
    public void onProcessFailed(ExecuteException e) {
        System.err.println("执行失败: " + e.getMessage());
    }
};

DefaultExecutor executor = new DefaultExecutor();
executor.execute(cmd, handler);
handler.waitFor(); // 等待执行完成
```

> 适合需要后台执行的任务，例如文件压缩、网络探测等。

------

#### 3️⃣ 超时控制（防止命令卡死）

```java
CommandLine cmd = new CommandLine("ping");
cmd.addArgument("www.baidu.com");

// 5秒超时
ExecuteWatchdog watchdog = new ExecuteWatchdog(5000);
DefaultExecutor executor = new DefaultExecutor();
executor.setWatchdog(watchdog);

try {
    executor.execute(cmd);
} catch (ExecuteException e) {
    if (watchdog.killedProcess()) {
        System.err.println("命令超时被终止");
    }
}
```

------

#### 4️⃣ 捕获标准输出与错误输出

```java
CommandLine cmd = new CommandLine("ping");
cmd.addArgument("www.google.com");

ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream, errorStream);
DefaultExecutor executor = new DefaultExecutor();
executor.setStreamHandler(streamHandler);

int exitCode = executor.execute(cmd);
System.out.println(\"标准输出：\\n\" + outputStream.toString());
System.out.println(\"错误输出：\\n\" + errorStream.toString());
```

> `PumpStreamHandler` 用于管理输入输出流，可替代 `System.out`，也可重定向到文件。

------

#### 5️⃣ 指定工作目录与环境变量

```java
CommandLine cmd = new CommandLine("ls");
cmd.addArgument("-l");

DefaultExecutor executor = new DefaultExecutor();
executor.setWorkingDirectory(new File(\"/home/user\")); // 设置目录

Map<String, String> env = new HashMap<>(System.getenv());
env.put(\"APP_MODE\", \"debug\");

int exitCode = executor.execute(cmd, env);
```

------

### 四、常见应用场景

| 场景       | 示例                                 |
| ---------- | ------------------------------------ |
| 文件压缩   | 调用 `tar`, `zip`, `7z` 命令         |
| 网络诊断   | 执行 `ping`, `traceroute`, `netstat` |
| 系统监控   | 运行 `top`, `ps`, `tasklist`         |
| 任务自动化 | 调用批处理、Shell 脚本或 Python 脚本 |
| 部署脚本   | CI/CD 环境执行构建或部署命令         |

------

### 五、最佳实践建议

1. **优先使用 `CommandLine` 构造参数**，避免字符串拼接造成注入风险；
2. **始终使用 `ExecuteWatchdog`** 防止命令卡死；
3. **通过 `PumpStreamHandler` 捕获输出**，不要使用 `System.out` 直写；
4. **记录日志**（命令、退出码、输出内容）；
5. **针对跨平台命令封装**，在调用前判断系统类型。

------

### 六、总结

| 功能            | 说明                                       |
| --------------- | ------------------------------------------ |
| 同步 / 异步执行 | ✅ 支持                                     |
| 超时控制        | ✅ 内置 `ExecuteWatchdog`                   |
| I/O 流管理      | ✅ `PumpStreamHandler`                      |
| 环境变量        | ✅ 支持自定义                               |
| 工作目录        | ✅ 支持设置                                 |
| 推荐场景        | 高可靠任务执行、运维脚本调用、网络工具封装 |

---

## 六、Java Shell 命令工具类封装

### 跨平台命令执行工具类

```java
/** 命令执行工具类：用于执行系统命令并读取输出结果 支持 Windows (cmd) 和 Unix (sh) 系统，自动适配命令解释器 */
public class CommandExecutor {

  // 默认超时时间（秒）
  private static final int DEFAULT_TIMEOUT = 30;
  // 默认字符集（Windows 常用 GBK，其他系统常用 UTF-8）
  private static final Charset DEFAULT_CHARSET =
      isWindows() ? Charset.forName("GBK") : StandardCharsets.UTF_8;

  public static void main(String[] args) {
    // 示例1：执行简单命令（Windows 示例）
    CommandExecutor.CommandResult result1 = CommandExecutor.execute("dir");
    System.out.println("示例1结果:\n" + result1);

    // 示例2：执行带参数的命令（Linux/macOS 示例）
    if (!CommandExecutor.isWindows()) {
      CommandExecutor.CommandResult result2 = CommandExecutor.execute("ls -l /tmp");
      System.out.println("\n示例2结果:\n" + result2);
    }

    // 示例3：使用命令列表（更安全的参数传递方式）
    CommandExecutor.CommandResult result3 =
        CommandExecutor.execute(
            List.of("ping", "127.0.0.1", "-n", "3") // Windows ping 3次
            // List.of("ping", "127.0.0.1", "-c", "3") // Linux/macOS ping 3次
            );
    System.out.println("\n示例3结果:\n" + result3);

    // 示例4：自定义超时和编码
    CommandExecutor.CommandResult result4 =
        CommandExecutor.execute("echo 测试中文输出", 10, TimeUnit.SECONDS, StandardCharsets.UTF_8);
    System.out.println("\n示例4结果:\n" + result4);
  }

  /**
   * 执行命令并返回结果（使用默认超时和字符集）
   *
   * @param command 命令字符串（如 "dir" 或 "ls -l"）
   * @return 命令执行结果封装对象
   */
  public static CommandResult execute(String command) {
    return execute(command, DEFAULT_TIMEOUT, TimeUnit.SECONDS, DEFAULT_CHARSET);
  }

  /**
   * 执行命令并返回结果（自定义超时）
   *
   * @param command 命令字符串
   * @param timeout 超时时间
   * @param unit 时间单位
   * @return 命令执行结果封装对象
   */
  public static CommandResult execute(String command, long timeout, TimeUnit unit) {
    return execute(command, timeout, unit, DEFAULT_CHARSET);
  }

  /**
   * 执行命令并返回结果（完全自定义参数）
   *
   * @param command 命令字符串
   * @param timeout 超时时间
   * @param unit 时间单位
   * @param charset 字符集（用于处理输出流编码）
   * @return 命令执行结果封装对象
   */
  public static CommandResult execute(
      String command, long timeout, TimeUnit unit, Charset charset) {
    // 构建跨平台命令数组
    String[] cmdArray = buildCommandArray(command);
    Process process = null;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CommandResult result = new CommandResult();

    try {
      // 启动进程
      ProcessBuilder pb = new ProcessBuilder(cmdArray).redirectErrorStream(true); // 合并错误流到标准输出
      process = pb.start();
      result.setCommand(command);

      // 异步读取输出流
      Process finalProcess = process;
      Future<String> outputFuture =
          executor.submit(() -> readInputStream(finalProcess.getInputStream(), charset));

      // 等待进程完成或超时
      boolean finished = process.waitFor(timeout, unit);
      if (!finished) {
        process.destroyForcibly(); // 超时强制终止
        result.setSuccess(false);
        result.setOutput("命令执行超时（已强制终止）");
        return result;
      }

      // 获取执行结果
      int exitCode = process.exitValue();
      result.setExitCode(exitCode);
      result.setSuccess(exitCode == 0);
      result.setOutput(outputFuture.get(1, TimeUnit.SECONDS)); // 等待输出读取完成

    } catch (TimeoutException e) {
      result.setSuccess(false);
      result.setOutput("读取命令输出超时: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // 恢复中断状态
      result.setSuccess(false);
      result.setOutput("线程被中断: " + e.getMessage());
    } catch (Exception e) {
      result.setSuccess(false);
      result.setOutput("执行命令失败: " + e.getMessage());
    } finally {
      // 确保资源释放
      if (process != null) {
        process.destroy();
      }
      executor.shutdownNow();
    }

    return result;
  }

  /**
   * 执行命令列表（适合多参数命令，避免字符串分割问题）
   *
   * @param commands 命令列表（如 ["ping", "127.0.0.1", "-n", "3"]）
   * @return 命令执行结果封装对象
   */
  public static CommandResult execute(List<String> commands) {
    return execute(commands, DEFAULT_TIMEOUT, TimeUnit.SECONDS, DEFAULT_CHARSET);
  }

  /**
   * 执行命令列表（完全自定义参数）
   *
   * @param commands 命令列表
   * @param timeout 超时时间
   * @param unit 时间单位
   * @param charset 字符集
   * @return 命令执行结果封装对象
   */
  public static CommandResult execute(
      List<String> commands, long timeout, TimeUnit unit, Charset charset) {
    if (commands == null || commands.isEmpty()) {
      CommandResult result = new CommandResult();
      result.setSuccess(false);
      result.setOutput("命令列表不能为空");
      return result;
    }

    Process process = null;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CommandResult result = new CommandResult();
    result.setCommand(String.join(" ", commands));

    try {
      // 启动进程（直接使用命令列表，不通过解释器包装）
      ProcessBuilder pb = new ProcessBuilder(commands).redirectErrorStream(true);
      process = pb.start();

      // 异步读取输出流
      Process finalProcess = process;
      Future<String> outputFuture =
          executor.submit(() -> readInputStream(finalProcess.getInputStream(), charset));

      // 等待进程完成或超时
      boolean finished = process.waitFor(timeout, unit);
      if (!finished) {
        process.destroyForcibly();
        result.setSuccess(false);
        result.setOutput("命令执行超时（已强制终止）");
        return result;
      }

      // 获取执行结果
      int exitCode = process.exitValue();
      result.setExitCode(exitCode);
      result.setSuccess(exitCode == 0);
      result.setOutput(outputFuture.get(1, TimeUnit.SECONDS));

    } catch (Exception e) {
      result.setSuccess(false);
      result.setOutput("执行命令失败: " + e.getMessage());
    } finally {
      if (process != null) process.destroy();
      executor.shutdownNow();
    }

    return result;
  }

  /** 构建跨平台命令数组（自动添加 cmd/sh 解释器） */
  private static String[] buildCommandArray(String command) {
    if (isWindows()) {
      return new String[] {"cmd.exe", "/c", command};
    } else {
      return new String[] {"/bin/sh", "-c", command};
    }
  }

  /** 读取输入流内容（带字符集处理） */
  private static String readInputStream(InputStream is, Charset charset) throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset))) {
      return reader.lines().collect(Collectors.joining(System.lineSeparator()));
    }
  }

  /** 判断当前系统是否为 Windows */
  private static boolean isWindows() {
    String os = System.getProperty("os.name").toLowerCase();
    return os.contains("win");
  }

  /** 命令执行结果封装类 */
  @Data
  static class CommandResult {
    private String command; // 执行的命令
    private int exitCode = -1; // 退出码（0表示成功）
    private boolean success; // 是否执行成功
    private String output; // 命令输出内容
  }
}

```

---

### CMD - OutputStream连续输入命令模式

```java


import java.io.*;
import java.util.Scanner;

public class Test1 {

    public static void main(String[] args) {

        try {
            Runtime runtime = Runtime.getRuntime();
            String[] cmds = {"cmd", "/k"};//此处必须用/k参数
            Process exec = runtime.exec(cmds);

            BufferedReader ResultErrorBuff = new BufferedReader(new InputStreamReader(exec.getErrorStream(), "gbk"));
            BufferedReader ResultSuccessBuff = new BufferedReader(new InputStreamReader(exec.getInputStream(), "gbk"));

            //处理回显
            new Thread(() -> processingReturnValues(ResultSuccessBuff)).start();
            new Thread(() -> processingReturnValues(ResultErrorBuff)).start();


            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(exec.getOutputStream(), "gbk"));
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("输入需要执行的命令>>>");
                String inCmd = scanner.nextLine();

                if (inCmd.equals("break")) {
                    bufferedWriter.write("exit");
                    bufferedWriter.newLine();
                    bufferedWriter.flush();
                    //等价于
                    exec.destroy();
                    break;
                }
                bufferedWriter.write(inCmd);
                bufferedWriter.newLine();//必须输入换行符  cmd默认按换行符 拆包
                bufferedWriter.flush();
            }
            System.out.println("exec.waitFor() = " + exec.waitFor());

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    //处理回显函数
    private static void processingReturnValues(BufferedReader ResultErrorBuff) {
        try {
            String line = null;
            while ((line = ResultErrorBuff.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (ResultErrorBuff != null) {
                try {
                    ResultErrorBuff.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}

```



## 七、常见错误与调试技巧

---

| 问题现象               | 根本原因                     | 解决方案                                                |
| ---------------------- | ---------------------------- | ------------------------------------------------------- |
| 程序卡死/无响应        | 未及时读取输出流导致缓冲区满 | 异步并行读取 stdout 和 stderr                           |
| 命令执行失败（无结果） | Windows 内部命令缺少 cmd /c  | 使用 `cmd.exe /c 命令` 形式执行                         |
| 中文乱码               | 编码不匹配                   | 明确指定字符集（如 GBK 或 UTF-8）                       |
| 路径带空格导致命令错误 | 字符串参数未正确分割         | 使用数组/列表形式传递参数（避免字符串拼接）             |
| 命令注入风险           | 使用字符串拼接用户输入参数   | 采用参数化方式：`new ProcessBuilder("ping", userInput)` |
| 进程无法终止           | 未设置超时控制               | 使用 `process.waitFor(timeout)` 或 `ExecuteWatchdog`    |
| 脚本权限不足           | Linux 无执行权限             | `chmod +x script.sh`                                    |



### Java解惑82 

如果你用一行命令行带上参数 slave 去运行这个程序，它会打印什么呢？如果你使用的命令行不带任何参数，它又会打印什么呢

```java
public class BeerBlast {

    static final String COMMAND = "java BeerBlast slave";

    public static void main(String[] args) throws Exception {

        if (args.length == 1 && args[0].equals("slave")) {

            for (int i = 99; i > 0; i--) {
                System.out.println(i + " bottles of beer on the wall");
                System.out.println(i + " bottles of beer");
                System.out.println("You take on down, pass it around,");
                System.out.println((i - 1) + " bottles of beer on the wall");
                System.out.println();
            }
        } else {
            // Master
            Process process = Runtime.getRuntime().exec(COMMAND);
            int exitValue = process.waitFor();
            System.out.println("exit value = " + exitValue);
        }
    }
}
```



解惑：

如果你使用参数 slave 来运行该程序，它就会打印出那首激动人心的名为”99 Bottles of Beer on the Wall”的童谣的歌词，这没有什么神秘的。如果你不使用该参数来运行这个程序，它会启动一个 slave 进程来打印这首歌谣，但是你看不到 slave 进程的输出。主进程会等待 slave 进程结束，然后打印出 slave进程的退出值(exit value)。根据惯例，0 值表示正常结束，所以 0 就是你可能期望该程序打印的东西。如果你运行了程序，你可能会发现该程序只会悬挂在那里，不会打印任何东西，看起来 slave 进程好像永远都在运行着。所以你可能会觉得你应该一直都能听到”99 Bottles of Beer on the Wall”这首童谣，即使是这首歌被唱走调了也是如此，但是这首歌只有 99 句，而且，电脑是很快的，你假设的情况应该是不存在的，那么这个程序出了什么问题呢？

这个秘密的线索可以在 Process 类的文档中找到，它叙述道：“由于某些本地平台只提供有限大小的缓冲，所以如果未能迅速地读取子进程(subprocess)的输出流，就有可能会导致子进程的阻塞，甚至是死锁” [Java-API]。这恰好就是这里所发生的事情：没有足够的缓冲空间来保存这首冗长的歌谣。为了确保 slave进程能够结束，父进程必须排空(drain)它的输出流，而这个输出流从 master线程的角度来看是输入流。下面的这个工具方法会在后台线程中完成这项工作：

```java
static void drainInBackground(final InputStream is) {
    new Thread(new Runnable(){
            public void run(){
            try{
                while( is.read() >= 0 );
            } catch(IOException e){
            // return on IOException
            }
    }
}).start();
```

如果我们修改原有的程序，在等待 slave 进程之前调用这个方法，程序就会打印出 0：

```java
}else{ // Master
Process process = Runtime.getRuntime().exec(COMMAND);
drainInBackground(process.getInputStream());
int exitValue = process.waitFor();
System.out.println("exit value = " + exitValue);
}
```

这里的教训是：**为了确保子进程能够结束，你必须排空它的输出流；对于错误流（error stream）也是一样**，而且它可能会更麻烦，因为你无法预测进程什么时候会倾倒（dump）一些输出到这个流中。在 5.0 版本中，加入了一个名为ProcessBuilder 的类用于排空这些流。它的 redirectErrorStream 方法将各个流合并起来，所以你只需要排空这一个流**。如果你决定不合并输出流和错误流，你必须并行地（concurrently）排空它们**。试图顺序化地（sequentially）排空它们会导致子进程被挂起。多年以来，很多程序员都被这个缺陷所刺痛。这里对于 API 计者们的教训是，Process 类应该避免这个错误，也许应该自动地排空输出流和错误流，除非用户表示要读取它们。更一般的讲，API 应该设计得更容易做出正确的事，而很难或不可能做出错误的事。

## 八、安全性与最佳实践

1. **避免命令注入：**
   ```java
   // 不安全
   Runtime.getRuntime().exec("ping " + userInput);
   // 安全
   new ProcessBuilder("ping", userInput).start();
   ```

2. **资源释放：**
   
   - 使用 `try-with-resources` 自动关闭流。
   - 在 `finally` 中调用 `process.destroy()`。
   
3. **命令超时控制：**
   
   ```java
   process.waitFor(10, TimeUnit.SECONDS);
   ```
   
4. **合并输出流避免死锁：**
   ```java
   pb.redirectErrorStream(true);
   ```

5. **日志与审计：**
   - 建议记录执行的命令与返回结果。

---

## 九、实用调用案例

### 案例 1：动态执行系统命令并捕获输出

```java
public static void execAndPrint(String command) throws Exception {
    Process process = new ProcessBuilder("cmd", "/c", command).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }
    }
    System.out.println("退出码: " + process.waitFor());
}
```

调用：

```java
execAndPrint("tasklist"); // 查看当前进程列表
execAndPrint("dir C:\\Windows"); // 查看目录
```

### 案例 2：执行多条命令（Windows）

```java
String command = "echo Start && dir && echo Done";
Process process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", command});
copyStream(process.getInputStream(), System.out);

// Windows 编译java 命令如下
// cd c:\Users\gouwe\Desktop\mytool && mvn compile
// cd c:\Users\gouwe\Desktop\mytool && java -cp target/classes com.g.PortStatusChecker
```

### 案例 3：调用外部程序

```java
Runtime.getRuntime().exec("notepad.exe D:/example.txt");
```

### 案例 4：在 Linux 中执行 Shell 脚本

```java
ProcessBuilder pb = new ProcessBuilder("/bin/bash", "/home/user/test.sh");
Process process = pb.start();
copyStream(process.getInputStream(), System.out);
```

### 案例 5：跨平台 Ping 工具

```java
String host = "www.google.com";
String command = System.getProperty("os.name").toLowerCase().contains("win") ?
    "ping " + host : "ping -c 4 " + host;
System.out.println(ShellUtil.exec(command));
```

### 案例6：netstat -ano 查询端口占用

```java
/**
 * PortStatusChecker类用于c
 *
 * <p>该类提供端口连接状态检测功能，可以验证指定主机和端口是否可访问
 */
public class PortStatusChecker {
  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("win");

  public static void main(String[] args) {
    String port = "21";
    Process process = null;
    try {
      // 创建命令行参数
      String[] cmd = getCommands(port);

      ProcessBuilder builder = new ProcessBuilder(cmd);
      // 设置环境变量
      Map<String, String> environment = builder.environment();
      environment.put("LANG", "zh_CN.UTF-8");

      builder.redirectErrorStream(true);
      process = builder.start();
      // 读取进程输出并显示结果
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), getEncoding()))) {
        displayResults(reader, port);
        process.waitFor();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("进程执行被中断: " + e.getMessage());
    } catch (IOException e) {
      System.err.println("IO操作出错: " + e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      System.err.println("执行命令时发生未知错误: " + e.getMessage());
      e.printStackTrace();
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  private static String[] getCommands(String port) {
    if (IS_WINDOWS) {
      return new String[] {"cmd.exe", "/c", "netstat -ano | findstr :" + port};
    } else {
      return new String[] {"sh", "-c", "netstat -tuln | grep :" + port};
    }
  }

  private static String getEncoding() {
    return IS_WINDOWS ? "GBK" : "UTF-8";
  }

  private static void displayResults(BufferedReader reader, String port) throws IOException {
    String line;
    boolean found = false;
    System.out.println("正在查询端口：" + port + " 的占用情况...\n");

    while ((line = reader.readLine()) != null) {
      System.out.println(line);
      found = true;
    }

    if (!found) {
      System.out.println("未发现端口 " + port + " 被占用。");
    }
  }
}
```



### 案例7：Java 调用FFmpeg 

给你一套**Java 调用 FFmpeg 的完整实战方案**，从“最简单命令调用”到“进阶封装”，直接能用。

---

#### 一、方式选择（先看这个）

Java 调 FFmpeg 常见有 3 种方式：

1️⃣ **Runtime / ProcessBuilder（推荐）**

* 本质：调用系统里的 `ffmpeg.exe`
* 优点：简单、稳定、无 JNI
* 缺点：依赖本地环境

2️⃣ **JavaCV（FFmpeg 封装库）**

* 优点：纯 Java 调用
* 缺点：包大、复杂

3️⃣ **JNA / JNI**

* 优点：底层控制强
* 缺点：复杂（不推荐）

👉 实战建议：**90% 场景用 ProcessBuilder**

---

#### 二、前置准备

###### 1️⃣ 安装 FFmpeg

下载：
[https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)

配置环境变量后测试：

```bash
ffmpeg -version
```

---

#### 三、基础案例：Java 调用 FFmpeg 转码

##### 🎯 示例：MP4 转 mp3

###### ✅ Java 代码（推荐写法）

```java
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class FFmpegDemo {

    public static void main(String[] args) {
        // F:\BaiduNetdiskDownload\豪斯医生S06.House.M.D.2009.1080p.Blu-ray.x265.AC3￡cXcY@FRDS
        String path = "F:\\BaiduNetdiskDownload\\鲍鹏山-先秦诸子";
        String input = "1 .商鞅,作法自毙的枭雄1.mp4";
        String output = "output.mp3";

        // ffmpeg -i "有声书 听书 看见（柴静）01.mp4" -vn -c:a libmp3lame -q:a 2 "有声书 听书 看见（柴静）01.mp3"
        String[] cmds = {
                "ffmpeg",
                "-i", input,
                "-vn", "-c:a", "libmp3lame", "-q:a", "2",
                output};
        
        
        ProcessBuilder pb = new ProcessBuilder(cmds);
        pb.directory(new File(path)); // 设置工作目录
        pb.redirectErrorStream(true); // 合并错误输出流和正常输出流

        try {
            Process process = pb.start();

            // 读取 FFmpeg 输出日志
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            int exitCode = process.waitFor(); // 等待 FFmpeg 运行完成
            System.out.println("执行完成，退出码：" + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

```

---

#### 四、进阶案例

##### 🎯 1. 获取视频信息（类似 ffprobe）

```java
ProcessBuilder pb = new ProcessBuilder(
        "ffmpeg",
        "-i", "input.mp4"
);
```

👉 FFmpeg 会把信息输出到 stderr（已合并）

---

##### 🎯 2. 截取视频片段

```java
ProcessBuilder pb = new ProcessBuilder(
        "ffmpeg",
        "-ss", "00:00:10",
        "-to", "00:00:20",
        "-i", "input.mp4",
        "-c", "copy",
        "output.mp4"
);
```

---

##### 🎯 3. 截图（生成缩略图）

```java
ProcessBuilder pb = new ProcessBuilder(
        "ffmpeg",
        "-i", "input.mp4",
        "-ss", "00:00:05",
        "-vframes", "1",
        "cover.jpg"
);
```

---

##### 🎯 4. 压缩视频

```java
ProcessBuilder pb = new ProcessBuilder(
        "ffmpeg",
        "-i", "input.mp4",
        "-b:v", "1000k",
        "-b:a", "128k",
        "output.mp4"
);
```

---

#### 五、生产级封装（建议你用这个）

##### ✅ 工具类封装

```java
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class FFmpegUtil {

    public static int execute(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[FFmpeg] " + line);
            }

            return process.waitFor();

        } catch (Exception e) {
            throw new RuntimeException("FFmpeg 执行失败", e);
        }
    }
}
```

---

##### ✅ 使用方式

```java
import java.util.Arrays;

public class Test {
    public static void main(String[] args) {
        int code = FFmpegUtil.execute(Arrays.asList(
                "ffmpeg",
                "-i", "input.mp4",
                "-vf", "scale=1280:720",
                "output.mp4"
        ));

        System.out.println("exit code = " + code);
    }
}
```

---

#### 六、关键坑点（非常重要）

###### ⚠️ 1. 中文路径问题

```java
new ProcessBuilder("ffmpeg", "-i", "D:\\视频\\测试.mp4", ...)
```

👉 Windows 可能乱码
✔ 解决：统一 UTF-8 或避免中文路径

---

###### ⚠️ 2. FFmpeg 不在 PATH

```java
"ffmpeg"
```

👉 会报错

✔ 解决：

```java
"D:\\ffmpeg\\bin\\ffmpeg.exe"
```

---

###### ⚠️ 3. 阻塞问题

必须消费输出流，否则：

👉 **进程卡死**

---

###### ⚠️ 4. 大文件建议异步执行

```java
CompletableFuture.runAsync(() -> FFmpegUtil.execute(cmd));
```

---





### 案例8：查找本地 winRar安装目录

#### 一、最可靠方案 ⭐⭐⭐⭐⭐

👉 通过 Windows 注册表查找（官方安装信息）

> **WinRAR 正规安装一定会写注册表**

常见注册表位置（64 / 32 位）

```
HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\WinRAR.exe
HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths\WinRAR.exe
```

------

##### Java 示例：通过 `reg query` 查询

##### 1️⃣ 查询注册表

```java
public static String findWinRARByRegistry() throws Exception {
    String[] keys = {
        "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinRAR.exe",
        "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinRAR.exe"
    };

    for (String key : keys) {
        String cmd = "reg query \"" + key + "\" /ve";
        Process process = new ProcessBuilder("cmd", "/c", cmd)
                .redirectErrorStream(true)
                .start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("REG_SZ")) {
                    // 示例输出： (默认)    REG_SZ    C:\Program Files\WinRAR\WinRAR.exe
                    return line.substring(line.lastIndexOf("REG_SZ") + 6).trim();
                }
            }
        }
    }
    return null;
}
```

##### 2️⃣ 使用结果

```java
String winrarPath = findWinRARByRegistry();
if (winrarPath != null) {
    System.out.println("WinRAR 路径：" + winrarPath);
}
```

✔ **这是生产环境最推荐方式**

------

#### 二、通过环境变量 PATH 查找 ⭐⭐⭐⭐

> 如果 WinRAR 被加入了 PATH

##### Java 实现

```java
public static String findWinRARByWhere() throws Exception {
    Process process = new ProcessBuilder("cmd", "/c", "where winrar")
            .redirectErrorStream(true)
            .start();

    try (BufferedReader br = new BufferedReader(
            new InputStreamReader(process.getInputStream(), "GBK"))) {
        return br.readLine(); // 第一行就是路径
    }
}
```

✔ 简单
 ❌ 不保证一定存在（取决于用户是否配置 PATH）

------

#### 三、从常见安装目录猜测 ⭐⭐⭐

> 作为兜底方案（不严谨）

##### 常见目录

```
C:\Program Files\WinRAR\
C:\Program Files (x86)\WinRAR\
```

##### Java 示例

```java
public static String findWinRARByCommonPath() {
    String[] paths = {
        "C:\\Program Files\\WinRAR\\WinRAR.exe",
        "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe"
    };
    for (String path : paths) {
        if (new File(path).exists()) {
            return path;
        }
    }
    return null;
}
```

------

#### 四、推荐的「组合式查找策略」（工程级）

> **这是我强烈建议你实际使用的方案**

```java
public static String findWinRAR() throws Exception {
    String path;

    path = findWinRARByRegistry();
    if (path != null) return path;

    path = findWinRARByWhere();
    if (path != null) return path;

    path = findWinRARByCommonPath();
    return path;
}
```

------

#### 五、找到 WinRAR 后如何安全调用（示例）

```java
String winrar = findWinRAR();
if (winrar == null) {
    throw new RuntimeException("未找到 WinRAR，请确认已安装");
}

ProcessBuilder pb = new ProcessBuilder(
        winrar,
        "x",
        "test.rar",
        "output\\"
);
pb.redirectErrorStream(true);
pb.start().waitFor();
```

✔ 参数拆分
 ✔ 防止空格路径问题
 ✔ 不拼字符串

## 参考资料

- [Oracle Docs - ProcessBuilder](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessBuilder.html)
- [Apache Commons Exec 官方文档](https://commons.apache.org/proper/commons-exec/)
- [Java Tutorials: Process API](https://docs.oracle.com/javase/tutorial/essential/environment/process.html)
- [Stack Overflow: ProcessBuilder vs Runtime.exec()](https://stackoverflow.com/questions/3643936/difference-between-runtime-getruntime-exec-and-processbuilder)





---

> ✨ **总结：**
>
> - `ProcessBuilder` 是现代推荐方案；
> - 始终处理 I/O 流，避免死锁；
> - 控制执行超时、清理资源；
> - 跨平台封装可极大提升可维护性。





# Java 程序在 Windows 或 Linux 下调用 Linux shell 脚本（.sh）🧩 

## 一、核心原理

Java 调用系统命令的方式主要有两种：

1. ✅ **`Runtime.getRuntime().exec()`**
2. ✅ **`ProcessBuilder`**

两者都能执行 Linux 命令或 `.sh` 脚本。

------

## 🧱 二、前提准备

假设你有一个脚本：

```
/home/gouwe/test.sh
```

内容如下：

```bash
#!/bin/bash
echo "当前时间：$(date)"
echo "参数1：$1"
```

然后给脚本加执行权限：

```bash
chmod +x /home/gouwe/test.sh
```

------

## ⚙️ 三、Java 调用方式（推荐用 ProcessBuilder）

```java
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RunShell {
    public static void main(String[] args) {
        // 要执行的命令
        String[] cmd = {"/bin/bash", "/home/gouwe/test.sh", "HelloFromJava"};

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true); // 合并错误输出
            Process process = pb.start();

            // 读取脚本输出
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            int exitCode = process.waitFor();
            System.out.println("脚本执行结束，退出码：" + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

------

## 🧠 四、如果脚本需要管理员权限（sudo）

你可以在命令数组中加上：

```java
String[] cmd = {"/bin/bash", "-c", "sudo /home/gouwe/test.sh arg1"};
```

> ⚠️ 如果需要密码，要么提前配置 `sudoers` 免密码，要么使用 Java Expect 工具自动输入。

------

## 🧩 五、在 Windows 调用远程 Linux 脚本

如果你想在 Windows 上运行 Java 程序，远程执行 Linux 脚本：

- 可以通过 **SSH** 调用，比如用：
  - **JSch（纯 Java SSH 库）**
  - **Apache Mina SSHD**
  - 或调用系统 `ssh` 命令。

### ✅ 示例（使用 JSch 连接远程服务器执行脚本）

```java
import com.jcraft.jsch.*;

public class SSHExample {
    public static void main(String[] args) throws Exception {
        String host = "192.168.1.100";
        String user = "root";
        String password = "123456";
        String command = "/home/gouwe/test.sh Hello";

        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, 22);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);
        channel.setErrStream(System.err);

        java.io.InputStream in = channel.getInputStream();
        channel.connect();

        byte[] tmp = new byte[1024];
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, 1024);
                if (i < 0) break;
                System.out.print(new String(tmp, 0, i));
            }
            if (channel.isClosed()) break;
            Thread.sleep(500);
        }

        channel.disconnect();
        session.disconnect();
    }
}
```

------

## ✅ 总结对比

| 方式             | 场景         | 优点         | 缺点         |
| ---------------- | ------------ | ------------ | ------------ |
| `ProcessBuilder` | 本机执行脚本 | 简单、轻量   | 仅限本地     |
| JSch (SSH)       | 远程执行脚本 | 可远程管理   | 需依赖 jar   |
| 调用系统 ssh     | 任意平台     | 无第三方依赖 | 不易捕获输出 |

