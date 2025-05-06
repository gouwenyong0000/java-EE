# 官方教程要点整理



## 初次接触

我们通常会认为直接使用 `Runtime.exec()` 是很简单的方式，但其实它隐藏了很多陷阱。Apache Commons Exec 的设计正是为了解决这些问题。

---

## 第一次执行子进程

目标：从 Java 应用中调用 Acrobat Reader 打印 PDF 文件。

### 示例代码（初始版本）：

```java
String line = "AcroRd32.exe /p /h " + file.getAbsolutePath();
CommandLine cmdLine = CommandLine.parse(line);
DefaultExecutor executor = DefaultExecutor.builder().get();
int exitValue = executor.execute(cmdLine);
```

**问题：**  
打印成功后抛出异常 —— 因为 Acrobat Reader 成功返回了退出码 `1`，而 Java 默认将非零退出码视为失败。

---

## 修正退出码识别

我们需要告诉 `DefaultExecutor` 将退出码 `1` 视为成功。

### 改进后的代码：

```java
executor.setExitValue(1);
```

---

## 防止子进程挂起：使用 Watchdog

有时候，子进程可能会因为某些原因卡住（如打印机缺纸）。此时我们可以使用 `ExecuteWatchdog` 设置超时机制。

### 添加 Watchdog：

```java
ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
    .setTimeout(Duration.ofSeconds(60))
    .get();
executor.setWatchdog(watchdog);
```

---

## 处理路径中的空格：引号是你的朋友

当文件路径中包含空格时，命令行会被错误地拆分。

### 错误示例：
```bash
AcroRd32.exe /p /h C:\Document And Settings\documents\432432.pdf
```

### 正确做法：添加双引号

```java
String line = "AcroRd32.exe /p /h \"" + file.getAbsolutePath() + "\"";
```

---

## 增量构建命令行（推荐方式）

避免字符串拼接带来的错误，建议使用 `CommandLine` 类逐步构建命令参数。

### 推荐写法：

```java
CommandLine cmdLine = new CommandLine("AcroRd32.exe");
cmdLine.addArgument("/p");
cmdLine.addArgument("/h");
cmdLine.addArgument("${file}");

Map<String, Object> map = new HashMap<>();
map.put("file", new File("invoice.pdf"));
cmdLine.setSubstitutionMap(map);
```

这样可以自动处理操作系统相关的路径格式转换。

---

## 异步执行子进程

当前的执行是阻塞式的，主线程会一直等待直到子进程结束。为了不阻塞线程，我们可以使用异步执行。

### 异步执行示例：

```java
DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();

executor.execute(cmdLine, resultHandler);

// 稍后获取结果
resultHandler.waitFor();
int exitValue = resultHandler.getExitValue();
```

通过 `ExecuteResultHandler` 实现回调处理，提升程序响应性。

---

## 总结

| 问题             | 解决方案                              |
| ---------------- | ------------------------------------- |
| 子进程退出码异常 | 使用 `setExitValue()`                 |
| 子进程挂起       | 使用 `ExecuteWatchdog` 设置超时       |
| 路径含空格       | 使用引号包裹路径或增量构建命令        |
| 构建命令易错     | 使用 `CommandLine` 增量添加参数       |
| 阻塞主线程       | 使用异步执行和 `ExecuteResultHandler` |

Apache Commons Exec 提供了比 `Runtime.exec()` 更强大、更健壮的 API 来管理外部进程，尤其是在生产环境中。

```java
CommandLine cmdLine = new CommandLine("AcroRd32.exe");
cmdLine.addArgument("/p");
cmdLine.addArgument("/h");
cmdLine.addArgument("${file}");
HashMap map = new HashMap();
map.put("file", new File("invoice.pdf"));
cmdLine.setSubstitutionMap(map);

DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();

ExecuteWatchdog watchdog = ExecuteWatchdog.builder().setTimeout(Duration.ofSeconds(60)).get();
Executor executor = DefaultExecutor.builder().get();
executor.setExitValue(1);
executor.setWatchdog(watchdog);
executor.execute(cmdLine, resultHandler);

// some time later the result handler callback was invoked so we
// can safely request the exit value
resultHandler.waitFor();
int exitValue = resultHandler.getExitValue();
```



## 完整案例

https://github.com/msandiford/commons-exec/blob/master/src/test/java/org/apache/commons/exec/TestUtil.java

```java
import org.apache.commons.exec.*;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.fail;

class PrintResultHandler extends DefaultExecuteResultHandler {

    private ExecuteWatchdog watchdog;

    public PrintResultHandler(final ExecuteWatchdog watchdog) {
        this.watchdog = watchdog;
    }

    public PrintResultHandler(final int exitValue) {
        super.onProcessComplete(exitValue);
    }

    @Override
    public void onProcessComplete(final int exitValue) {
        super.onProcessComplete(exitValue);
        System.out.println("[resultHandler] The document was successfully printed ...");
    }

    @Override
    public void onProcessFailed(final ExecuteException e) {
        super.onProcessFailed(e);
        if (watchdog != null && watchdog.killedProcess()) {
            System.err.println("[resultHandler] The print process timed out");
        } else {
            System.err.println("[resultHandler] The print process failed to do : " + e.getMessage());
        }
    }
}

public class TutorialTest {
    @Test
    public void testTutorialExample() throws Exception {

        final Duration printJobTimeout = Duration.ofSeconds(15);
        final boolean printInBackground = false;
        final File pdfFile = new File("/Documents and Settings/foo.pdf");

        PrintResultHandler printResult;

        try {
            // printing takes around 10 seconds
            System.out.println("[main] Preparing print job ...");
            printResult = print(pdfFile, printJobTimeout, printInBackground);
            System.out.println("[main] Successfully sent the print job ...");
        } catch (final Exception e) {
            e.printStackTrace();
            fail("[main] Printing of the following document failed : " + pdfFile.getAbsolutePath());
            throw e;
        }

        // come back to check the print result
        System.out.println("[main] Test is exiting but waiting for the print job to finish...");
        printResult.waitFor();
        System.out.println("[main] The print job has finished ...");
    }
    

    /**
     *  src/test/scripts/acrord32.bat
     */
    private final File acroRd32Script = resolveScriptForOS("src/test/scripts/acrord32");

    /**
     * 模拟打印PDF文档。
     *
     * @param file              要打印的文件
     * @param printJobTimeout   打印任务超时时间（毫秒），在看门狗终止打印进程之前
     * @param printInBackground 是否在后台执行打印（非阻塞模式）
     * @return 打印结果处理器（实现Future模式）
     * @throws IOException 测试失败时抛出IO异常
     */
    public PrintResultHandler print(final File file, final Duration printJobTimeout, final boolean printInBackground) throws IOException {

        int exitValue;
        ExecuteWatchdog watchdog = null;
        PrintResultHandler resultHandler;

        // 构建命令行参数（使用java.io.File）
        final Map<String, File> map = new HashMap<>();
        map.put("file", file);
        final CommandLine commandLine = new CommandLine(acroRd32Script);
        commandLine.addArgument("/p");        // 添加打印参数
        commandLine.addArgument("/h");        // 添加隐藏窗口参数
        commandLine.addArgument("${file}");   // 添加文件路径占位符
        commandLine.setSubstitutionMap(map);  // 设置参数替换映射

        // 创建执行器并设置成功退出码为1
        final Executor executor = DefaultExecutor.builder().get();
        executor.setExitValue(1);

        // 根据超时配置创建看门狗
        if (printJobTimeout.toMillis() > 0) {
            watchdog = ExecuteWatchdog.builder().setTimeout(printJobTimeout).get();
            executor.setWatchdog(watchdog);
        }

        // 根据后台打印标志选择执行模式
        if (printInBackground) {
            System.out.println("[print] 执行非阻塞打印任务...");
            resultHandler = new PrintResultHandler(watchdog);
            executor.execute(commandLine, resultHandler);
        } else {
            System.out.println("[print] 执行阻塞打印任务...");
            exitValue = executor.execute(commandLine);
            resultHandler = new PrintResultHandler(exitValue);
        }

        return resultHandler;
    }


    public static File resolveScriptForOS(final String script) {
        if (OS.isFamilyWindows()) {
            return new File(script + ".bat");
        }
        if (OS.isFamilyUnix()) {
            return new File(script + ".sh");
        }
        if (OS.isFamilyOpenVms()) {
            return new File(script + ".dcl");
        }
        fail("Test not supported for this OS");
        return null; // unreachable.
    }
}
```





# 入门案例

Apache Commons Exec 是一个用于在 Java 程序中执行外部进程的库，旨在替代 `Runtime.exec()` 方法，提供更强大和灵活的功能。以下是使用 Apache Commons Exec 的详细步骤和示例：

---

## **1. 添加 Maven 依赖**
首先，在 `pom.xml` 中添加 Apache Commons Exec 的依赖：
```xml
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-exec</artifactId>
            <version>1.4.0</version>
        </dependency>
```

---

## **2. 基本用法**
以下是一个简单的示例，展示如何使用 Apache Commons Exec 执行外部命令：
```java
public class ExecExample {
    public static void main(String[] args) {
        try {

            // 动态适配操作系统命令
            CommandLine cmdLine;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                cmdLine = CommandLine.parse("cmd.exe /c echo Hello, Apache Commons Exec!");
            } else {
                cmdLine = CommandLine.parse("/bin/sh -c echo Hello, Apache Commons Exec!");
            }

            // 创建执行器
            DefaultExecutor executor = new DefaultExecutor();

            // 设置超时时间（60秒）
            ExecuteWatchdog watchdog = new ExecuteWatchdog(60000);
            executor.setWatchdog(watchdog);

            // 设置输出处理（将命令的标准输出和错误输出重定向到控制台）
            PumpStreamHandler streamHandler = new PumpStreamHandler(System.out, System.err);
            executor.setStreamHandler(streamHandler);

            // 执行命令
            int exitValue = executor.execute(cmdLine);
            System.out.println("命令执行完成，退出码: " + exitValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## **3. 关键组件说明**
- **`CommandLine`**: 用于构建命令行参数，支持链式调用。
  ```java
  CommandLine cmdLine = CommandLine.parse("ls -l /tmp");
  // 或者动态构建命令
  CommandLine cmdLine = new CommandLine("ls");
  cmdLine.addArgument("-l");
  cmdLine.addArgument("/tmp");
  ```

- **`DefaultExecutor`**: 默认的执行器，负责执行命令。
- **`ExecuteWatchdog`**: 用于设置超时时间，防止命令无限期阻塞。
- **`PumpStreamHandler`**: 处理命令的输入、输出和错误流。可以自定义流处理逻辑，例如捕获输出到文件或内存。

---

## **4. 高级用法**
### **4.1 捕获命令输出**
如果需要捕获命令的输出到字符串或文件，可以通过自定义 `PumpStreamHandler` 实现：
```java
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CaptureOutputExample {
    public static void main(String[] args) {
        try {
            CommandLine cmdLine = CommandLine.parse("echo Hello, Apache Commons Exec!");
            DefaultExecutor executor = new DefaultExecutor();

            // 捕获输出到 ByteArrayOutputStream
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
            executor.setStreamHandler(streamHandler);

            int exitValue = executor.execute(cmdLine);
            String output = outputStream.toString();
            System.out.println("命令输出: " + output);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### **4.2 传递环境变量**
可以通过 `DefaultExecutor` 设置环境变量：
```java
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;

import java.util.Map;
import java.util.HashMap;

public class EnvVariablesExample {
    public static void main(String[] args) {
        try {
            CommandLine cmdLine = CommandLine.parse("printenv MY_VAR");
            DefaultExecutor executor = new DefaultExecutor();

            // 设置环境变量
            Map<String, String> env = new HashMap<>();
            env.put("MY_VAR", "Hello from Java!");
            executor.setEnvironmentVariables(env);

            int exitValue = executor.execute(cmdLine);
            System.out.println("命令执行完成，退出码: " + exitValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## **5. 应用场景**
- **自动化脚本执行**: 如数据库备份（参考知识库中的 MySQL 备份示例）。
- **系统管理工具**: 执行系统命令（如 `ls`, `grep`, `find`）。
- **集成外部工具**: 在 Java 程序中调用外部工具（如 `ffmpeg`, `git`）。

---

## **6. 最佳实践**
1. **超时管理**  
   使用 `ExecuteWatchdog` 防止命令执行时间过长导致程序阻塞：
   ```java
   ExecuteWatchdog watchdog = new ExecuteWatchdog(60000); // 60秒
   executor.setWatchdog(watchdog);
   ```

2. **错误处理**  
   捕获 `ExecuteException` 处理命令执行中的错误：
   ```java
   try {
       int exitValue = executor.execute(cmdLine);
   } catch (ExecuteException e) {
       System.err.println("命令执行失败，退出码: " + e.getExitValue());
   }
   ```

3. **流处理**  
   使用 `PumpStreamHandler` 确保输出流被正确读取，避免因缓冲区满导致进程阻塞。

4. **资源释放**  
   确保在异常或超时情况下正确关闭资源（如流或进程）。

---

## **7. 常见问题**
- **如何执行带空格的路径？**  
  使用 `CommandLine` 的 `addArgument` 方法自动处理空格：
  
  ```java
  CommandLine cmdLine = new CommandLine("notepad.exe");
  cmdLine.addArgument("C:\\My Documents\\file.txt");
  ```
  
- **如何异步执行命令？**  
  使用 `Executor` 的异步 API（需结合线程管理）。

---

## **8. 参考资料**

- **官方文档**: [Apache Commons Exec](https://commons.apache.org/proper/commons-exec/)
- **GitHub 项目地址**: [commons-exec](https://gitcode.com/gh_mirrors/co/commons-exec)

通过以上步骤和示例，你可以快速上手 Apache Commons Exec 并高效地在 Java 程序中执行外部命令！

以下是对 Apache Commons Exec 官方教程内容的整理与补充，已融合至之前的笔记中，帮助你更全面地理解该库的使用方法和最佳实践。

------





# Apache 和 Runtime对比

前言
--

在调用 SHELL 命令或 DOS 命令时，使用 `Runtime.getRuntime().exec(command);` 这个方法。但是执行某些命令时，程序可能就卡在那了，需要在执行的过程中新开启几个线程来不断地读取标准输出，以及错误输出，这样很不方便，好在 commons-exec 提供了更加友好的使用方式。



一、同步调用
------

commons-exec 的 command 不需要考虑执行环境，比如 windows 下不需要添加 "cmd /c" 的前缀。  可以使用自定义的流来接受结果，比如使用文件流将结果保存到文件，使用网络流保存到远程服务器上等。  下面的例子模仿一个命令调用，这里只是一个 ping localhost 操作，这里为了方便，结果写在了内存中。

```java
@Test
public void testCommonExec() throws IOException {
    String command = "ping localhost";
    //接收正常结果流
    ByteArrayOutputStream susStream = new ByteArrayOutputStream();
    //接收异常结果流
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    CommandLine commandLine = CommandLine.parse(command);
    DefaultExecutor exec = new DefaultExecutor();
    PumpStreamHandler streamHandler = new PumpStreamHandler(susStream, errStream);
    exec.setStreamHandler(streamHandler);
    int code = exec.execute(commandLine);
    System.out.println("退出代码: " + code);
    System.out.println(susStream.toString("GBK"));
    System.out.println(errStream.toString("GBK"));
}
```

如果使用 JDK 中的 Runtime 操作就复杂了：

```java
@Test
public void testJdk() throws InterruptedException, IOException {
    final Process process = Runtime.getRuntime().exec("cmd /c ping localhost");
    new Thread(() -> {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), "gbk"))) {
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    process.exitValue();
                    break; // exitValue没有异常表示进程执行完成，退出循环
                } catch (IllegalThreadStateException e) {
                    // 异常代表进程没有执行完
                }
                //
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }).start();
    process.waitFor();
}
```

二、异步调用
------

commons-exec 支持异步调用

```java
@Test
public void testCommonExec() throws InterruptedException, IOException {
    String command = "ping localhost";
    //接收正常结果流
    ByteArrayOutputStream susStream = new ByteArrayOutputStream();
    //接收异常结果流
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    CommandLine commandLine = CommandLine.parse(command);
    DefaultExecutor exec = new DefaultExecutor();


    PumpStreamHandler streamHandler = new PumpStreamHandler(susStream, errStream);
    exec.setStreamHandler(streamHandler);
    ExecuteResultHandler erh = new ExecuteResultHandler() {
        @Override
        public void onProcessComplete(int exitValue) {
            try {
                String suc = susStream.toString("GBK");
                System.out.println(suc);
                System.out.println("3. 异步执行完成");
            } catch (UnsupportedEncodingException uee) {
                uee.printStackTrace();
            }
        }
        @Override
        public void onProcessFailed(ExecuteException e) {
            try {
                String err = errStream.toString("GBK");
                System.out.println(err);
                System.out.println("3. 异步执行出错");
            } catch (UnsupportedEncodingException uee) {
                uee.printStackTrace();
            }
        }
    };
    System.out.println("1. 开始执行");
    exec.execute(commandLine, erh);
    System.out.println("2. 做其他操作");
    // 避免主线程退出导致程序退出
    Thread.currentThread().join();
}
```

如果使用 JDK 中的 Runtime 就复杂了：

```java
@Test
    public void testJdk() throws IOException, InterruptedException {
        System.out.println("1. 开始执行");
        String cmd = "cmd /c ping localhost"; // 假设是一个耗时的操作
        execAsync(cmd, processResult -> {
            System.out.println("3. 异步执行完成，success=" + processResult.success + "; msg=" + processResult.result);
            System.exit(0);
        });
        // 做其他操作 ... ...
        System.out.println("2. 做其他操作");
        // 避免主线程退出导致程序退出
        Thread.currentThread().join();
    }

    private void execAsync(String command, Consumer<ProcessResult> callback) throws IOException {
        final Process process = Runtime.getRuntime().exec(command);
        new Thread(() -> {
            StringBuilder successMsg = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
                // 存放临时结果
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        successMsg.append(line).append("\r\n");
                        int exitCode = process.exitValue();
                        ProcessResult pr = new ProcessResult();
                        if (exitCode == 0) {
                            pr.success = true;
                            pr.result = successMsg.toString();
                        } else {
                            pr.success = false;
                            pr.result = IOUtils.toString(process.getErrorStream(), "utf-8");
                        }
                        callback.accept(pr); // 回调主线程注册的函数
                        break; // exitValue没有异常表示进程执行完成，退出循环
                    } catch (IllegalThreadStateException e) {
                        // 异常代表进程没有执行完
                    }
                    try {
                        // 等待100毫秒在检查是否完成
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    private static class ProcessResult {
        boolean success;
        String result;
    }
```

三、处理超时 看门狗
------

commons-exec 主要通过 ExecuteWatchdog 类来处理超时

```java
@Test
public void testCommonExecWatch() throws IOException {
    String command = "ping localhost";
    ByteArrayOutputStream susStream = new ByteArrayOutputStream();
    ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    CommandLine commandLine = CommandLine.parse(command);
    DefaultExecutor exec = new DefaultExecutor();
    //设置一分钟超时
    ExecuteWatchdog watchdog = new ExecuteWatchdog(60 * 1000);
    exec.setWatchdog(watchdog);
    PumpStreamHandler streamHandler = new PumpStreamHandler(susStream, errStream);
    exec.setStreamHandler(streamHandler);
    try {
        int code = exec.execute(commandLine);
        System.out.println("result code: " + code);
        // 不同操作系统注意编码，否则结果乱码
        String suc = susStream.toString("GBK");
        String err = errStream.toString("GBK");
        System.out.println(suc + err);
    } catch (ExecuteException e) {
        if (watchdog.killedProcess()) {
            // 被watchdog故意杀死
            System.err.println("超时了");
        }
    }
}
```

总结
--

Apache commons-exec 提供一些常用的方法用来执行外部进程，Apache commons exec 库提供了 Watchdog 来设监视进程的执行超时，同时也还实现了同步和异步功能，比 JDK 原生的 Runtime 要好用很多。