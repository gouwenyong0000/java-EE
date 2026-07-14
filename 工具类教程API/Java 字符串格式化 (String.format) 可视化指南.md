# Java 字符串格式化 (String.format) 可视化指南

## ✅ 简介

`String.format()` 是 Java 中非常强大的字符串格式化工具，允许你以类似 C 的 `printf` 风格来构建格式化的字符串。 它广泛用于日志输出、界面显示、报表生成、数据转换等场景。

> 💡 **注意**：`String.format()` 本质上是 `java.util.Formatter` 的便捷封装。`System.out.printf()` 和 `String.format()` 使用完全相同的语法，前者直接打印到控制台，后者返回一个字符串。

## 🧩 一、基本语法结构

每个格式说明符（占位符）都遵循以下结构：

```
%[argument_index$][flags][width][.precision]conversion
```

| **组件**            | **说明**                                               |
| ------------------- | ------------------------------------------------------ |
| **`%`**             | 格式说明符起始符号                                     |
| `[argument_index$]` | **(可选)** 参数索引（如 `%2$s` 表示第 2 个参数）       |
| `[flags]`           | **(可选)** 控制对齐方式、填充、符号等                  |
| `[width]`           | **(可选)** 最小字段宽度                                |
| `[.precision]`      | **(可选)** 精度（浮点数的小数位或字符串的最大长度）    |
| `conversion`        | **(必填)** 转换符，指定数据类型（如 `%d`, `%f`, `%s`） |



## 🔢 二、常用转换类型 (conversion)



| **转换字符** | **数据类型**               | **示例**  | **输出**              |
| ------------ | -------------------------- | --------- | --------------------- |
| `%d`         | 整数（十进制）             | `123`     | `123`                 |
| `%o`         | 八进制整数                 | `123`     | `173`                 |
| `%x` / `%X`  | 十六进制整数（小写/大写）  | `255`     | `ff` / `FF`           |
| `%f`         | 浮点数                     | `45.67`   | `45.670000`           |
| `%e` / `%E`  | 科学计数法                 | `45.67`   | `4.567000e+01`        |
| `%g` / `%G`  | 自动选择 `%f` 或科学计数法 | `1234.56` | `1234.56`             |
| `%s` / `%S`  | 字符串（原样 / 全大写）    | `"hello"` | `"hello"` / `"HELLO"` |
| `%c` / `%C`  | 单个字符（原样 / 大写）    | `'a'`     | `'a'` / `'A'`         |
| `%b` / `%B`  | 布尔值                     | `true`    | `true` / `TRUE`       |
| `%n`         | 换行符（平台无关）         | N/A       | `\n` (或 `\r\n`)      |
| `%%`         | 输出一个 `%` 符号          | N/A       | `%`                   |



#### 💡 示例代码

```Java
int i = 123;
double d = 123.456;
String s = "Java";
boolean b = true;
char c = 'Z';

System.out.println(String.format("整数: %d", i));          // 整数: 123
System.out.println(String.format("八进制: %o", i));          // 八进制: 173
System.out.println(String.format("十六进制 (小写): %x", 255)); // 十六进制 (小写): ff
System.out.println(String.format("十六进制 (大写): %X", 255)); // 十六进制 (大写): FF
System.out.println(String.format("浮点数: %f", d));          // 浮点数: 123.456000
System.out.println(String.format("科学计数法: %e", d));      // 科学计数法: 1.234560e+02
System.out.println(String.format("通用浮点数: %g", d));      // 通用浮点数: 123.456
System.out.println(String.format("字符串: %s", s));          // 字符串: Java
System.out.println(String.format("字符串转大写: %S", s));    // 字符串转大写: JAVA
System.out.println(String.format("字符: %c", c));          // 字符: Z
System.out.println(String.format("布尔值: %b", b));          // 布尔值: true
System.out.println(String.format("布尔值转大写: %B", b));    // 布尔值转大写: TRUE
System.out.println(String.format("百分号: %%"));          // 百分号: %
System.out.printf("换行%n");                         // (换行)
```



## 🔧 三、标志 (flags) 使用可视化

| **标志**       | **描述**                     | **示例** | **输出**     |
| -------------- | ---------------------------- | -------- | ------------ |
| `-`            | 左对齐（默认右对齐）         | `%-10s`  | `Hello `     |
| `+`            | 强制显示正负号               | `%+d`    | `+123`       |
| `     ` (空格) | 正数前加空格（负数显示-）    | `% d`    | ` 123`       |
| `0`            | 用零填充空白部分（用于数字） | `%010d`  | `0000000123` |
| `,`            | 千分位分隔符                 | `%,d`    | `1,234,567`  |
| `(`            | 负数用括号括起               | `%(d`    | `(123)`      |
| `#`            | 添加前缀（如 `0x`, `0`）     | `%#x`    | `0x7b`       |



#### 💡 示例代码

```java
int num = -12345;
int positiveNum = 89;

System.out.println(String.format("左对齐: |%-10d|", 123));   // 左对齐: |123       |
System.out.println(String.format("强制显示符号: %+d", positiveNum)); // 强制显示符号: +89
System.out.println(String.format("强制显示符号: %+d", num));     // 强制显示符号: -12345
System.out.println(String.format("正数前加空格: |% d|", positiveNum)); // 正数前加空格: | 89|
System.out.println(String.format("零填充: %010d", 123));        // 零填充: 0000000123
System.out.println(String.format("千分位: %,d", 1234567));      // 千分位: 1,234,567
System.out.println(String.format("负数括号: %(d", num));        // 负数括号: (12345)
System.out.println(String.format("十六进制带前缀: %#x", 255));    // 十六进制带前缀: 0xff
System.out.println(String.format("八进制带前缀: %#o", 64));      // 八进制带前缀: 0100
```



## 📏 四、宽度 (width) 和精度 (.precision) 可视化

- **Width**：指定最小输出宽度。如果字符串或数字不够宽，会用空格（或 `0` 标志）填充。
- **Precision**：
  - 对于浮点数（`%f`）：指定小数点后的位数。
  - 对于字符串（`%s`）：指定截取的最大字符数。

| **格式符** | **输入**                  | **输出效果**   |
| ---------- | ------------------------- | -------------- |
| `%10s`     | `"Hi"`                    | `" Hi"`        |
| `%-10s`    | `"Hi"`                    | `"Hi "`        |
| `%010d`    | `123`                     | `"0000000123"` |
| `%.2f`     | `3.1415`                  | `"3.14"`       |
| `%10.2f`   | `3.1415`                  | `" 3.14"`      |
| `%.10s`    | `"This is a long string"` | `"This is a "` |



#### 💡 示例代码

```Java
double pi = Math.PI; // 3.1415926...
String text = "This is a long string";

System.out.println(String.format("|%10s|", "Hello"));       // |     Hello|
System.out.println(String.format("|%-10s|", "Hi"));         // |Hi        |
System.out.println(String.format("|%.2f|", pi));            // |3.14|
System.out.println(String.format("|%10.2f|", pi));          // |      3.14|
System.out.println(String.format("|%.10s|", text));         // |This is a |
```



## 🔄 五、参数索引重用



当你需要重排参数顺序，或者多次使用同一个参数时，索引非常有用。

- `%1$`：使用第 1 个参数。
- `%2$`：使用第 2 个参数。

```Java
// %2$s 使用第二个参数 (Hello), %1$d 使用第一个参数 (10)
String result = String.format("%2$s %1$d %2$s again", 10, "Hello");
System.out.println(result); // "Hello 10 Hello again"
```



## 🌍 六、国际化支持 (Locale)



String.format() 允许传入一个 Locale 参数，以便根据不同地区的习惯格式化数字（尤其是千分位和

小数点的符号）。

```Java
import java.util.Locale;

double number = 1234567.89;

// 默认 Locale (取决于你的
System.out.println(String.format("默认: %,.2f", number));

// 美国格式: 逗号 , 作为千分位，点 . 作为小数
System.out.println(String.format(Locale.US, "美国格式: %,.2f", number));
// > 美国格式: 1,234,567.89

// 德国格式: 点 . 作为千分位，逗号 , 作为小数
System.out.println(String.format(Locale.GERMANY, "德国格式: %,.2f", number));
// > 德国格式: 1.234.567,89

// 法国格式 (使用空格作为千分位)
System.out.println(String.format(Locale.FRANCE, "法国格式: %,.2f", number));
// > 法国格式: 1 234 567,89
```



## 🕒 七、日期与时间格式化



日期/时间格式化比较特殊，它们都以 `%t` (或 `%T`) 开头，后面再跟一个特定的转换符。

| **转换符**          | **含义**                   | **示例输出**                       |
| ------------------- | -------------------------- | ---------------------------------- |
| **复合类型 (推荐)** |                            |                                    |
| `%tF`               | ISO 8601 日期 (YYYY-MM-DD) | `2025-11-11`                       |
| `%tT`               | ISO 8601 时间 (HH:MM:SS)   | `00:35:11`                         |
| `%tc`               | 完整的日期和时间（本地化） | `周二 十一月 11 00:35:11 CST 2025` |
| **单独组件**        |                            |                                    |
| `%tY`               | 年（四位）                 | `2025`                             |
| `%tm`               | 月（两位, 01-12）          | `11`                               |
| `%td`               | 日（两位, 01-31）          | `11`                               |
| `%tH`               | 小时（24制, 00-23）        | `00`                               |
| `%tM`               | 分钟（00-59）              | `35`                               |
| `%tS`               | 秒（00-60）                | `11`                               |
| `%tL`               | 毫秒（000-999）            | `123`                              |
| `%tp`               | 上午/下午 (本地化)         | `上午`                             |



#### 💡 示例代码



**重要**：在格式化日期时，强烈推荐使用**参数索引**（如 `%1$`），否则你需要为每一个 `%t` 转换符传递一个 `Date` 对象。

```Java
import java.util.Date;
import java.util.Calendar;

Date now = new Date(); // 或者 Calendar.getInstance()

// 推荐：使用 %1$ 索引复用 'now' 参数
String.format("今天是 %1$tF %1$tT", now);
// > "今天是 2025-11-11 00:35:11"

// 组合使用
String.format("详细拆分: %1$tY年%1$tm月%1$td日", now);
// > "详细拆分: 2025年11月11日"

// 格式化 Long 类型的时间戳
Long timestamp = System.currentTimeMillis();
String.format("时间戳 %1$tF %1$tT.%1$tL", timestamp);
// > "时间戳 2025-11-11 00:35:11.123"
```



## ⚙️ 八、Formatter 类使用示例



当你需要向 `StringBuilder`、文件或流写入格式化内容时，可直接使用 `Formatter`。

```Java
import java.util.Formatter;

// 默认格式化到 StringBuilder
try (Formatter f = new Formatter()) {
    f.format("Name: %s, Score: %d", "Tom", 90);
    System.out.println(f.toString()); // > "Name: Tom, Score: 90"
}
```



## ⚠️ 九、易错点与注意事项



| **常见问题**                 | **说明**                                                     |
| ---------------------------- | ------------------------------------------------------------ |
| `%d` 接收浮点数              | 抛出 `IllegalFormatConversionException` (类型不匹配)         |
| `%f` 默认保留六位小数        | 需指定精度，如 `%.2f`                                        |
| `%s` 可自动调用 `toString()` | 传入任何对象（包括 null），`%s` 会自动调用 `obj.toString()` (null 会输出 "null") |
| `%n` vs `\n`                 | `String.format()` 中推荐使用 `%n` 替代 `\n`，以保证跨平台换行符一致。 |
| 多参数顺序错误               | 当参数较多时，容易混淆。可使用索引 `%2$s` 明确指定。         |
| `%b` 是布尔值                | **`%b` 不是二进制！** 它是用于 `boolean` 类型的。            |



## 📋 十、推荐使用场景



| **场景**                | **推荐方法**                                     |
| ----------------------- | ------------------------------------------------ |
| 简单数字转字符串        | `String.valueOf(x)` 或 `Integer.toString(x)`     |
| 数字转字符串 + 控制格式 | `String.format("%04d", x)` (如补零)              |
| 多种类型混合格式化      | `String.format("User %s (ID: %d)", name, id)`    |
| 国际化数字格式          | `String.format(Locale, "%,.2f", money)`          |
| 日志输出                | `System.out.printf(...)` 或 `Logger.printf(...)` |



## 🧩 十一、实用模板片段

```Java
// 模板1: 金额格式化 (千分位 + 两位小数)
String money = String.format("%,.2f", 1234567.89);
// > "1,234,567.89"

// 模板2: 百分比格式化 (自动乘 100 并保留 2 位小数 + '%')
String percent = String.format("%.2f%%", 0.8567 * 100);
// > "85.67%" (注意 '%%' 用于转义)

// 模板3: 日志输出 (带时间戳)
System.out.printf("[%1$tF %1$tT] INFO - %2$s%n", new Date(), "Operation success");
// > "[2025-11-11 00:35:11] INFO - Operation success"

// 模板4: 补零 (常用于生成ID)
String id = String.format("%08d", 1234);
// > "00001234"
```



## 🧮 十二、数字进制输出模板



这是你最初关心的重点：二进制和十六进制的格式化。



### 1️⃣ 十进制 (`%d`)

标准整数格式化。

```Java
int n = 12345;
System.out.println(String.format("默认: %d", n));    // 默认: 12345
System.out.println(String.format("带符号: %+d", n));   // 带符号: +12345
System.out.println(String.format("空格符号: % d", n));   // 空格符号:  12345
System.out.println(String.format("右对齐10位: |%10d|", n)); // 右对齐10位: |     12345|
System.out.println(String.format("千分位: %,d", 1234567)); // 千分位: 1,234,567
```



### 2️⃣ 八进制 (`%o`)

```java
int n = 123;
System.out.println(String.format("八进制: %o", n));     // 八进制: 173
System.out.println(String.format("带前缀: %#o", n));     // 带前缀: 0173
```



### 3️⃣ 十六进制 (`%x` / `%X`)

```java
int n = 255;
System.out.println(String.format("小写: %x", n));        // 小写: ff
System.out.println(String.format("大写: %X", n));        // 大写: FF
System.out.println(String.format("带前缀(小): %#x", n)); // 带前缀(小): 0xff
System.out.println(String.format("带前缀(大): %#X", n)); // 带前缀(大): 0XFF
System.out.println(String.format("补零4位: %04X", n));   // 补零4位: 00FF
```



### 4️⃣ 二进制 (无直接转换符)

`String.format()` **没有** 为整数提供直接的二进制转换符（`%b` 是用于布尔值的）。

你必须先用 `Integer.toBinaryString(int)` 转换，然后用 `%s` (字符串) 来格式化它。

```Java
int n = 13;
String binaryString = Integer.toBinaryString(n); // "1101"

// 直接输出
System.out.println("Binary: " + binaryString); // "Binary: 1101"

// 模板: 补零到 8-bit (一个字节)
// 1. 先转为二进制字符串
// 2. 用 %s 格式化，指定宽度 8
// 3. 用 replace() 把 format 自动填充的 ' ' (空格) 替换为 '0'
String binary8bit = String.format("%8s", binaryString).replace(' ', '0');
System.out.println("Binary 8-bit: " + binary8bit); // "Binary 8-bit: 00001101"

// 模板: 补零到 16-bit
String binary16bit = String.format("%16s", binaryString).replace(' ', '0');
System.out.println("Binary 16-bit: " + binary16bit); // "Binary 16-bit: 0000000000001101"
```



### 5️⃣ 综合输出示例



```Java
int num = 255;

System.out.println(String.format("Decimal       : %d", num));
System.out.println(String.format("Decimal + sign: %+d", num));
System.out.println(String.format("Octal         : %o", num));
System.out.println(String.format("Octal #       : %#o", num));
System.out.println(String.format("Hex (lower)   : %x", num));
System.out.println(String.format("Hex (upper)   : %X", num));
System.out.println(String.format("Hex #         : %#X", num));
System.out.println("Binary        : " + Integer.toBinaryString(num));
System.out.println("Binary 8-bit  : " + String.format("%8s", Integer.toBinaryString(num)).replace(' ', '0'));
System.out.println("Binary 16-bit : " + String.format("%16s", Integer.toBinaryString(num)).replace(' ', '0'));
```

**输出示例：**

```
Decimal       : 255
Decimal + sign: +255
Octal         : 377
Octal #       : 0377
Hex (lower)   : ff
Hex (upper)   : FF
Hex #         : 0XFF
Binary        : 11111111
Binary 8-bit  : 11111111
Binary 16-bit : 0000000011111111
```



