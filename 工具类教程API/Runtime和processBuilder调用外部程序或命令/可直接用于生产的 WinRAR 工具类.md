# 可直接用于生产的 `WinRAR` 工具类

- ✅ **自动查找 WinRAR 安装路径**
- ✅ **RAR / ZIP 压缩**
- ✅ **RAR / ZIP 解压**
- ✅ **路径含空格安全**
- ✅ **ProcessBuilder 方式（不死锁）**
- ✅ **可直接丢进工具包使用**

> **适用系统**：Windows
> **依赖**：系统已安装 WinRAR
> **调用方式**：命令行 `WinRAR.exe`

------

# 一、整体设计说明

### 设计原则

- **不拼命令字符串**（防空格、防注入）
- **自动发现 WinRAR**
- **统一命令执行**
- **返回执行结果**

### 支持的 WinRAR 命令

| 功能 | 命令  |
| ---- | ----- |
| 压缩 | `a`   |
| 解压 | `x`   |
| 覆盖 | `-o+` |
| 静默 | `-y`  |

------

# 二、WinRAR 工具类

## 1️⃣ WinRARUtil.java

```java
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

public class WinRARUtil {

    private static final Charset CMD_CHARSET = Charset.forName("GBK");

    /* ================= 查找 WinRAR ================= */

    public static String findWinRAR() {
        try {
            String path = findByRegistry();
            if (path != null) return path;

            path = findByWhere();
            if (path != null) return path;

            path = findByCommonPath();
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    private static String findByRegistry() throws Exception {
        String[] keys = {
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinRAR.exe",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinRAR.exe"
        };

        for (String key : keys) {
            Process p = new ProcessBuilder("cmd", "/c", "reg query \"" + key + "\" /ve")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), CMD_CHARSET))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("REG_SZ")) {
                        return line.substring(line.lastIndexOf("REG_SZ") + 6).trim();
                    }
                }
            }
        }
        return null;
    }

    private static String findByWhere() throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "where winrar")
                .redirectErrorStream(true)
                .start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), CMD_CHARSET))) {
            return br.readLine();
        }
    }

    private static String findByCommonPath() {
        String[] paths = {
                "C:\\Program Files\\WinRAR\\WinRAR.exe",
                "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe"
        };
        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    /* ================= 压缩 ================= */

    /**
     * 压缩文件或目录
     *
     * @param source     源文件 / 目录
     * @param targetRar  目标 rar / zip 文件
     */
    public static void compress(String source, String targetRar) throws Exception {
        String winrar = requireWinRAR();

        List<String> cmd = Arrays.asList(
                winrar,
                "a",           // add
                "-r",          // 递归
                "-y",          // 全部 yes
                targetRar,
                source
        );

        execute(cmd);
    }

    /* ================= 解压 ================= */

    /**
     * 解压文件
     *
     * @param rarFile    rar / zip 文件
     * @param targetDir 解压目录
     */
    public static void extract(String rarFile, String targetDir) throws Exception {
        String winrar = requireWinRAR();

        List<String> cmd = Arrays.asList(
                winrar,
                "x",           // extract with full path
                "-o+",         // 覆盖
                "-y",          // 全部 yes
                rarFile,
                targetDir
        );

        execute(cmd);
    }

    /* ================= 公共执行 ================= */

    private static void execute(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), CMD_CHARSET))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("WinRAR 执行失败，exitCode=" + exitCode);
        }
    }

    private static String requireWinRAR() {
        String winrar = findWinRAR();
        if (winrar == null) {
            throw new RuntimeException("未检测到 WinRAR，请确认已安装");
        }
        return winrar;
    }
}
```

------

# 三、使用示例

### ✅ 压缩

```java
WinRARUtil.compress(
        "D:\\data\\logs",
        "D:\\backup\\logs.rar"
);
```

### ✅ 解压

```java
WinRARUtil.extract(
        "D:\\backup\\logs.rar",
        "D:\\restore\\"
);
```

------

# 四、为什么这个工具类是「工程级」的

✔ 自动发现 WinRAR
✔ 路径含空格 100% 安全
✔ 不死锁（合并错误流）
✔ 不依赖第三方库
✔ WinRAR 官方命令行参数

------

# 五、可扩展点

你很容易再加这些能力：

- 🔹 设置压缩密码 `-p123456`
- 🔹 指定压缩格式 zip / rar
- 🔹 排除文件 `-x*.log`
- 🔹 多卷压缩 `-v100m`
- 🔹 静默后台执行（不打印输出）



------

# 六、工程建议

> **Java 调用 WinRAR 的正确姿势：**

- 不解析 rar
- 不 JNI
- 只调用官方 CLI
- 用 ProcessBuilder

------

