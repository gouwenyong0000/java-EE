`String.format()` 是 Java `String` 类提供的一个强大的静态方法，用于创建格式化的字符串。它类似于 C 语言中的 `sprintf()` 函数，允许你通过一个 **格式字符串 (format string)** 和一系列 **参数 (arguments)** 来生成一个新的、精确控制格式的字符串。



java语言的格式打印受到C printf的启发。尽管格式字符串与C相似，但是已经进行了一些自定义来容纳Java语言并利用其某些功能。而且，Java格式比C更严格。例如，如果转换与标志不兼容，则将抛出异常。在C中，不适用的标志被默默地忽略。因此，格式字符串旨在识别为C程序员，但不一定与C中的字符串完全兼容

# 作用和目的

`String.format` 的主要作用是：

- **格式化输出**: 将各种数据类型（如数字、字符串、日期时间）按照预定义的、复杂的格式转换成字符串。
- **提高可读性**:  相较于使用大量的字符串拼接符 (`+`)，`String.format()` 使格式化逻辑更清晰、代码更易读和维护。
- **支持本地化 (Localization)**: 可以根据指定的 `Locale` (地区设置) 来格式化数字、日期、时间等，以适应不同国家或地区的表示习惯。



# 语法

## `String.format()`基本语法

`String.format()` 方法有两个重载形式：

1. **使用默认 Locale**:

   ```Java
   String formattedString = String.format(String format, Object... args);
   ```

   使用当前 JVM 的默认语言环境进行格式化。

2. **使用指定 Locale**:

   ```Java
   String formattedString = String.format(Locale locale, String format, Object... args);
   ```

   使用指定的 `locale` 进行格式化，例如数字的千位分隔符、日期格式等会根据 `locale` 变化。

   ```Java
   // 示例：使用美国 Locale 格式化十六进制数
   String hex = String.format(Locale.US, "Value in hex: %#x", 15); // "Value in hex: 0xf"
   ```

- `locale`: 一个 `java.util.Locale` 对象，指定本地化规则。
- `format`: 格式字符串，包含固定文本和格式说明符。
- `args`: 可变参数列表，用于替换格式字符串中的格式说明符。



##  格式说明符 (Format Specifiers)

格式说明符是 `String.format` 的核心，它定义了参数如何被格式化。一个格式说明符通常具有以下结构：

**一般、字符和数字类型的格式说明符**

```java
%[argument_index$][flags][width][.precision]conversion
```

- **argument_index**（可选）：一个十进制整数，指示参数列表中对应参数的位置。第一个参数用 `1$` 引用，第二个用 `2$`，依此类推。
- **flags**（可选）：一组字符，用于修改输出格式。可用的标志字符取决于具体的转换类型。
- **width**（可选）：一个正整数，指定输出的最小字符宽度。
- **precision**（可选）：一个非负整数，通常用于限制字符数，其具体含义依转换类型而异。
- **conversion**（必需）：一个字符，指示如何对参数进行格式化。可用的转换字符取决于参数的数据类型。



**日期/时间类型的格式说明符**

```java
%[argument_index$][flags][width]conversion
```

其中 `conversion` 必须是两个字符，第一个是 `t` 或 `T`，第二个字符指定具体的日期/时间格式。它们与 GNU `date` 或 POSIX `strftime(3c)` 的定义相似，但不完全相同。



**不对应任何参数的说明符**

```java
%[flags][width]conversion
```

这类说明符用于插入特定内容（例如百分号或行分隔符）而非参数值。





### 转换类型(conversion)

| 类别          | 转换字符                       | 说明                                     |
| ------------- | ------------------------------ | ---------------------------------------- |
| **通用**      | `b`/`B`, `h`/`H`, `s`/`S`      | 布尔、哈希、字符串                       |
| **字符**      | `c`/`C`                        | 单一 Unicode 字符                        |
| **整数**      | `d`, `o`, `x`/`X`              | 十进制、八进制、十六进制整数             |
| **浮点数**    | `e`/`E`, `f`, `g`/`G`, `a`/`A` | 科学计数法、小数、自动选择、十六进制浮点 |
| **日期/时间** | `t`/`T` + 后缀                 | 年月日时分秒、星期、时区、毫秒等多种格式 |
| **特殊**      | `%` (`%%`), `n`                | 百分号、平台相关的行分隔符               |

下面为每个转换类型分类各举一个示例，展示格式字符串、输入值及输出结果。

------

#### 通用（`b`/`B`, `h`/`H`, `s`/`S`）

```java
String.format("boolean: %b, HEX hash: %H, str: %s",
              null,      // b → "false"
              "Hi",      // H → 对象 hashCode 的十六进制大写
              123);      // s → "123"
```

- 输出示例（hash 值因 JVM 而异）:

  ```
  boolean: false, HEX hash: 6F, str: 123
  ```

------

#### 字符（`c`/`C`）

```java
String.format("char: %c, code point: %C",
              'A',       // c → 'A'
              9731);     // C → Unicode 9731 对应的字符 '☃' 并大写（无效时抛异常）
```

- 输出示例:

  ```
  char: A, code point: ☃
  ```

------

#### 整数（`d`, `o`, `x`/`X`）

```java
int n = 42;
System.out.println(String.format("dec: %d, oct: %#o, hex: %#X", n, n, n));
```

- `%d` → 十进制

- `%#o` → 八进制，带前导 `0`

- `%#X` → 十六进制大写，带前导 `0X`

- 输出示例:

  ```
  dec: 42, oct: 052, hex: 0X2A
  ```

------

#### 浮点数（`e`/`E`, `f`, `g`/`G`, `a`/`A`）

```java
double pi = Math.PI;
System.out.println(String.format(
    "scientific: %.3e, fixed: %.3f, auto: %.5g, hexfp: %a",
    pi, pi, pi, pi));
```

- `%.3e` → 科学计数法，小写，3 位小数

- `%.3f` → 定点，小数点后 3 位

- `%.5g` → 自动选择，总共 5 位有效数字

- `%a` → 十六进制浮点

- 输出示例:

  ```
  scientific: 3.142e+00, fixed: 3.142, auto: 3.1416, hexfp: 0x1.921fb54442d18p1
  ```

------

#### 日期/时间（`t`/`T` + 后缀）

```java
LocalDateTime dt = LocalDateTime.of(2025, 4, 11, 14, 23, 45);
System.out.println(String.format(
    "ISO date: %tF, time12: %tr, weekday: %tA",
    dt, dt, dt));
```

- `%tF` → ISO 8601 日期

- `%tr` → 12 小时制时间带 AM/PM

- `%tA` → 完整星期名称

- 输出示例:

  ```
  ISO date: 2025-04-11, time12: 02:23:45 PM, weekday: Friday
  ```

------

#### 特殊（`%` (`%%`), `n`）

```java
System.out.printf("Progress: 50%% complete.%nNext line.%n");
```

- `%%` → 输出单个 `%`

- `%n` → 平台相关的行分隔符

- 输出示例（假设 Unix 换行）:

  ```
  Progress: 50% complete.
  Next line.
  ```



### 标志（flags）

| 标志字符 | 含义                                                         | 适用转换                    |
| -------- | ------------------------------------------------------------ | --------------------------- |
| `-`      | 左对齐（默认右对齐）                                         | 通用、字符、数字、日期/时间 |
| `+`      | 在正数前输出 ‘+’；在负数前输出 ‘-’                           | 数字                        |
| (空格)   | 在正数前输出空格；在负数前输出 ‘-’                           | 数字                        |
| `0`      | 用 ‘0’ 填充；在宽度大于数字长度时在左侧填充                  | 数字                        |
| `,`      | 对整数部分按千位分组（每三位插入分隔符，受 Locale 影响）     | 数字、浮点                  |
| `_`      | 对整数部分按千位分组（每三位插入下划线，不受 Locale 影响）   | 数字、浮点                  |
| `(`      | 负数用括号包围，而不是用负号                                 | 数字                        |
| `#`      | “备用格式”——对不同转换有不同效果： • 对 `o`：保证前导 0 • 对 `x`/`X`：加上 `0x`/`0X` 前缀 • 对浮点：即使无小数部分也输出小数点 | 整数、浮点                  |
| `^`      | 将结果转换为大写（仅对 `s`/`S`、`t`/`T` 无效）               | 通用、字符、数字            |

### 宽度（width）

- **定义**：一个正整数，指定输出的最小字符数。
- **行为**：
  - 如果转换结果长度小于指定宽度，则根据对齐标志在左侧或右侧填充：
    - 默认右对齐；使用 `-` 标志可左对齐。
    - 对于数字类型，若同时指定了 `0` 标志，则在左侧填充 ‘0’ 而非空格。
  - 如果转换结果长度大于或等于宽度，则输出完整结果，不会截断。

数值示例

```java
String.format("|%5d|", 42);   // 输出 "|   42|"   （右对齐，空格填充）
String.format("|%-5d|", 42);  // 输出 "|42   |"   （左对齐，空格填充）
String.format("|%05d|", 42);  // 输出 "|00042|"   （右对齐，‘0’ 填充）
```

字符串示例

```java
String.format("|%10s|", "Java");   // 输出 "|      Java|"   （右对齐，空格填充）
String.format("|%-10s|", "Java");  // 输出 "|Java      |"   （左对齐，空格填充）
String.format("|%3s|", "Hello");   // 输出 "|Hello|"        （宽度小于字符串长度时不生效）
```



### 3. 精度（precision）

- **定义**：一个非负整数，常用于限制输出长度或小数位数。
- **行为**：
  - **字符串 (`s`/`S`)**：最多输出指定数量的字符，多余部分截断。
  - **浮点数 (`f`, `e`/`E`, `g`/`G`, `a`/`A`)**：指定小数点后的位数（`f`）或有效数字总数（`g`/`G`）。
  - **其它转换**：通常被忽略或视为非法。

示例：

```java
String.format("%.3s", "Hello");    // 输出 "Hel"
String.format("%.2f", 3.14159);    // 输出 "3.14"
String.format("%.4g", 123.456);    // 输出 "123.5"
```

------

### 4. 日期/时间后缀

使用 `%t` 或 `%T` 开头，后接下表中的字符来格式化日期/时间：

| 后缀 | 含义                                     | 示例（2025-04-11 14:23:45）  |
| ---- | ---------------------------------------- | ---------------------------- |
| `H`  | 24 小时制的小时（00–23）                 | 14                           |
| `I`  | 12 小时制的小时（01–12）                 | 02                           |
| `k`  | 24 小时制的小时（0–23）                  | 14                           |
| `l`  | 12 小时制的小时（1–12）                  | 2                            |
| `M`  | 分钟（00–59）                            | 23                           |
| `S`  | 秒（00–60）                              | 45                           |
| `L`  | 毫秒（000–999）                          | 000                          |
| `N`  | 纳秒（000000000–999999999）              | 000000000                    |
| `p`  | 上/下午标记（小写）                      | pm                           |
| `z`  | 时区偏移（+/-HHMM）                      | +0800                        |
| `Z`  | 时区名称                                 | CST                          |
| `s`  | 自纪元（1970-01-01T00:00:00Z）以来的秒数 | 1712850225                   |
| `Q`  | 自纪元以来的毫秒数                       | 1712850225000                |
| `B`  | 月份名称（完整）                         | April                        |
| `b`  | 月份名称（简写）                         | Apr                          |
| `h`  | 同 `b`                                   | Apr                          |
| `A`  | 星期名称（完整）                         | Friday                       |
| `a`  | 星期名称（简写）                         | Fri                          |
| `C`  | 年份的前两位（世纪）                     | 20                           |
| `Y`  | 年份（四位）                             | 2025                         |
| `y`  | 年份（后两位）                           | 25                           |
| `j`  | 年内天数（001–366）                      | 101                          |
| `m`  | 月份（01–12）                            | 04                           |
| `d`  | 日（01–31）                              | 11                           |
| `e`  | 日（1–31）                               | 11                           |
| `R`  | 时间（24 小时制，HH:MM）                 | 14:23                        |
| `T`  | 时间（HH:MM:SS）                         | 14:23:45                     |
| `r`  | 时间（12 小时制，hh:MM:SS a）            | 02:23:45 pm                  |
| `D`  | 日期（MM/DD/YY）                         | 04/11/25                     |
| `F`  | ISO 8601 日期（YYYY-MM-DD）              | 2025-04-11                   |
| `c`  | 完整日期时间表示                         | Fri Apr 11 14:23:45 CST 2025 |





# 示例

以下是一些 `String.format` 的使用示例，展示了不同的格式说明符和标志：

## **基本类型格式化:**

```Java
int age = 30;
String name = "李华";
double height = 1.75;

String info = String.format("姓名: %s, 年龄: %d, 身高: %.2f米", name, age, height);
System.out.println(info); // 输出: 姓名: 李华, 年龄: 30, 身高: 1.75米
```

## **宽度和对齐:**

```Java
String formatWidth = String.format("|%10s|%-10s|", "右对齐", "左对齐");
System.out.println(formatWidth); // 输出: |     右对齐|左对齐     |

String formatNumberWidth = String.format("|%5d|", 123);
System.out.println(formatNumberWidth); // 输出: |  123| (右对齐，宽度为5)

String formatZeroPadding = String.format("|%05d|", 123);
System.out.println(formatZeroPadding); // 输出: |00123| (用零填充)
```

## **精度:**

```Java
double pi = 3.1415926;
String formatPrecision = String.format("%.3f", pi);
System.out.println(formatPrecision); // 输出: 3.142 (保留三位小数)

String formatStringPrecision = String.format("%.5s", "Hello World");
System.out.println(formatStringPrecision); // 输出: Hello (字符串截取前5个字符)
```

## **日期和时间:**

```Java
import java.util.Date;

Date now = new Date();
String formatDate = String.format("%tY年%tm月%td日 %tA", now, now, now, now);
System.out.println(formatDate); // 输出: 2025年02月11日 星期二 (日期会根据当前时间变化)

String formatTime = String.format("%tH:%tM:%tS", now, now, now);
System.out.println(formatTime); // 输出: 03:32:44 (时间会根据当前时间变化)

String formatDateTime = String.format("%tc", now);
System.out.println(formatDateTime); // 输出: Tue Feb 11 03:32:44 CST 2025 (完整日期时间)
```

## **多次引用同一个参数**

`String.format` 允许你在格式字符串中多次引用同一个参数。这可以通过**索引说明符**来实现。索引说明符允许你指定要格式化的参数的位置。

在 `String.format` 中，索引说明符的语法是 `"%<index>$<conversion>"`，其中 `<index>` 是参数的索引（从 1 开始），而 `<conversion>` 是转换字符（例如 `%s`, `%d`, `%f` 等）。

**示例：**

假设我们有一个名字 "小明"，我们想在格式化字符串中多次使用它。

```Java
String name = "小明";
String message = String.format("欢迎您，%1$s！您好，%1$s！再次欢迎，%1$s！", name);
System.out.println(message);
```

## **其他标志:**

```Java
int positiveNumber = 123;
int negativeNumber = -123;

String formatPlusSign = String.format("%+d %+d", positiveNumber, negativeNumber);
System.out.println(formatPlusSign); // 输出: +123 -123 (正数显示正号)

String formatComma = String.format("%,d", 1234567);
System.out.println(formatComma); // 输出: 1,234,567 (千位分隔符)
```



