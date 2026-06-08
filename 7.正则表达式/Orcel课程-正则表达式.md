# 大纲

本课程介绍如何使用 `java.util.regex` API 与正则表达式进行模式匹配。虽然此程序包接受的语法类似于 [Perl](http://www.perl.com/) 编程语言，但 Perl 的知识不是先决条件。本课程从基础知识开始，逐步构建以涵盖更高级的技术。

正则表达式（Regular Expression）是一种**描述字符串模式的语言**，用于：

- 校验（Validation）
- 查找（Search）
- 提取（Extract）
- 替换（Replace）

Java 使用 `java.util.regex`，属于 **NFA 回溯型正则引擎**。

## 学习目标与路线图

- **会读**：看懂常见正则表达式
- **会写**：独立完成校验、提取、替换
- **写得对**：避免 matches / find 误用
- **写得稳**：理解回溯与性能
- **写得安全**：避免 ReDoS 风险

能力分级：

- L1：基础语法
- L2：分组与断言
- L3：Java API
- L4：性能与回溯
- L5：工程与安全



# 介绍

## 什么是正则表达式？

*Regular expressions (正则表达式)* 是一种基于集合中每个字符串共享的共同特征来描述一组字符串的方法。它们可用于搜索，编辑或操作文本和数据。你必须学习一种特定的语法来创建正则表达式  超出 Java 编程语言的正则语法。正则表达式的复杂程度各不相同，但是一旦你理解了它们的构造基础，你就能够解密(或创建)任何正则表达式。

该路径教授 [`java.util.regex`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/package-summary.html) API 支持的正则表达式语法，并提供了几个工作示例来说明各种对象如何交互。在正则表达式的世界中，有许多不同的风格可供选择，例如 grep，Perl，Tcl，Python，PHP 和 awk。`java.util.regex` API 中的正则表达式语法与 Perl 中的类似。

## 如何在此包中表示正则表达式？

`java.util.regex` 包主要由三个类组成：[`Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)，[`Matcher`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html)，和 [`PatternSyntaxException`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html)。

- `Pattern` 对象是正则表达式的编译表示。`Pattern` 类不提供公共构造函数。要创建模式，必须首先调用其中一个 `public static compile` 方法，然后返回 `Pattern` 对象。这些方法接受正则表达式作为第一个参数；该路径的前几课将教你所需的语法。
- `Matcher` 对象是解释模式并对输入字符串执行匹配操作的引擎。与 `Pattern` 类一样，`Matcher` 不定义公共构造函数。通过在 `Pattern` 对象上调用 `matcher` 方法获得 `Matcher` 对象。
- `PatternSyntaxException` 对象是非检查型异常，表示正则表达式模式中的语法错误。

该路径的最后几课详细探讨了每个类。但首先，你必须了解实际构造正则表达式的方式。因此，下一节将介绍一个简单的测试工具，将重复使用它来探索其语法。

# 测试工具

本节定义了一个可重用的测试工具 [`RegexTestHarness.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/RegexTestHarness.java)，用于探索此 API 支持的正则表达式构造。运行此代码的命令是 `java RegexTestHarness`;不需要命令行参数。应用程序重复循环，提示用户输入正则表达式和输入字符串。使用此测试工具是可选的，但你可能会发现浏览以下页面中讨论的测试用例很方便。

```java
import java.io.Console;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RegexTestHarness {

    public static void main(String[] args){
        // System.console() 仅在纯命令行（终端 / CMD） 环境下有效，IDEA/Eclipse 等 IDE 的控制台会返回 null，导致触发 No console. 退出逻辑
        Console console = System.console();
        if (console == null) {
            System.err.println("No console.");
            System.exit(1);
        }
        while (true) {

            Pattern pattern =Pattern.compile(console.readLine("%nEnter your regex: "));
            Matcher matcher =pattern.matcher(console.readLine("Enter input string to search: "));

            boolean found = false;
            while (matcher.find()) {
                console.format("I found the text" +
                    " \"%s\" starting at " +
                    "index %d and ending at index %d.%n",
                    matcher.group(),
                    matcher.start(),
                    matcher.end());
                found = true;
            }
            if(!found){
                console.format("No match found.%n");
            }
        }
    }
}
```



> ```cmd
>  javac RegexTestHarness.java  && java RegexTestHarness
> ```
>
> 

在继续下一节之前，请保存并编译此代码，以确保你的开发环境支持所需的包。

# 字符串字面量

此 API 支持的最基本的模式匹配形式是字符串字面量的匹配。例如，如果正则表达式为 `foo` 且输入字符串为 `foo`，则匹配将成功，因为字符串相同。尝试使用测试工具：

```
Enter your regex: foo
Enter input string to search: foo
I found the text foo starting at index 0 and ending at index 3.
```

该匹配成功。请注意，虽然输入字符串长度为 3 个字符，但起始索引为 0，结束索引为 3。按照规范，**范围包括起始索引且排除结束索引**，如下图所示：字符串文字 foo，带有编号单元格和索引值。

```
          cell 0       cell 1       cell 2
┌──────────────┬──────────────┬──────────────┐
│      f       │      o       │      o       │
└──────────────┴──────────────┴──────────────┘
       ↓               ↓               ↓
     index 0         index 1         index 2
```

字符串中的每个字符都驻留在自己的 *cell (单元格)* 中，索引位置指向每个单元格。字符串“foo”从索引 0 开始，到索引 3 结束，即使字符本身只占用单元格 0,1 和 2。

随后的匹配，你会发现一些重叠；下一个匹配的起始索引与上一个匹配的结束索引相同：

```
Enter your regex: foo
Enter input string to search: foofoofoo
I found the text foo starting at index 0 and ending at index 3.
I found the text foo starting at index 3 and ending at index 6.
I found the text foo starting at index 6 and ending at index 9.
```

## 元字符

此 API 还支持许多影响模式匹配方式的特殊字符。将正则表达式更改为 `cat.`，将输入字符串更改为 `cats`。输出显示如下：

```
Enter your regex: cat.
Enter input string to search: cats
I found the text cats starting at index 0 and ending at index 4.
```

即使输入字符串中不存在点“`.`”，匹配仍会成功。它成功是因为点是 *metacharacter (元字符)*  由匹配器解释的具有特殊含义的字符。元字符“.”表示“任何字符”，这就是本例中匹配成功的原因。

此API支持的元字符为：`<([{\^-=$!|]})?*+.>`

------

**注意:** 在某些情况下，上面列出的特殊字符将 *不* 被视为元字符。当你了解有关如何构造正则表达式的更多信息时，你将遇到此问题。但是，你可以使用此列表检查特定字符是否会被视为元字符。例如，字符 `@` 和 `#` 从不具有特殊含义。

------

有两种方法可以强制将元字符视为普通字符：

- 在元字符前面加一个反斜杠，或者
- 将其括在 `\Q`(开始引用)和 `\E`(结束它)中。

使用此技术时，`\Q` 和 `\E` 可放置在表达式中的任何位置，前提是 `\Q` 首先出现。

> tips:
>
> `Pattern.quote(字符串)` 的核心作用：**把带正则特殊符号（如 `.、+、\*、?` 等）的普通字符串，转成「纯字面匹配」的正则**，让这些特殊符号不再被当作正则语法解析，而是按「原样匹配」
>
> ```java
> String winPath = "C:\\Users\\test.txt"; // Java字符串中\需转义为\\
>         
> // ❶ 不用quote：正则匹配\需写4个\（Java转义一次成\\，正则再转义一次成\）
> Pattern p1 = Pattern.compile("\\\\"); 
> Matcher m1 = p1.matcher(winPath);
> System.out.println("匹配到\\的次数（不用quote）：" + m1.results().count()); // 输出2
> 
> // ❷ 用quote：直接传\（Java字符串写\\），自动转义，无需写4个\
> Pattern p2 = Pattern.compile(Pattern.quote("\\")); // 等价于匹配\Q\\E
> Matcher m2 = p2.matcher(winPath);
> System.out.println("匹配到\\的次数（用quote）：" + m2.results().count()); // 输出2
> ```
>
> 



# 字符类

如果浏览 [`Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html) 类规范，你将看到总结支持的正则表达式结构的表。在“字符类”部分中，你将找到以下内容：



| 构造            | 描述                                   |
| --------------- | -------------------------------------- |
| `[abc]`         | a，b 或 c(简单类)                      |
| `[^abc]`        | 除 a，b 或 c 之外的任何字符(否定)      |
| `[a-zA-Z]`      | a 到 z，或 A 到 Z，包括(范围)          |
| `[a-d[m-p]]`    | a 到 d，或 m 到 p：[a-dm-p] (联合)     |
| `[a-z&&[def]]`  | d，e 或 f (交叉)                       |
| `[a-z&&[^bc]]`  | a 到 z，b 和 c 除外：[ad-z] (减法)     |
| `[a-z&&[^m-p]]` | a 到 z，但不是 m 到 p：[a-lq-z] (减法) |

左侧列指定正则表达式构造，而右侧列描述每个构造将匹配的条件。

------

**注意:** 短语“character class”中的“class”一词不是指 `.class` 文件。在正则表达式的上下文中，*character class (字符类)* 是括在方括号内的一组字符。它指定将成功匹配给定输入字符串中的单个字符的字符。

------

## 简单类

字符类的最基本形式是简单地将一组字符并排放在方括号内。例如，表达式 `[bcr]at` 将匹配单词“bat”，“cat”或“rat”，因为它定义了一个字符类(接受“b”，“c”，或“r”)作为其第一个字符。

```
Enter your regex: [bcr]at
Enter input string to search: bat
I found the text "bat" starting at index 0 and ending at index 3.

Enter your regex: [bcr]at
Enter input string to search: cat
I found the text "cat" starting at index 0 and ending at index 3.

Enter your regex: [bcr]at
Enter input string to search: rat
I found the text "rat" starting at index 0 and ending at index 3.

Enter your regex: [bcr]at
Enter input string to search: hat
No match found.
```

在上面的示例中，只有当第一个字母与字符类定义的字符之一匹配时，整体匹配才会成功。

### 否定

要匹配 *除了* 列出字符之外的所有字符，请在字符类的开头插入“`^`”元字符。该技术称为 *negation (否定)*。

```
Enter your regex: [^bcr]at
Enter input string to search: bat
No match found.

Enter your regex: [^bcr]at
Enter input string to search: cat
No match found.

Enter your regex: [^bcr]at
Enter input string to search: rat
No match found.

Enter your regex: [^bcr]at
Enter input string to search: hat
I found the text "hat" starting at index 0 and ending at index 3.
```

仅当输入字符串的第一个字符 *不* 包含字符类定义的任何字符时，匹配才会成功。

### 范围

有时你需要定义一个包含一系列值的字符类，例如字母“a 到 h”或数字“1 到 5”。要指定范围，只需在要匹配的第一个和最后一个字符之间插入“`-`”元字符，例如 `[1-5]` 或 `[a-h]`。你还可以在类中将不同的范围放在彼此旁边，以进一步扩展匹配的可能性。例如，`[a-zA-Z]` 将匹配字母表中的任何字母：a 到 z(小写)或 A 到 Z(大写)。

以下是范围和否定的一些示例：

```
Enter your regex: [a-c]
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.

Enter your regex: [a-c]
Enter input string to search: b
I found the text "b" starting at index 0 and ending at index 1.

Enter your regex: [a-c]
Enter input string to search: c
I found the text "c" starting at index 0 and ending at index 1.

Enter your regex: [a-c]
Enter input string to search: d
No match found.

Enter your regex: foo[1-5]
Enter input string to search: foo1
I found the text "foo1" starting at index 0 and ending at index 4.

Enter your regex: foo[1-5]
Enter input string to search: foo5
I found the text "foo5" starting at index 0 and ending at index 4.

Enter your regex: foo[1-5]
Enter input string to search: foo6
No match found.

Enter your regex: foo[^1-5]
Enter input string to search: foo1
No match found.

Enter your regex: foo[^1-5]
Enter input string to search: foo6
I found the text "foo6" starting at index 0 and ending at index 4.
```

### 联合

你还可以使用 *unions (联合)* 创建由两个或多个单独的字符类组成的单个字符类。要创建联合，只需将一个类嵌套在另一个类中，例如 `[0-4[6-8]]`。此特定联合创建一个与数字 0,1,2,3,4,6,7 和 8 匹配的单个字符类。

```
Enter your regex: [0-4[6-8]]
Enter input string to search: 0
I found the text "0" starting at index 0 and ending at index 1.

Enter your regex: [0-4[6-8]]
Enter input string to search: 5
No match found.

Enter your regex: [0-4[6-8]]
Enter input string to search: 6
I found the text "6" starting at index 0 and ending at index 1.

Enter your regex: [0-4[6-8]]
Enter input string to search: 8
I found the text "8" starting at index 0 and ending at index 1.

Enter your regex: [0-4[6-8]]
Enter input string to search: 9
No match found.
```

### 交集

要创建仅匹配其所有嵌套类共有的字符的单个字符类，请使用 `&&`，如 `[0-9&&[345]]`。此特定交集创建单个字符类，仅匹配两个字符类共有的数字：3,4 和 5。

```
Enter your regex: [0-9&&[345]]
Enter input string to search: 3
I found the text "3" starting at index 0 and ending at index 1.

Enter your regex: [0-9&&[345]]
Enter input string to search: 4
I found the text "4" starting at index 0 and ending at index 1.

Enter your regex: [0-9&&[345]]
Enter input string to search: 5
I found the text "5" starting at index 0 and ending at index 1.

Enter your regex: [0-9&&[345]]
Enter input string to search: 2
No match found.

Enter your regex: [0-9&&[345]]
Enter input string to search: 6
No match found.
```

这是一个显示两个范围交集的示例：

```
Enter your regex: [2-8&&[4-6]]
Enter input string to search: 3
No match found.

Enter your regex: [2-8&&[4-6]]
Enter input string to search: 4
I found the text "4" starting at index 0 and ending at index 1.

Enter your regex: [2-8&&[4-6]]
Enter input string to search: 5
I found the text "5" starting at index 0 and ending at index 1.

Enter your regex: [2-8&&[4-6]]
Enter input string to search: 6
I found the text "6" starting at index 0 and ending at index 1.

Enter your regex: [2-8&&[4-6]]
Enter input string to search: 7
No match found.
```

### 减法

最后，你可以使用 *subtraction (减法)* 来否定一个或多个嵌套字符类，例如 `[0-9&&[^345]]`。此示例创建一个匹配 0 到 9，*except (除了)* 数字 3,4 和 5 之外的所有内容的单个字符类。

```
Enter your regex: [0-9&&[^345]]
Enter input string to search: 2
I found the text "2" starting at index 0 and ending at index 1.

Enter your regex: [0-9&&[^345]]
Enter input string to search: 3
No match found.

Enter your regex: [0-9&&[^345]]
Enter input string to search: 4
No match found.

Enter your regex: [0-9&&[^345]]
Enter input string to search: 5
No match found.

Enter your regex: [0-9&&[^345]]
Enter input string to search: 6
I found the text "6" starting at index 0 and ending at index 1.

Enter your regex: [0-9&&[^345]]
Enter input string to search: 9
I found the text "9" starting at index 0 and ending at index 1.
```

现在我们已经介绍了如何创建字符类，你可能希望在继续下一部分之前查看 [Character Classes table](https://pingfangx.github.io/java-tutorials/essential/regex/char_classes.html#CHART)。



# 预定义的字符类

[`Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html) API 包含许多有用的 *predefined character classes (预定义字符类)*，它们为常用的正则表达式提供了方便的缩写：



| 构造 | 描述                                     |
| ---- | ---------------------------------------- |
| `.`  | 任何字符(可以匹配，也可以不匹配行终止符) |
| `\d` | 数字：`[0-9]`                            |
| `\D` | 非数字：`[^0-9]`                         |
| `\s` | 空白字符：`[ \t\n\x0B\f\r]`              |
| `\S` | 非空白字符：`[^\s]`                      |
| `\w` | 字符：`[a-zA-Z_0-9]`                     |
| `\W` | 非单词字符：`[^\w]`                      |

在上表中，左侧列中的每个构造都是右侧列中字符类的简写。例如，`\d` 表示数字范围(0-9)，`\w` 表示单词字符(任何小写字母，任何大写字母，下划线字符，或任何数字)。尽可能使用预定义的类。它们使你的代码更易于阅读，并消除格式错误的字符类引入的错误。

以反斜杠开头的构造称为 *escaped constructs (转义构造)*。我们在 [String Literals](https://pingfangx.github.io/java-tutorials/essential/regex/literals.html) 部分中预览了转义结构，其中我们提到使用反斜杠和 `\Q` 和 `\E` 作为引用。如果在字符串字面量中使用转义构造，则必须在反斜杠前面加上另一个反斜杠，以便编译字符串。例如：

```java
private final String REGEX = "\\d"; // a single digit
```

在这个例子中，`\d` 是正则表达式；编译代码需要额外的反斜杠。但是，测试工具直接从 `Console` 读取表达式，因此不需要额外的反斜杠。

以下示例演示了预定义字符类的使用。

```
Enter your regex: .
Enter input string to search: @
I found the text "@" starting at index 0 and ending at index 1.

Enter your regex: . 
Enter input string to search: 1
I found the text "1" starting at index 0 and ending at index 1.

Enter your regex: .
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.

Enter your regex: \d
Enter input string to search: 1
I found the text "1" starting at index 0 and ending at index 1.

Enter your regex: \d
Enter input string to search: a
No match found.

Enter your regex: \D
Enter input string to search: 1
No match found.

Enter your regex: \D
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.

Enter your regex: \s
Enter input string to search:  
I found the text " " starting at index 0 and ending at index 1.

Enter your regex: \s
Enter input string to search: a
No match found.

Enter your regex: \S
Enter input string to search:  
No match found.

Enter your regex: \S
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.

Enter your regex: \w
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.

Enter your regex: \w
Enter input string to search: !
No match found.

Enter your regex: \W
Enter input string to search: a
No match found.

Enter your regex: \W
Enter input string to search: !
I found the text "!" starting at index 0 and ending at index 1.
```

在前三个示例中，正则表达式只是 `.`(“点”元字符)，表示“任何字符”。因此，在所有三种情况下(随机选择的 `@` 字符，数字和字母)匹配成功。其余示例均使用 [Predefined Character Classes table](https://pingfangx.github.io/java-tutorials/essential/regex/pre_char_classes.html#CHART) 中的单个正则表达式构造。你可以参考此表来确定每个匹配背后的逻辑：

- `\d` 匹配所有数字
- `\s` 匹配空白
- `\w` 匹配单词字符

或者，大写字母意思相反：

- `\D` 匹配非数字
- `\S` 匹配非空白
- `\W` 匹配非单词字符



# 量词

*Quantifiers (量词)* 允许你指定要匹配的匹配项次数。为方便起见，下面介绍了描述贪婪，懒惰和占有量词的 Pattern API 规范的三个部分。乍一看，似乎量词 `X?`，`X??` 和 `X?+` 完全相同，因为它们都承诺匹配“`X`，一次或根本不匹配”。有一些细微的实现差异，将在本节末尾附近解释。

| 贪婪     | 懒惰      | 占有      | 含义                              |
| -------- | --------- | --------- | --------------------------------- |
| `X?`     | `X??`     | `X?+`     | `X`，一次或零次                   |
| `X*`     | `X*?`     | `X*+`     | `X`，零次或多次                   |
| `X+`     | `X+?`     | `X++`     | `X`，一次或多次                   |
| `X{n}`   | `X{n}?`   | `X{n}+`   | `X`，恰好 *`n`* times             |
| `X{n,}`  | `X{n,}?`  | `X{n,}+`  | `X`，至少 *`n`* 次                |
| `X{n,m}` | `X{n,m}?` | `X{n,m}+` | `X`，至少 *`n`* 但不超过 *`m`* 次 |

让我们通过创建三个不同的正则表达式来开始我们对贪婪量词的看法：字母“a”后跟 `?`，`*` 或 `+`。让我们看看当这些表达式针对空输入字符串 `""` 进行测试时会发生什么：

```
Enter your regex: a?
Enter input string to search: 
I found the text "" starting at index 0 and ending at index 0.

Enter your regex: a*
Enter input string to search: 
I found the text "" starting at index 0 and ending at index 0.

Enter your regex: a+
Enter input string to search: 
No match found.
```

## 零长度匹配

在上面的示例中，匹配在前两种情况下成功，因为表达式 `a?` 和 `a*` 都允许字母 `a` 的零次匹配项。你还会注意到开始和结束索引都是零，这与我们到目前为止看到的任何示例都不同。空输入字符串 `""` 没有长度，因此测试只是匹配索引 0 处的任何内容。这种匹配被称为 *zero-length matches (零长度匹配)*。在以下几种情况下可能发生零长度匹配：在空输入字符串中，在输入字符串的开头，在输入字符串的最后一个字符之后，或在输入字符串的任意两个字符之间。零长度匹配很容易识别，因为它们始终在相同的索引位置开始和结束。

让我们通过几个例子来探索零长度匹配。将输入字符串更改为单个字母“a”，你会注意到一些有趣的内容：

```
Enter your regex: a?
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.
I found the text "" starting at index 1 and ending at index 1.

Enter your regex: a*
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.
I found the text "" starting at index 1 and ending at index 1.

Enter your regex: a+
Enter input string to search: a
I found the text "a" starting at index 0 and ending at index 1.
```

所有三个量词都找到了字母“a”，但前两个也在索引 1 处找到了零长度匹配；也就是说，在输入字符串的最后一个字符之后。请记住，匹配器将字符“a”视为位于索引 0 和索引 1 之间的单元格中，并且我们的测试工具循环直到它无法再找到匹配项。根据所使用的量词，最后一个字符后索引处“无”的存在可能触发也可能不触发匹配。

现在连续五次将输入字符串更改为字母“a”，你将获得以下内容：

```
Enter your regex: a?
Enter input string to search: aaaaa
I found the text "a" starting at index 0 and ending at index 1.
I found the text "a" starting at index 1 and ending at index 2.
I found the text "a" starting at index 2 and ending at index 3.
I found the text "a" starting at index 3 and ending at index 4.
I found the text "a" starting at index 4 and ending at index 5.
I found the text "" starting at index 5 and ending at index 5.

Enter your regex: a*
Enter input string to search: aaaaa
I found the text "aaaaa" starting at index 0 and ending at index 5.
I found the text "" starting at index 5 and ending at index 5.

Enter your regex: a+
Enter input string to search: aaaaa
I found the text "aaaaa" starting at index 0 and ending at index 5.
```

表达式 `a?` 找到每个字符的单独匹配，因为它匹配“a”出现零或一次。表达式 `a*` 找到两个单独的匹配：第一个匹配中的所有字母“a”，然后是索引 5 处的最后一个字符后的零长度匹配。最后，`a+` 匹配所有字母“a”组成的匹配项，忽略最后一个索引处“无”的存在。

此时，如果前两个量词遇到“a”以外的字母，你可能想知道结果会是什么。例如，如果遇到字母“b”会发生什么，如“ababaaaab”？

我们来看看：

```
Enter your regex: a?
Enter input string to search: ababaaaab
I found the text "a" starting at index 0 and ending at index 1.
I found the text "" starting at index 1 and ending at index 1.
I found the text "a" starting at index 2 and ending at index 3.
I found the text "" starting at index 3 and ending at index 3.
I found the text "a" starting at index 4 and ending at index 5.
I found the text "a" starting at index 5 and ending at index 6.
I found the text "a" starting at index 6 and ending at index 7.
I found the text "a" starting at index 7 and ending at index 8.
I found the text "" starting at index 8 and ending at index 8.
I found the text "" starting at index 9 and ending at index 9.

Enter your regex: a*
Enter input string to search: ababaaaab
I found the text "a" starting at index 0 and ending at index 1.
I found the text "" starting at index 1 and ending at index 1.
I found the text "a" starting at index 2 and ending at index 3.
I found the text "" starting at index 3 and ending at index 3.
I found the text "aaaa" starting at index 4 and ending at index 8.
I found the text "" starting at index 8 and ending at index 8.
I found the text "" starting at index 9 and ending at index 9.

Enter your regex: a+
Enter input string to search: ababaaaab
I found the text "a" starting at index 0 and ending at index 1.
I found the text "a" starting at index 2 and ending at index 3.
I found the text "aaaa" starting at index 4 and ending at index 8.
```

即使字母“b”出现在单元格 1,3 和 8 中，输出也会在这些位置报告零长度匹配。正则表达式 `a?` 不是专门寻找字母“b”;它只是在寻找字母“a”的存在(或缺少)。如果量词允许匹配“a”零次，则输入字符串中不是“a”的任何内容都将显示为零长度匹配。剩余的 a 根据前面示例中讨论的规则匹配。



要精确匹配模式 *n* 次，只需在一组大括号内指定数字：

```
Enter your regex: a{3}
Enter input string to search: aa
No match found.

Enter your regex: a{3}
Enter input string to search: aaa
I found the text "aaa" starting at index 0 and ending at index 3.

Enter your regex: a{3}
Enter input string to search: aaaa
I found the text "aaa" starting at index 0 and ending at index 3.
```

这里，正则表达式 `a{3}` 搜索连续三次出现的字母“a”。第一个测试失败，因为输入字符串没有足够的匹配。第二个测试在输入字符串中包含 3 个 a，它会触发匹配。第三个测试也触发匹配，因为在输入字符串的开头恰好有 3 个 a。接下来的任何事情都与第一个匹配无关。如果该模式在该点之后再次出现，则会触发后续匹配：

```
Enter your regex: a{3}
Enter input string to search: aaaaaaaaa
I found the text "aaa" starting at index 0 and ending at index 3.
I found the text "aaa" starting at index 3 and ending at index 6.
I found the text "aaa" starting at index 6 and ending at index 9.
```

要求模式至少出现 *n* 次，请在数字后面添加逗号：

```
Enter your regex: a{3,}
Enter input string to search: aaaaaaaaa
I found the text "aaaaaaaaa" starting at index 0 and ending at index 9.
```

使用相同的输入字符串，此测试只找到一个匹配项，因为连续的 9 个 a 满足“至少” 3 个 a 的需要。

最后，要指定出现次数的上限，请在大括号内添加第二个数字：

```
Enter your regex: a{3,6} // find at least 3 (but no more than 6) a's in a row
Enter input string to search: aaaaaaaaa
I found the text "aaaaaa" starting at index 0 and ending at index 6.
I found the text "aaa" starting at index 6 and ending at index 9.
```

在这里，第一个匹配被强制停止在 6 个字符的上限。第二个匹配包括剩余的内容，恰好是 3 个 a  满足此匹配允许的最小字符数。如果输入字符串短一个字符，则不会有第二个匹配，因为只剩下两个 a。

## 使用量词捕获组和字符类

到目前为止，我们只对包含一个字符的输入字符串测试了量词。实际上，量词只能一次附加到一个字符，因此正则表达式“abc+”表示“a，后跟 b，后跟 c 一次或多次”。它不意味着“abc”一次或多次。但是，量词也可以附加到 [Character Classes](https://pingfangx.github.io/java-tutorials/essential/regex/char_classes.html) 和 [Capturing Groups](https://pingfangx.github.io/java-tutorials/essential/regex/groups.html)，例如 `[abc]+`(a 或 b 或 c，一次或多次) 或 `(abc)+`(组“abc”，一次或多次)。

让我们通过指定每行三个的 `(dog)` 组来说明。

```
Enter your regex: (dog){3}
Enter input string to search: dogdogdogdogdogdog
I found the text "dogdogdog" starting at index 0 and ending at index 9.
I found the text "dogdogdog" starting at index 9 and ending at index 18.

Enter your regex: dog{3}
Enter input string to search: dogdogdogdogdogdog
No match found.
```

这里第一个例子找到三个匹配，因为量词适用于整个捕获组。但是，移除括号会匹配失败，因为量词 `{3}` 现在仅适用于字母“g”。

同样，我们可以将量词应用于整个字符类：

```
Enter your regex: [abc]{3}
Enter input string to search: abccabaaaccbbbc
I found the text "abc" starting at index 0 and ending at index 3.
I found the text "cab" starting at index 3 and ending at index 6.
I found the text "aaa" starting at index 6 and ending at index 9.
I found the text "ccb" starting at index 9 and ending at index 12.
I found the text "bbc" starting at index 12 and ending at index 15.

Enter your regex: abc{3}
Enter input string to search: abccabaaaccbbbc
No match found.
```

这里量词 `{3}` 适用于第一个示例中的整个字符类，但仅适用于第二个中的字母“c”。

## 贪婪，懒惰和占有量词之间的差异

贪婪，懒惰和占有量词之间存在细微差别。

贪婪量词被认为是“贪婪的”，因为它们在尝试第一次匹配之前，强制匹配器读入（或称为 *eat*）整个输入字符串。如果第一次匹配尝试(整个输入字符串)失败，匹配器将输入字符串后退一个字符并再次尝试，重复该过程直到找到匹配项或者没有剩余字符可以后退。根据表达式中使用的量词，它将尝试匹配的最后内容是 1 或 0 个字符。

然而，懒惰量词采用相反的方法：它们从输入字符串的开头开始，然后懒惰地一次吃一个字符来寻找匹配。他们尝试的最后内容是整个输入字符串。

最后，占有量词总是占用整个输入字符串，尝试一次(并且仅一次)匹配。与贪婪量词不同，占有量词永远不会后退，即使这样做也会使整体匹配成功。

为了说明，请考虑输入字符串 `xfooxxxxxxfoo`。

```
Enter your regex: .*foo  // greedy quantifier
Enter input string to search: xfooxxxxxxfoo
I found the text "xfooxxxxxxfoo" starting at index 0 and ending at index 13.

Enter your regex: .*?foo  // reluctant quantifier
Enter input string to search: xfooxxxxxxfoo
I found the text "xfoo" starting at index 0 and ending at index 4.
I found the text "xxxxxxfoo" starting at index 4 and ending at index 13.

Enter your regex: .*+foo // possessive quantifier
Enter input string to search: xfooxxxxxxfoo
No match found.
```

第一个例子使用贪婪量词 `.*` 来找到“任何东西”，零次或多次，然后是字母 `"f" "o" "o"`。因为量词是贪婪，所以表达式的 `.*` 部分首先会占用整个输入字符串。此时，整体表达式不能成功，因为已经消耗了最后三个字母(`"f" "o" "o"`)。因此，匹配器一次缓慢地后退一个字母，直到最右边的“foo”重新出现，此时匹配成功并且搜索结束。

然而，第二个例子是懒惰的，所以它首先消耗“无”。因为“foo”没有出现在字符串的开头，所以它被强制吞下第一个字母(“x”)，这会触发 0 和 4 的第一个匹配。我们的测试工具将继续此过程，直到输入字符串耗尽为止。它在 4 和 13 找到另一场匹配。

第三个例子找不到匹配，因为量词是占有。在这种情况下，整个输入字符串由 `.*+` 消耗，不留下任何内容以满足表达式末尾的“foo”。使用占有量词来表示你想要抓住所有东西而不会后退的情况；在没有立即找到匹配的情况下，它将胜过等效的贪婪量词。



# 捕获组

在 [previous section](https://pingfangx.github.io/java-tutorials/essential/regex/quant.html) 中，我们看到了量词一次如何附加到一个字符，字符类或捕获组。但到目前为止，我们还没有详细讨论过捕获组的概念。

*Capturing groups (捕获组)* 是将多个字符视为一个单元的一种方法。它们是通过将要分组的字符放在一组括号中来创建的。例如，正则表达式 `(dog)` 创建一个包含字母 `"d" "o"` 和 `"g"` 的组。输入字符串中与捕获组匹配的部分将保存在内存中，以便以后通过后向引用进行调用(如下面的 [Backreferences](https://pingfangx.github.io/java-tutorials/essential/regex/groups.html#backref) 部分所述)。

## 组编号

如 [`Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html) API 中所述，捕获组通过从左到右计算它们的左括号来编号。例如，在表达式 `((A)(B(C)))` 中，有四个这样的组：

1. `((A)(B(C)))`
2. `(A)`
3. `(B(C))`
4. `(C)`

要查找表达式中存在的组数，请在匹配器对象上调用 `groupCount` 方法。`groupCount` 方法返回 `int`，显示匹配器模式中存在的捕获组数。在此示例中，`groupCount` 将返回数字 `4`，表明该模式包含 4 个捕获组。

还有一个特殊组，即组 0，它始终表示整个表达式。该组未包含在 `groupCount` 报告的总数中。以 `(?` 开头的组是纯粹的，*non-capturing groups (非捕获组)*，不捕获文本，不计入组总数。(稍后将在 [Methods of the Pattern Class](https://pingfangx.github.io/java-tutorials/essential/regex/pattern.html) 部分中看到非捕获组的示例。)

了解组的编号非常重要，因为某些 `Matcher` 方法接受 `int` 指定特定组编号作为参数：

- [`public int start(int group)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#start-int-):返回上一个匹配操作期间给定组捕获的子序列的起始索引。
- [`public int end (int group)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#end-int-):返回上一个匹配操作期间给定组捕获的子序列的最后一个字符的索引加 1。
- [`public String group (int group)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#group-int-):返回上一个匹配操作期间给定组捕获的输入子序列。



## 反向引用（Backreference）

> 用于匹配**重复结构**。
>
> ```
> (\d\d)\1
> ```



与捕获组匹配的输入字符串部分保存在内存中，以便稍后通过 *backreference (反向引用)* 进行调用。在正则表达式中将反向引用指定为反斜杠(`\`)，后跟一个数字（指示要调用的组的编号）。例如，表达式 `(\d\d)` 定义了一个匹配个行中两个数字的捕获组，可以通过反向引用 `\1` 在表达式中稍后调用。

要匹配任何 2 位数字，后跟完全相同的两位数字，你可以使用 `(\d\d)\1` 作为正则表达式：

```
Enter your regex: (\d\d)\1
Enter input string to search: 1212
I found the text "1212" starting at index 0 and ending at index 4.
```

如果更改最后两位数，则匹配将失败：

```
Enter your regex: (\d\d)\1
Enter input string to search: 1234
No match found.
```

对于嵌套捕获组，反向引用的工作方式完全相同：指定反斜杠后跟要调用的组的编号。



## 非捕获分组 vs 捕获分组

| 类型     | 语法    | 是否捕获 | 是否编号 | 性能 |
| -------- | ------- | -------- | -------- | ---- |
| 捕获组   | (abc)   | 是       | 是       | 较低 |
| 非捕获组 | (?:abc) | 否       | 否       | 更优 |

**建议：除非需要反向引用，否则优先使用非捕获组**

---

### 命名捕获组（Java 7+）

```java
Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
Matcher m = p.matcher("2025-12-16");
if (m.find()) {
    m.group("year");  // 2025
}
```

👉 可读性与可维护性极高，**工程强烈推荐**

---

## 替换实战（replaceAll + 分组）

### 格式重排

```java
String s = "2025-12-16";
s.replaceAll("(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1");
```

### 删除重复单词

```regex
\b(\w+)\s+\1\b
```

替换为空即可去重

```java
String s = "this is is a test test.";
String r = s.replaceAll("\\b(\\w+)\\s+\\1\\b", "$1");
// 结果："this is a test."
```



# 零宽断言（Lookaround）

零宽断言用于**判断位置是否满足条件，但不消耗字符**。

### 正向先行断言（Lookahead）

```regex
\d+(?=px)
```

匹配后面是 px 的数字：`100px → 100`

### 负向先行断言

```regex
\d+(?!px)
```

匹配后面不是 px 的数字

### 正向后行断言（Lookbehind）

```regex
(?<=\$)\d+
```

匹配前面是 `$` 的数字：`$100 → 100`

### 负向后行断言

```regex
(?<!\$)\d+
```

匹配前面不是 `$` 的数字

> 📌 Java 要求 Lookbehind **长度固定**（不能用 `+ *`）



# 边界匹配

到目前为止，我们只关注于在特定输入字符串中是否 *在某个位置* 找到匹配。我们从不关心匹配发生在字符串中的 *何处*。

通过使用 *boundary matchers (边界匹配)* 指定此类信息，可以使模式匹配更精确。例如，你可能对查找特定单词感兴趣，但前提是它出现在行的开头或结尾。或者你可能想知道匹配是在单词边界上进行，还是在上一个匹配的末尾进行。

下表列出并解释了所有边界匹配器。

| 边界构造 | 描述                                   |
| -------- | -------------------------------------- |
| `^`      | 行首                                   |
| `$`      | 行尾                                   |
| `\b`     | 单词边界                               |
| `\B`     | 非单词边界                             |
| `\A`     | 输入的开始                             |
| `\G`     | 上一个匹配的结束                       |
| `\Z`     | 输入结束但是对于最终终止符(如果有的话) |
| `\z`     | 输入结束                               |

以下示例演示边界匹配器 `^` 和 `$` 的使用。如上所述，`^` 匹配一行的开头，`$` 匹配一行的结尾。

```
Enter your regex: ^dog$
Enter input string to search: dog
I found the text "dog" starting at index 0 and ending at index 3.

Enter your regex: ^dog$
Enter input string to search:       dog
No match found.

Enter your regex: \s*dog$
Enter input string to search:             dog
I found the text "            dog" starting at index 0 and ending at index 15.

Enter your regex: ^dog\w*
Enter input string to search: dogblahblah
I found the text "dogblahblah" starting at index 0 and ending at index 11.
```

第一个示例是成功的，因为模式占用整个输入字符串。第二个示例失败，因为输入字符串在开头包含额外的空格。第三个示例指定一个表达式，该表达式允许无限制的空格，后面是行尾的“dog”。第四个例子要求“dog”出现在一行的开头，后跟无限数量的单词字符。

要检查模式是否在单词边界上开始和结束(与较长字符串中的子字符串相对)，只需在任一侧使用 `\b`;例如，`\bdog\b`

```
Enter your regex: \bdog\b
Enter input string to search: The dog plays in the yard.
I found the text "dog" starting at index 4 and ending at index 7.

Enter your regex: \bdog\b
Enter input string to search: The doggie plays in the yard.
No match found.
```

要匹配非字边界上的表达式，请改为使用 `\B`：

```
Enter your regex: \bdog\B
Enter input string to search: The dog plays in the yard.
No match found.

Enter your regex: \bdog\B
Enter input string to search: The doggie plays in the yard.
I found the text "dog" starting at index 4 and ending at index 7.
```

要求匹配仅在上一个匹配结束时发生，请使用 `\G`：

```
Enter your regex: dog 
Enter input string to search: dog dog
I found the text "dog" starting at index 0 and ending at index 3.
I found the text "dog" starting at index 4 and ending at index 7.

Enter your regex: \Gdog 
Enter input string to search: dog dog
I found the text "dog" starting at index 0 and ending at index 3.
```

这里第二个例子只找到一个匹配，因为第二次匹配项的“dog”没有在上一个匹配结束时开始。



# Pattern 类的方法

到目前为止，我们只使用测试工具以最基本的形式创建 `Pattern` 对象。本节探讨高级技术，例如使用标志创建模式和使用嵌入式标志表达式。它还探讨了我们尚未讨论的一些其他有用的方法。

## 使用 flag 创建模式

`Pattern` 类定义了一个备用 `compile` 方法，该方法接受一组影响模式匹配方式的标志。flags 参数是一个位掩码，可能包含以下任何公共静态字段：

- [`Pattern.CANON_EQ`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#CANON_EQ)启用规范等效。当指定此标志时，当且仅当两个字符的完整规范化分解匹配时，才认为这两个字符匹配。例如，当指定此标志时，表达式 `"a\u030A"` 将匹配字符串 `"\u00E5"`。默认情况下，匹配不会将规范等效考虑在内。指定此标志可能会导致性能下降。
- [`Pattern.CASE_INSENSITIVE`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#CASE_INSENSITIVE)启用不区分大小写的匹配。默认情况下，不区分大小写的匹配假定只匹配 US-ASCII 字符集中的字符。通过将 UNICODE_CASE 标志与此标志一起指定，可以启用 Unicode 感知的不区分大小写的匹配。也可以通过嵌入式标志表达式 `(?i)` 启用不区分大小写的匹配。指定此标志可能会略微降低性能。
- [`Pattern.COMMENTS`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#COMMENTS)允许模式中的空格和注释。在此模式下，将忽略空格，并忽略以 `#` 开头的嵌入式注释，直到行结束。也可以通过嵌入式标志表达式 `(?x)` 启用注释模式。
- [`Pattern.DOTALL`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#DOTALL)启用 dotall 模式。在 dotall 模式下，表达式 `.` 匹配任何字符，包括行终止符。默认情况下，此表达式与行终止符不匹配。也可以通过嵌入式标志表达式 `(?s)` 启用 Dotall 模式。(s 是“单行”模式的助记符，在 Perl 中就是这样称呼的。)
- [`Pattern.LITERAL`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#LITERAL)启用模式的文字解析。指定此标志后，指定模式的输入字符串将被视为文字字符序列。输入序列中的元字符或转义序列将没有特殊含义。当与此标志一起使用时，标志 `CASE_INSENSITIVE` 和 `UNICODE_CASE` 会保持对匹配的影响。其他标志变得多余。没有用于启用文字解析的嵌入标志字符。
- [`Pattern.MULTILINE`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#MULTILINE)启用多行模式。在多行模式下，表达式 `^` 和 `$` 分别在行终止符之后或之前匹配，或者在输入序列的末尾之前匹配。默认情况下，这些表达式仅在整个输入序列的开头和结尾处匹配。也可以通过嵌入式标志表达式 `(?m)` 启用多行模式。
- [`Pattern.UNICODE_CASE`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#UNICODE_CASE)启用支持 Unicode 的大小写折叠。指定此标志后，当由 `CASE_INSENSITIVE` 标志启用时，不区分大小写的匹配将以与 Unicode 标准一致的方式完成。默认情况下，不区分大小写的匹配假定只匹配 US-ASCII 字符集中的字符。也可以通过嵌入式标志表达式 `(?u)` 启用支持 Unicode 的大小写折叠。指定此标志可能会导致性能下降。
- [`Pattern.UNIX_LINES`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#UNIX_LINES)启用 UNIX 行模式。在该模式中，只有 `'\n'` 行终止符在 `.` `^` 和 `$` 的行为中被识别。也可以通过嵌入式标志表达式 `(?d)` 启用 UNIX 行模式。

在以下步骤中，我们将修改测试工具 [`RegexTestHarness.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/RegexTestHarness.java) 以创建具有不区分大小写匹配的模式。

首先，修改代码以调用 `compile` 的备用版本：

```java
Pattern pattern = 
Pattern.compile(console.readLine("%nEnter your regex: "),
Pattern.CASE_INSENSITIVE);
```

然后编译并运行测试工具以获得以下结果：

```
Enter your regex: dog
Enter input string to search: DoGDOg
I found the text "DoG" starting at index 0 and ending at index 3.
I found the text "DOg" starting at index 3 and ending at index 6.
```

如你所见，无论大小写如何，字符串文字“dog”都匹配这两种情况。要编译带有多个标志的模式，请使用按位或运算符 "`|`" 分隔要包含的标志。为清楚起见，以下代码示例对正则表达式进行硬编码，而不是从 `Console` 中读取它：

```java
pattern = Pattern.compile("[az]$", Pattern.MULTILINE | Pattern.UNIX_LINES);
```

你也可以指定 `int` 变量：

```java
final int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
Pattern pattern = Pattern.compile("aa", flags);
```



## 嵌入式标志表达式

也可以使用 *embedded flag expressions (嵌入式标志表达式)* 启用各种标志。嵌入式标志表达式是 `compile` 的双参数版本的替代，并且在正则表达式本身中指定。以下示例使用原始测试工具 [`RegexTestHarness.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/RegexTestHarness.java) 和嵌入式标志表达式 `(?i)` 来启用不区分大小写的匹配。

```
Enter your regex: (?i)foo
Enter input string to search: FOOfooFoOfoO
I found the text "FOO" starting at index 0 and ending at index 3.
I found the text "foo" starting at index 3 and ending at index 6.
I found the text "FoO" starting at index 6 and ending at index 9.
I found the text "foO" starting at index 9 and ending at index 12.
```

忽略大小写，所有匹配再次成功。

与 `Pattern` 的可公开访问的字段对应的嵌入式标志表达式如下表所示：

| 常量                       | 等效嵌入式标志表达式 |
| -------------------------- | -------------------- |
| `Pattern.CANON_EQ`         | 无                   |
| `Pattern.CASE_INSENSITIVE` | `(?i)`               |
| `Pattern.COMMENTS`         | `(?x)`               |
| `Pattern.MULTILINE`        | `(?m)`               |
| `Pattern.DOTALL`           | `(?s)`               |
| `Pattern.LITERAL`          | 无                   |
| `Pattern.UNICODE_CASE`     | `(?u)`               |
| `Pattern.UNIX_LINES`       | `(?d)`               |

## 使用 `matches(String,CharSequence)` 方法

`Pattern` 类定义了一个方便的 [`matches`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#matches-java.lang.String-java.lang.CharSequence-) 方法，该方法允许你快速检查给定输入字符串中是否存在模式。与所有公共静态方法一样，你应该通过其类名调用 `matches`，例如 `Pattern.matches("\\d","1");`。在此示例中，该方法返回 `true`，因为数字“1”与正则表达式 `\d` 匹配。

## 使用 `split(String)` 方法

[`split`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#split-java.lang.CharSequence-) 方法是一个很好的工具，用于收集位于匹配模式两侧的文本。如下面 [`SplitDemo.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/SplitDemo.java) 所示，`split` 方法可以提取单词“`one two three four five`”从字符串“`one:two:three:four:five`”：

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class SplitDemo {

    private static final String REGEX = ":";
    private static final String INPUT =
        "one:two:three:four:five";
    
    public static void main(String[] args) {
        Pattern p = Pattern.compile(REGEX);
        String[] items = p.split(INPUT);
        for(String s : items) {
            System.out.println(s);
        }
    }
}
OUTPUT:

one
two
three
four
five
```

为简单起见，我们匹配了字符串文字冒号(`:`)而不是复杂的正则表达式。由于我们仍在使用 `Pattern` 和 `Matcher` 对象，因此你可以使用 split 来获取位于任何正则表达式两侧的文本。这是相同的例子，[`SplitDemo2.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/SplitDemo2.java)，修改为在数字上拆分：

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class SplitDemo2 {

    private static final String REGEX = "\\d";
    private static final String INPUT =
        "one9two4three7four1five";

    public static void main(String[] args) {
        Pattern p = Pattern.compile(REGEX);
        String[] items = p.split(INPUT);
        for(String s : items) {
            System.out.println(s);
        }
    }
}
OUTPUT:

one
two
three
four
five
```

## 其他实用方法

你可能会发现以下方法也有一些用处：

- [`public static String quote(String s)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#quote-java.lang.String-)返回指定 `String` 的文字模式 `String`。此方法生成 `String`，可用于创建与 `String s` 匹配的 `Pattern`，就像它是文字模式一样。**输入序列中的元字符或转义序列将没有特殊含义**。
- [`public String toString()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html#toString--)返回此模式的 `String` 表示形式。这是编译此模式的正则表达式。

## `java.lang.String` 中的模式方法等价物

`java.lang.String` 中也存在正则表达式支持，它通过几种模仿 `java.util.regex.Pattern` 行为的方法。为方便起见，下面介绍了 API 的主要片段。

- [`public boolean matches(String regex)`](https://docs.oracle.com/javase/8/docs/api/java/lang/String.html#matches-java.lang.String-):判断此字符串是否与给定的正则表达式匹配。调用 `str.matches(regex)` 形式的此方法会产生与表达式 `Pattern.matches(regex, str)` 完全相同的结果。
- `public String[] split(String regex, int limit)`:围绕给定正则表达式的匹配拆分此字符串。调用 `str.split(regex, n)` 形式的此方法会产生与表达式 `Pattern.compile(regex).split(str, n)` 相同的结果。
- `public String[] split(String regex)`:围绕给定正则表达式的匹配拆分此字符串。此方法与调用具有给定表达式且 limit 参数为零的双参数 split 方法的方法相同。结果数组中不包括尾随空字符串。

还有一个替换方法，用另一个 `CharSequence` 替换一个：

- [`public String replace(CharSequence target,CharSequence replacement)`](https://docs.oracle.com/javase/8/docs/api/java/lang/String.html#replace-java.lang.CharSequence-java.lang.CharSequence-):将此字符串中与 target 匹配的每个子字符串替换为指定的 replacement 序列。替换从字符串的开头到结尾，例如，在字符串“aaa”中将“aa”替换为“b”将导致“ba”而不是“ab”。

# Matcher 类的方法

本节介绍 `Matcher` 类的一些其他有用方法。为方便起见，下面列出的方法根据功能进行分组。

## 索引方法

*Index methods (索引方法)* 提供有用的索引值，可以精确显示在输入字符串中找到匹配的位置：

- [`public int start()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#start--):返回上一个匹配的起始索引。
- [`public int start(int group)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#start-int-):返回上一个匹配操作期间给定组捕获的子序列的起始索引。
- [`public int end()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#end--):返回最后一个字符匹配后的偏移量。
- [`public int end(int group)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#end-int-):返回在上一个匹配操作期间由给定组捕获的子序列的最后一个字符之后的偏移量。

## 查找方法

*Study methods (研究方法)* 检查输入字符串并返回指示是否找到模式的布尔值。

- [`public boolean lookingAt()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#lookingAt--):尝试将从区域开头开始的输入序列与模式匹配。
- [`public boolean find()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#find--):尝试查找与模式匹配的输入序列的下一个子序列。
- [`public boolean find(int start)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#find-int-):重置此匹配器，然后尝试从指定的索引处开始查找与模式匹配的输入序列的下一个子序列。
- [`public boolean matches()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#matches--):尝试将整个区域与模式匹配。

| 方法      | 是否全匹配 | 是否移动游标 | 常见误区          |
| --------- | ---------- | ------------ | ----------------- |
| matches   | 是         | 否           | 误以为是 contains |
| find      | 否         | 是           | 忘记 reset        |
| lookingAt | 否         | 否           | 和 matches 混淆   |



## 替换方法

*Replacement methods (替换方法)* 是替换输入字符串中的文本的有用方法。

- [`public Matcher appendReplacement(StringBuffer sb, String replacement)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#appendReplacement-java.lang.StringBuffer-java.lang.String-):实现非末尾追加和替换步骤。
- [`public StringBuffer appendTail(StringBuffer sb)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#appendTail-java.lang.StringBuffer-):实现末尾追加和替换步骤。
- [`public String replaceAll(String replacement)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#replaceAll-java.lang.String-):用给定替换字符串，替换输入序列中与模式匹配的的每个子序列。
- [`public String replaceFirst(String replacement)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#replaceFirst-java.lang.String-):用给定的替换字符串，替换与模式匹配的输入序列的第一个子序列。
- [`public static String quoteReplacement(String s)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#quoteReplacement-java.lang.String-):返回指定 `String` 的文字替换 `String`。此方法生成 `String`，它将作为 `Matcher` 类的 `appendReplacement` 方法中的文字替换 `s`。生成的 `String` 将匹配 `s` 中作为文字序列处理的字符序列。斜线(`'\'`)和美元符号(`'$'`)将不具有特殊意义。

## 使用 `start` 和 `end` 方法

这是一个例子，[`MatcherDemo.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/MatcherDemo.java)，它计算输入字符串中单词“dog”出现的次数。

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MatcherDemo {

    private static final String REGEX =
        "\\bdog\\b";
    private static final String INPUT =
        "dog dog dog doggie dogg";

    public static void main(String[] args) {
       Pattern p = Pattern.compile(REGEX);
       //  get a matcher object
       Matcher m = p.matcher(INPUT);
       int count = 0;
       while(m.find()) {
           count++;
           System.out.println("Match number "
                              + count);
           System.out.println("start(): "
                              + m.start());
           System.out.println("end(): "
                              + m.end());
      }
   }
}
OUTPUT:

Match number 1
start(): 0
end(): 3
Match number 2
start(): 4
end(): 7
Match number 3
start(): 8
end(): 11
```

你可以看到此示例使用单词边界来确保字母 `"d" "o" "g"` 不仅仅是较长单词中的子字符串。它还提供了有关输入字符串中匹配发生位置的一些有用信息。`start` 方法返回上一个匹配操作期间给定组捕获的子序列的起始索引，`end` 返回匹配的最后一个字符的索引加 1。

## 使用 `matches` 和 `lookingAt` 方法

`matches` 和 `lookingAt` 方法都尝试将输入序列与模式匹配。但是，差异在于 `matches` 需要匹配整个输入序列，而 `lookingAt` 则不需要。两种方法总是从输入字符串的开头开始。这是完整的代码，[`MatchesLooking.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/MatchesLooking.java)：

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MatchesLooking {

    private static final String REGEX = "foo";
    private static final String INPUT =
        "fooooooooooooooooo";
    private static Pattern pattern;
    private static Matcher matcher;

    public static void main(String[] args) {
   
        // Initialize
        pattern = Pattern.compile(REGEX);
        matcher = pattern.matcher(INPUT);

        System.out.println("Current REGEX is: "
                           + REGEX);
        System.out.println("Current INPUT is: "
                           + INPUT);

        System.out.println("lookingAt(): "
            + matcher.lookingAt());
        System.out.println("matches(): "
            + matcher.matches());
    }
}
Current REGEX is: foo
Current INPUT is: fooooooooooooooooo
lookingAt(): true
matches(): false
```

## 使用 `replaceFirst(String)` 和 `replaceAll(String)`

`replaceFirst` 和 `replaceAll` 方法替换与给定正则表达式匹配的文本。如其名称所示，`replaceFirst` 替换第一次匹配项，`replaceAll` 替换所有匹配项。这是 [`ReplaceDemo.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/ReplaceDemo.java) 代码：

```java
import java.util.regex.Pattern; 
import java.util.regex.Matcher;

public class ReplaceDemo {
 
    private static String REGEX = "dog";
    private static String INPUT =
        "The dog says meow. All dogs say meow.";
    private static String REPLACE = "cat";
 
    public static void main(String[] args) {
        Pattern p = Pattern.compile(REGEX);
        // get a matcher object
        Matcher m = p.matcher(INPUT);
        INPUT = m.replaceAll(REPLACE);
        System.out.println(INPUT);
    }
}
OUTPUT: The cat says meow. All cats say meow.
```

在第一个版本中，所有匹配项的 `dog` 都替换为 `cat`。但为何停在这里？你可以替换匹配 *any (任何)* 正则表达式的文本，而不是替换像 `dog` 这样的简单文字。此方法的 API 声明“给定正则表达式 `a*b`，输入 `aabfooaabfooabfoob`，替换字符串 `-`，调用在该表达式的匹配器上的此方法将产生字符串 `-foo-foo-foo-`。“

这是 [`ReplaceDemo2.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/ReplaceDemo2.java) 代码：

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;
 
public class ReplaceDemo2 {
 
    private static String REGEX = "a*b";
    private static String INPUT =
        "aabfooaabfooabfoob";
    private static String REPLACE = "-";
 
    public static void main(String[] args) {
        Pattern p = Pattern.compile(REGEX);
        // get a matcher object
        Matcher m = p.matcher(INPUT);
        INPUT = m.replaceAll(REPLACE);
        System.out.println(INPUT);
    }
}
OUTPUT: -foo-foo-foo-
```

要仅替换模式的第一个匹配项，只需调用 `replaceFirst` 替换 `replaceAll`。它接受相同的参数。

## 使用 `appendReplacement(StringBuffer,String)` 和 `appendTail(StringBuffer)`

`Matcher` 类还提供 `appendReplacement` 和 `appendTail` 方法以进行文本替换。以下示例 [`RegexDemo.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/RegexDemo.java) 使用这两种方法来实现与 `replaceAll` 相同的效果。

```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RegexDemo {
 
    private static String REGEX = "a*b";
    private static String INPUT = "aabfooaabfooabfoob";
    private static String REPLACE = "-";
 
    public static void main(String[] args) {
        Pattern p = Pattern.compile(REGEX);
        Matcher m = p.matcher(INPUT); // get a matcher object
        StringBuffer sb = new StringBuffer();
        while(m.find()){
            m.appendReplacement(sb,REPLACE);
        }
        m.appendTail(sb);
        System.out.println(sb.toString());
    }
}
OUTPUT: -foo-foo-foo- 
```

## `java.lang.String` 中的匹配方法等价物

为方便起见，`String` 类也模仿了几个 `Matcher` 方法：

- [`public String replaceFirst(String regex, String replacement)`](https://docs.oracle.com/javase/8/docs/api/java/lang/String.html#replaceFirst-java.lang.String-java.lang.String-):将此字符串中与给定正则表达式匹配的的第一个子字符串替换为给定的 replacement。调用 `str.replaceFirst(regex, repl)` 形式的此方法会产生与表达式 `Pattern.compile(*regex*).matcher(str).replaceFirst(repl)` 相同的结果。
- [`public String replaceAll(String regex, String replacement)`](https://docs.oracle.com/javase/8/docs/api/java/lang/String.html#replaceAll-java.lang.String-java.lang.String-):将此字符串中与给定正则表达式匹配的的每一个子字符串替换为给定的 replacement。调用 `str.replaceAll(regex, repl)` 形式的此方法会产生与表达式 `Pattern.compile(regex).matcher(str).replaceAll(repl)` 完全相同的结果。

# PatternSyntaxException 类的方法

[`PatternSyntaxException`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html) 是非检查型异常，表示正则表达式模式中的语法错误。`PatternSyntaxException` 类提供以下方法来帮助你确定出错的地方：

- [`public String getDescription()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html#getDescription--):获取错误的描述。
- [`public int getIndex()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html#getIndex--):获取错误索引。
- [`public String getPattern()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html#getPattern--):获取错误的正则表达式模式。
- [`public String getMessage()`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html#getMessage--):返回一个多行字符串，其中包含语法错误及其索引的描述，错误的正则表达式模式以及模式中的错误索引。

以下源代码 [`RegexTestHarness2.java`](https://pingfangx.github.io/java-tutorials/essential/regex/examples/RegexTestHarness2.java) 更新我们的测试工具以检查格式错误的正则表达式：

```java
import java.io.Console;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

public class RegexTestHarness2 {

    public static void main(String[] args){
        Pattern pattern = null;
        Matcher matcher = null;

        Console console = System.console();
        if (console == null) {
            System.err.println("No console.");
            System.exit(1);
        }
        while (true) {
            try{
                pattern = 
                Pattern.compile(console.readLine("%nEnter your regex: "));

                matcher = 
                pattern.matcher(console.readLine("Enter input string to search: "));
            }
            catch(PatternSyntaxException pse){
                console.format("There is a problem" +
                               " with the regular expression!%n");
                console.format("The pattern in question is: %s%n",
                               pse.getPattern());
                console.format("The description is: %s%n",
                               pse.getDescription());
                console.format("The message is: %s%n",
                               pse.getMessage());
                console.format("The index is: %s%n",
                               pse.getIndex());
                System.exit(0);
            }
            boolean found = false;
            while (matcher.find()) {
                console.format("I found the text" +
                    " \"%s\" starting at " +
                    "index %d and ending at index %d.%n",
                    matcher.group(),
                    matcher.start(),
                    matcher.end());
                found = true;
            }
            if(!found){
                console.format("No match found.%n");
            }
        }
    }
}
```

要运行此测试，请输入 `?i)foo` 作为正则表达式。这个错误是程序员忘记嵌入式标志表达式 `(?i)` 中的左括号的常见情况。这样做会产生以下结果：

```java
Enter your regex: ?i)
There is a problem with the regular expression!
The pattern in question is: ?i)
The description is: Dangling meta character '?'
The message is: Dangling meta character '?' near index 0
?i)
^
The index is: 0
```

从这个输出中，我们可以看到语法错误是索引 0 处的悬空元字符(问号)。缺少左括号是罪魁祸首。

# Unicode 支持

从 JDK 7 版本开始，正则表达式模式匹配扩展了支持 Unicode 6.0 的功能。

- [匹配特定的代码点](https://pingfangx.github.io/java-tutorials/essential/regex/unicode.html#matchingSpecific)
- [Unicode 字符属性](https://pingfangx.github.io/java-tutorials/essential/regex/unicode.html#properties)

## 匹配特定的代码点

你可以使用格式 `\uFFFF` 的转义序列匹配特定的 Unicode 代码点，其中 `FFFF` 是要匹配的代码点的十六进制值。例如，`\u6771` 匹配东方的汉字符。

或者，你可以使用 Perl 样式的十六进制表示法 `\x{...}` 指定代码点。例如：

```java
String hexPattern = "\x{" + Integer.toHexString(codePoint) + "}";
```

## Unicode 字符属性

除了其值之外，每个 Unicode 字符都具有某些属性（attributes）或属性（properties）。你可以将属于特定类别的单个字符与表达式 `\p{prop}` 进行匹配。你可以使用表达式 `\P{prop}` 匹配 *不* 属于特定类别的单个字符。

支持的三种属性类型是脚本，块和“常规”类别。

### 脚本

要确定代码点是否属于特定脚本，可以使用 `script` 关键字或 `sc` 简短格式，例如 `\p{script=Hiragana}`。或者，你可以在脚本名称前加上字符串 `Is`，例如 `\p{IsHiragana}`。

`Pattern` 支持的有效脚本名称是 [`UnicodeScript.forName`](https://docs.oracle.com/javase/8/docs/api/java/lang/Character.UnicodeScript.html#forName-java.lang.String-) 接受的名称。

### 块

可以使用 `block` 关键字或 `blk` 短格式指定块，例如 `\p{block=Mongolian}`。或者，你可以在块名称前加上字符串 `In`，例如 `\p{InMongolian}`。

`Pattern` 支持的有效块名称是 [`UnicodeBlock.forName`](https://docs.oracle.com/javase/8/docs/api/java/lang/Character.UnicodeBlock.html#forName-java.lang.String-) 接受的名称。

### 常规类别

可以使用可选前缀 `Is` 指定类别。例如，`IsL` 与 Unicode 字母的类别匹配。也可以使用 `general_category` 关键字或短格式 `gc` 指定类别。例如，可以使用 `general_category=Lu` 或 `gc=Lu` 匹配大写字母。

支持的类别是 [`Character`](https://docs.oracle.com/javase/8/docs/api/java/lang/Character.html) 类指定的版本中 [The Unicode Standard](http://www.unicode.org/unicode/standard/standard.html) 的类别。

# 正则性能与回溯风险（工程必读）

## 灾难性回溯示例（⚠️ 高风险）

```regex
(a+)+b
```

```text
输入：aaaaaaaaaaaaaaaaa
```

❌ 会导致 CPU 飙升（ReDoS）

### 优化方式

- 使用占有量词：`a++b`
- 使用原子组：`(?>a+)b`
- 避免多层嵌套 + 贪婪量词

---

## Pattern / Matcher 工程规范

### 线程安全

- Pattern：✅ 线程安全
- Matcher：❌ 非线程安全

```java
private static final Pattern P = Pattern.compile("\\d+");
```

👉 不要在循环或方法内反复 compile

---

## 常见业务正则示例

### 手机号（中国）

```regex
1[3-9]\d{9}
```

### 邮箱

```regex
[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}
```

### IP 地址

```regex
((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)
```

---

## 正则使用决策指南

- 是否必须整串匹配？
  → `matches()`
- 是否查找子串？
  → `find()`
- 是否多次提取？
  → `while(m.find())`
- 是否仅替换？
  → `replaceAll()`

---

## 常见易错点总结

- `String.matches()` 默认 **全匹配**
- `.` 默认不匹配换行（需 DOTALL）
- `\b` 是**单词边界**不是空格
- `*` `?` 容易产生零长度匹配

---



# 面试高频题补充

## 1️⃣ `find()` 和 `matches()` 区别？

### 核心区别

| 方法        | 是否要求全匹配 | 是否可多次匹配 | 典型用途                             |
| ----------- | -------------- | -------------- | ------------------------------------ |
| `matches()` | ✅ 是           | ❌ 否           | 校验整个字符串，易于contains概念混淆 |
| `find()`    | ❌ 否           | ✅ 是           | 查找子串 / 提取                      |

### 示例

```java
"abc123".matches("\\d+"); // false（不是全数字）
Matcher m = Pattern.compile("\\d+").matcher("abc123def456");
while (m.find()) {
    System.out.println(m.group()); // 123, 456
}
```

👉 **结论**：

- 校验用 `matches()`
- 提取 / 查找用 `find()`

------

## 2️⃣ 为什么不建议用正则解析 HTML？

### 原因分析

1. **HTML 不是正则语言**（可嵌套、递归）
2. 正则无法可靠处理：
   - 标签嵌套
   - 属性换行
   - 注释 / script / style
3. 极易出现：
   - 误匹配
   - 漏匹配
   - 性能问题

### 错误示例

```regex
<.*?>
```

在复杂 HTML 中几乎必然出错

### 正确做法

- 使用解析器：
  - Jsoup
  - HTMLParser
  - 浏览器 DOM

👉 **正则只适合：简单、确定结构的文本片段**

------

## 3️⃣ 贪婪 / 懒惰 / 占有量词区别？

### 三种量词对比

| 类型 | 语法     | 行为     | 是否回溯 |
| ---- | -------- | -------- | -------- |
| 贪婪 | `* + {}` | 尽可能多 | ✅ 是     |
| 懒惰 | `*? +?`  | 尽可能少 | ✅ 是     |
| 占有 | `*+ ++`  | 一次吃完 | ❌ 否     |

### 示例

```text
输入：<tag>content</tag>
```

- `<.*>` → `<tag>content</tag>`（贪婪）
- `<.*?>` → `<tag>`（懒惰）
- `<.*+>` → 匹配失败（不回溯）

👉 **占有量词是性能优化利器**

------

## 4️⃣ 什么是 ReDoS？如何避免？

### 什么是 ReDoS

**ReDoS（Regular Expression Denial of Service）**：

> 利用正则的灾难性回溯，构造输入导致 CPU 被耗尽

### 高风险正则

```regex
(a+)+b
```

### 避免方式

1. 避免嵌套量词：`(a+)+`
2. 使用占有量词：`a++b`
3. 使用原子组：`(?>a+)b`
4. 限制输入长度
5. 关键正则进行压测

👉 **后端 / 网关 / 安全场景必须重视**





## Java 正则核心 API（含关键坑点）

### 1. 核心类

- `Pattern`：正则表达式编译后的对象
- `Matcher`：匹配器，执行匹配操作

### 2. 关键方法

- `matcher.find()`：**尝试在文本中查找下一个匹配子串**（必须先调用！）

- `matcher.groupCount()`：**返回正则表达式里定义的捕获组数量**

  ✅ 重要：它和是否匹配成功无关！即使 `find()` 失败，`groupCount()` 依然会返回数字！

- `matcher.group(int n)`：获取第 n 个分组的内容

- `matcher.matches()`：全串匹配

### 3. ✅ 正确使用规则（必记）

```java
// 1. 编译正则
Pattern pattern = Pattern.compile("(\\d+)-(\\w+)");
Matcher matcher = pattern.matcher("123-abc");

// 2. 必须先判断 find() / matches()
if (matcher.find()) {
    // 3. 再获取分组
    String group1 = matcher.group(1);
    String group2 = matcher.group(2);
}
```

### 4. ❌ 错误写法（千万避免）

```java
// 错误：直接用 groupCount 判断是否匹配
if (matcher.groupCount() > 0) {  // 永远不可靠！
}
```



# 其他资源

现在你已经完成了关于正则表达式的本课程，你可能会发现你的主要参考资料将是以下类的 API 文档：[`Pattern`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)，[`Matcher`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html) 和 [`PatternSyntaxException`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/PatternSyntaxException.html)。

为了更准确地描述正则表达式构造的行为，我们建议阅读 Jeffrey E. F.Friedl 的书 [*Mastering Regular Expressions*](http://www.amazon.com/exec/obidos/ASIN/0596002890/javasoftsunmicroA/)。《精通正则表达式》

# 正则表达式速查表

### 一、基础语法

| 构造                 | 描述                              | 示例                   |
| -------------------- | --------------------------------- | ---------------------- |
| 普通字符             | 匹配自身（非元字符）              | `abc` 匹配 "abc"       |
| `\`                  | 转义元字符为普通字符              | `\.` 匹配 "."          |
| `\Q...\E`            | 引用内容（内部所有元字符失效）    | `\Qa.b\E` 匹配 "a.b"   |
| `Pattern.quote(str)` | 自动引用字符串（等价于`\Qstr\E`） | `Pattern.quote("a*b")` |

### 二、字符类（方括号`[]`内）

| 构造           | 描述                       | 示例                         |
| -------------- | -------------------------- | ---------------------------- |
| `[abc]`        | 匹配 a、b、c 中的任意一个  | `[xyz]` 匹配 "x"、"y" 等     |
| `[^abc]`       | 匹配非 a、b、c 的任意字符  | `[^0-9]` 匹配非数字          |
| `[a-z]`        | 匹配指定范围字符（含边界） | `[A-Z]` 匹配大写字母         |
| `[a-d[m-p]]`   | 联合（a-d 或 m-p）         | `[a-c[1-3]]` 匹配 a-c 或 1-3 |
| `[a-z&&[def]]` | 交集（a-z 且 def）         | `[0-9&&[3-5]]` 匹配 3-5      |
| `[a-z&&[^bc]]` | 减法（a-z 排除 b、c）      | `[a-z&&[^x-y]]` 排除 x-y     |

### 三、预定义字符类

| 构造 | 描述                            | 等价类            |
| ---- | ------------------------------- | ----------------- |
| `.`  | 匹配任意字符（默认不包含`\n`）  | -                 |
| `\d` | 数字字符                        | `[0-9]`           |
| `\D` | 非数字字符                      | `[^0-9]`          |
| `\s` | 空白字符（空格、\t、\n、\r 等） | `[ \t\n\x0B\f\r]` |
| `\S` | 非空白字符                      | `[^\s]`           |
| `\w` | 单词字符（字母、数字、下划线）  | `[a-zA-Z_0-9]`    |
| `\W` | 非单词字符                      | `[^\w]`           |

### 四、量词（匹配次数）

| 类型 | 构造      | 描述                                  |
| ---- | --------- | ------------------------------------- |
| 贪婪 | `X?`      | X 出现 0 次或 1 次                    |
| 贪婪 | `X*`      | X 出现 0 次或多次（尽可能多）         |
| 贪婪 | `X+`      | X 出现 1 次或多次（尽可能多）         |
| 贪婪 | `X{n}`    | X 恰好出现 n 次                       |
| 贪婪 | `X{n,}`   | X 至少出现 n 次                       |
| 贪婪 | `X{n,m}`  | X 出现 n 到 m 次（尽可能多）          |
| 懒惰 | `X??`     | X 出现 0 次或 1 次（尽可能少）        |
| 懒惰 | `X*?`     | X 出现 0 次或多次（尽可能少）         |
| 懒惰 | `X+?`     | X 出现 1 次或多次（尽可能少）         |
| 懒惰 | `X{n}?`   | X 恰好出现 n 次（同贪婪，因固定次数） |
| 懒惰 | `X{n,}?`  | X 至少出现 n 次（尽可能少）           |
| 懒惰 | `X{n,m}?` | X 出现 n 到 m 次（尽可能少）          |
| 占有 | `X?+`     | X 出现 0 次或 1 次（不回溯）          |
| 占有 | `X*+`     | X 出现 0 次或多次（不回溯）           |
| 占有 | `X++`     | X 出现 1 次或多次（不回溯）           |
| 占有 | `X{n}+`   | X 恰好出现 n 次（不回溯）             |
| 占有 | `X{n,}+`  | X 至少出现 n 次（不回溯）             |
| 占有 | `X{n,m}+` | X 出现 n 到 m 次（不回溯）            |

### 五、捕获组与反向引用

| 构造          | 描述                                  | 示例                     |
| ------------- | ------------------------------------- | ------------------------ |
| `(pattern)`   | 捕获组（编号从 1 开始，按左括号顺序） | `(ab)\1` 匹配 "abab"     |
| `(?:pattern)` | 非捕获组（仅分组，不保存内容）        | `(?:ab)+` 匹配 "abab"    |
| `\n`          | 反向引用（引用第 n 个捕获组的内容）   | `(\d)\1` 匹配 "22"、"33" |

### 六、边界匹配器

| 构造 | 描述                                   | 示例                                 |
| ---- | -------------------------------------- | ------------------------------------ |
| `^`  | 输入字符串开头（多行模式匹配每行开头） | `^hello` 匹配 "hello" 开头           |
| `$`  | 输入字符串结尾（多行模式匹配每行结尾） | `world$` 匹配 "world" 结尾           |
| `\b` | 单词边界（单词与非单词字符之间）       | `\bcat\b` 匹配独立 "cat"             |
| `\B` | 非单词边界                             | `\Bcat\B` 匹配 "category" 中的 "cat" |
| `\A` | 输入字符串绝对开头（不受多行影响）     | `\Aabc` 仅匹配字符串首的 "abc"       |
| `\Z` | 输入字符串绝对结尾（忽略最后的换行）   | `xyz\Z` 匹配字符串尾的 "xyz"         |

### 七、零宽断言（匹配位置，不消费字符）

| 构造           | 类型     | 描述                      | 示例                                 |
| -------------- | -------- | ------------------------- | ------------------------------------ |
| `(?=pattern)`  | 正向先行 | 匹配 pattern 前面的位置   | `\d+(?=元)` 匹配 "100 元" 中的 "100" |
| `(?!pattern)`  | 负向先行 | 匹配非 pattern 前面的位置 | `\d+(?!%)` 匹配不跟 "%" 的数字       |
| `(?<=pattern)` | 正向后顾 | 匹配 pattern 后面的位置   | `(?<=￥)\d+` 匹配 "￥100" 中的 "100" |
| `(?<!pattern)` | 负向后顾 | 匹配非 pattern 后面的位置 | `(?<!\$)\d+` 匹配前面不是 "$" 的数字 |

### 八、Pattern 编译标志

| 标志常量           | 简写（正则中） | 描述                                      |
| ------------------ | -------------- | ----------------------------------------- |
| `CASE_INSENSITIVE` | `(?i)`         | 忽略大小写（默认仅 ASCII）                |
| `MULTILINE`        | `(?m)`         | 多行模式（`^`/`$`匹配每行首尾）           |
| `DOTALL`           | `(?s)`         | 使`.`匹配换行符`\n`                       |
| `UNICODE_CASE`     | -              | 结合`CASE_INSENSITIVE`支持 Unicode 大小写 |
| `COMMENTS`         | `(?x)`         | 忽略正则中的空格和注释（`#`后内容）       |

### 九、核心 API 方法

| 类        | 方法                               | 描述                                |
| --------- | ---------------------------------- | ----------------------------------- |
| `Pattern` | `compile(String regex)`            | 编译正则表达式为 Pattern 对象       |
| `Pattern` | `compile(String regex, int flags)` | 带标志编译正则                      |
| `Pattern` | `matcher(CharSequence input)`      | 创建 Matcher 对象（关联输入字符串） |
| `Matcher` | `find()`                           | 查找下一个匹配子串（返回 boolean）  |
| `Matcher` | `matches()`                        | 整个输入字符串是否完全匹配正则      |
| `Matcher` | `group()`                          | 返回当前匹配的子串（group (0)）     |
| `Matcher` | `group(int n)`                     | 返回第 n 个捕获组的内容             |
| `Matcher` | `start()`/`end()`                  | 返回当前匹配的起始 / 结束索引       |
| `Matcher` | `replaceAll(String replacement)`   | 替换所有匹配的子串                  |
| `Matcher` | `replaceFirst(String replacement)` | 替换第一个匹配的子串                |

### 十、常见示例

| 场景               | 正则表达式                                         |
| ------------------ | -------------------------------------------------- |
| 邮箱校验           | `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$` |
| 中国大陆手机号     | `^1[3-9]\d{9}$`                                    |
| 日期（yyyy-mm-dd） | `^\d{4}-\d{2}-\d{2}$`                              |
| 提取 HTML 标签内容 | `<title>(.*?)</title>`（懒惰量词）                 |

**注意**：Java 字符串中反斜杠需转义，如正则`\d`在 Java 中需写为`"\\d"`。