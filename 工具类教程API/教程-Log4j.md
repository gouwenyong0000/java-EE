# [Java Log4j 教程](https://geek-docs.com/java/java-tutorial/log4j.html)

Java Log4j 教程定义了日志记录，介绍了 Log4j 库，并在几个代码示例中演示了日志记录。

## 日志记录

日志记录是将信息写入日志文件的过程。 日志文件包含有关在操作系统，软件或通信中发生的各种事件的信息。

## 记录目的

完成记录是出于以下目的：

- 故障排除
- 信息收集
- 剖析
- 稽核
- 产生统计资料

记录不仅限于识别软件开发中的错误。 它还可用于检测安全事件，监视策略违规，在出现问题时提供信息，查找应用瓶颈或生成使用情况数据。

## 要记录哪些事件

应记录的事件包括输入验证失败，身份验证和授权失败，应用错误，配置更改以及应用启动和关闭。

## 哪些事件不记录

不应记录的事件包括应用源代码，会话标识值，访问令牌，敏感的个人数据，密码，数据库连接字符串，加密密钥，银行帐户和持卡人数据。

## 记录最佳做法

以下是进行日志记录的一些最佳做法：

- 日志记录应该有意义。
- 日志记录应在不同级别进行结构化和完成。
- 日志应包含上下文。
- 日志消息应该是人类所无法理解的，并且可以被机器解析。
- 测井应保持平衡； 它不应包含过多或过多的信息。
- 测井应适应开发和生产。
- 登录更复杂的应用应完成几个日志文件。

## Log4j

Apache Log4j 是基于 Java 的日志记录实用程序。 它是 Apache Software Foundation 的项目。 可以通过 Java 代码或在配置文件中配置 Log4j。 配置文件可以 XML，JSON，YAML 或属性文件格式编写。

## Log4j 组件

Log4j 具有三个主要组件：记录器，附加器和布局。 记录器被命名为目标，可捕获捕获日志消息并将其发送到附加程序。 Appender 将日志消息传递到其目的地，例如文件，套接字或控制台。 布局用于定义日志消息的格式。

## 根记录器

Log4j 具有一个特定的内置记录器，称为“根查询器”。 它位于层次结构的顶部，即使未配置，也始终存在。 它为应用中的所有类编写消息。 如果我们不希望将来自特定记录器的消息传递到根记录器，则将发信人的`additivity`属性更改为`false`。

## 包特定的日志记录

我们可能希望将日志记录限制为某些 Java 包。 在进行 XML 配置的情况下，我们使用`name`属性设置特定于包的日志记录。

```java
<Logger name="com.zetcode.work" level="info" additivity="false" >
    <AppenderRef ref="MyFile" />
</Logger>
```

使用此记录器，我们将信息级别的事件消息从`com.zetcode.work`包传递到日志文件的目标位置。 将`additivity`设置为`false`时，消息不会传播到根记录器。

## Log4j 事件级别

级别用于标识事件的严重性。 级别按从最具体到最不具体的顺序进行组织：

- `OFF`-最具体，不记录
- `FATAL`-严重错误，将阻止应用继续； 非常具体，数据很少
- `ERROR`-严重错误，可能可以恢复
- `WARN`-可能有害的消息
- `INFO`-信息性消息
- `DEBUG`-常规调试事件
- `TRACE`-细粒度的调试消息，通常捕获通过应用的流； 不太具体，很多数据
- `ALL`-最不具体，所有数据

下表显示了日志记录级别的工作方式。

| 活动级别 |                           配置级别                           |
| :------: | :----------------------------------------------------------: |
|  `ALL`   | `TRACE` | `DEBUG` | `INFO` | `WARN` | `ERROR` | `FATAL` | `OFF` |
|  `ALL`   |       YES \| YES \| YES \| YES \| YES \| YES \| YES \|       |
| `TRACE`  |        YES \| YES \| NO \| NO \| NO \| NO \| NO \| NO        |
| `DEBUG`  |       YES \| YES \| YES \| NO \| NO \| NO \| NO \| NO        |
|  `INFO`  |       YES \| YES \| YES \| YES \| NO \| NO \| NO \| NO       |
|  `WARN`  |      YES \| YES \| YES \| YES \| YES \| NO \| NO \| NO       |
| `ERROR`  |      YES \| YES \| YES \| YES \| YES \| YES \| NO \| NO      |
| `FATAL`  |     YES \| YES \| YES \| YES \| YES \| YES \| YES \| NO      |

该表说明了事件和配置级别的工作方式。 如果我们在调试级别记录消息，并且配置为“警告”，则不会传递该消息。 如果我们在信息级别记录消息，而配置级别是调试，则消息将传递到其目的地。

## Log4j 基本示例

在第一个示例中，我们为一个简单的 Java 控制台应用设置了 Log4j。

```
$ tree
.
├── pom.xml
└── src
    ├── main
    │   ├── java
    │   │   └── com
    │   │       └── zetcode
    │   │           └── JavaLog4jEx.java
    │   └── resources
    │       └── log4j2.xml
    └── test
        └── java
```

这是项目结构。 Log4j 配置文件位于`src/main/resources`目录中。 我们使用 XML 格式。

```
pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.zetcode</groupId>
    <artifactId>JavaLog4jEx</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
    </properties>

    <dependencies>

        <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-core</artifactId>
            <version>2.9.1</version>
        </dependency>

    </dependencies>

</project>
```

这是 Maven `pom.xml`文件。 我们包括`log4j-core`依赖项。

```
JavaLog4jEx.java
package com.zetcode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JavaLog4jEx {

    private static final Logger logger = LogManager.getLogger(JavaLog4jEx.class);

    public static void main(String[] args) {

        logger.info("The main() method is called");

        doWork();

        logger.warn("Warning message");
        logger.error("Error message");
    }

    public static void doWork() {

        // doing some work

        logger.info("The doWork() method is called");
    }
}
```

这是一个简单的 Java 控制台示例。

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
```

我们导入`LogManager`和`Logger`类。

```java
private static final Logger logger = LogManager.getLogger(JavaLog4jEx.class);
```

从`LogManager`中，我们得到记录器。

```java
logger.info("The main() method is called");

doWork();

logger.warn("Warning message");
logger.error("Error message");
```

我们生成信息，警告和错误消息。

```
log4j2.xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration status="info">
    <Properties>
        <Property name="layout">%d [%t] %-5level %logger - %m%n</Property>
    </Properties>

    <Appenders>

        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="${layout}" />
        </Console>     

    </Appenders>

    <Loggers>

        <Logger name="com.zetcode" level="info" additivity="false" >
            <AppenderRef ref="Console" />
        </Logger>

        <Root level="error">
            <AppenderRef ref="Console" />
        </Root>    

    </Loggers>
</Configuration>
```

在`log4j2.xml`中配置 Log4j。 我们选择了 XML 文件格式。

```java
<Properties>
    <Property name="layout">%d [%t] %-5level %logger - %m%n</Property>
</Properties>
```

在`Properties`标记中，我们设置了日志目录和布局。 布局定义了日志消息的格式。

模式布局由转换说明符组成。 每个说明符均以百分号开头，后跟可选的格式修饰符和强制转换字符。 `%d`输出记录事件的日期。 `%t`输出生成日志事件的线程的名称。 `%-5level`输出记录事件的级别，级别名称中至少要包含五个字符，并且这些字符必须对齐。 `%logger`输出发布了记录事件的记录器的名称。 `%m`打印与日志记录事件关联的应用消息，`%n`是平台相关的行分隔符或多个字符。

```java
<Appenders>

    <Console name="Console" target="SYSTEM_OUT">
        <PatternLayout pattern="${layout}" />
    </Console>     

</Appenders>
```

追加项是定义日志记录消息发送位置的对象。 我们定义一个控制台附加程序； 它使用上述布局将消息写到标准输出。

```java
<Loggers>

    <Logger name="com.zetcode" level="info" additivity="false" >
        <AppenderRef ref="Console" />
    </Logger>

    <Root level="error">
        <AppenderRef ref="Console" />
    </Root>    

</Loggers>
```

我们有两个记录器。 `com.zetcode`记录器具有级别信息，而根记录器具有级别错误。 两个记录器都使用控制台附加程序，例如 他们将消息传递到控制台。 将`additivity`设置为`false`时，`com.zetcode's`消息不会传播到根记录器。 换句话说，消息不会两次打印到控制台。

```java
2017-11-17 15:17:36,899 [main] INFO  com.zetcode.JavaLog4jEx - The main() method is called
2017-11-17 15:17:36,903 [main] INFO  com.zetcode.JavaLog4jEx - The doWork() method is called
2017-11-17 15:17:36,903 [main] WARN  com.zetcode.JavaLog4jEx - Warning message
2017-11-17 15:17:36,903 [main] ERROR com.zetcode.JavaLog4jEx - Error message
```

运行示例后，控制台中将包含这些消息。

## Log4j 基本示例 II

在下一个示例中，我们将说明 Log4j 的其他功能。 我们将消息写入文件并定义特定于软件包的记录器。

```java
$ tree
.
├── pom.xml
└── src
    ├── main
    │   ├── java
    │   │   └── com
    │   │       └── zetcode
    │   │           ├── main
    │   │           │   └── JavaLog4jEx.java
    │   │           └── work
    │   │               └── MyWork.java
    │   └── resources
    │       └── log4j2.xml
    └── test
        └── java
```

这是项目结构。

```
pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.zetcode</groupId>
    <artifactId>JavaLog4jEx2</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
    </properties>

    <dependencies>

        <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-core</artifactId>
            <version>2.9.1</version>
        </dependency>

    </dependencies>
    <name>JavaLog4jEx2</name>
</project>
```

这是`pom.xml`文件。

```
JavaLog4jEx2.java
package com.zetcode.main;

import com.zetcode.work.MyWork;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JavaLog4jEx2 {

    private static final Logger logger = LogManager.getLogger(JavaLog4jEx2.class);

    public static void main(String[] args) {

        logger.info("The main() method is called");

        doJob();

        MyWork mw = new MyWork();
        mw.doMyWork();
    }

    public static void doJob() {

        // doing some job

        logger.info("The doJob() method is called");
    }
}
```

这是主应用文件。 它调用了一些做一些日志记录的方法。

```
MyWork.java
package com.zetcode.work;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MyWork {

    private static final Logger logger = LogManager.getLogger(MyWork.class);

    public void doMyWork() {

        // doing some work

        logger.info("doMyWork() method called");
    }
}
```

我们有一个简单的方法来记录一条信息消息。 其类在`com.zetcode.work`包中。 我们定义了一个记录器，它将仅记录来自此程序包的消息。

```
log4j2.xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration status="info">
    <Properties>
        <Property name="layout">%d [%t] %-5level %logger{36} - %m%n</Property>
    </Properties>

    <Appenders>

        <Console name="Console">
            <PatternLayout pattern="${layout}" />
        </Console>     

        <File name="MyFile" fileName="/home/janbodnar/tmp/mylog.log" append="false">
            <PatternLayout pattern="${layout}"/>
        </File>        

    </Appenders>

    <Loggers>

        <Logger name="com.zetcode.work" level="info" additivity="false" >
            <AppenderRef ref="MyFile" />
        </Logger>

        <Root level="info">
            <AppenderRef ref="Console" />
        </Root>    

    </Loggers>
</Configuration>
```

在`log4j2.xml`配置文件中，我们定义了两个追加器和两个记录器。

```java
<File name="MyFile" fileName="/home/janbodnar/tmp/mylog.log" append="false">
    <PatternLayout pattern="${layout}"/>
</File>   
```

我们定义了一个文件追加器，它将日志消息写入指定的文件。 文件名由`fileName`属性指定。 将`append`属性设置为`false`时，该文件将始终被覆盖。

```java
<Logger name="com.zetcode.work" level="info" additivity="false" >
    <AppenderRef ref="MyFile" />
</Logger>
```

我们定义了一个记录器，用于记录来自`com.zetcode.work`包的信息消息。 记录器将消息写入文件。

```java
<Root level="info">
    <AppenderRef ref="Console" />
</Root>
```

其余消息（在我们的情况下为`com.zetcode.main`包中的消息）由 root 记录程序处理。

```java
2017-11-17 15:35:22,718 [main] INFO  com.zetcode.main.JavaLog4jEx2 - The main() method is called
2017-11-17 15:35:22,721 [main] INFO  com.zetcode.main.JavaLog4jEx2 - The doJob() method is called
```

这两个消息已写入控制台。

```java
$ cat mylog.log 
2017-11-17 15:35:22,722 [main] INFO  com.zetcode.work.MyWork - doMyWork() method called
```

此消息已写入`mylog.log`文件。

## Log4j `RollingFileAppender`

`RollingFileAppender`是一种特殊类型的附加程序，可在日志文件达到一定大小或符合时间标准时备份它们。 滚动文件追加器会自动滚动或归档当前日志文件，并继续记录新文件。

以下应用使用`RollingFileAppender`。

```java
$ tree
.
├── pom.xml
└── src
    └── main
        ├── java
        │   └── com
        │       └── zetcode
        │           └── JavaLog4jRollingFileEx.java
        └── resources
            └── log4j2.xml
```

这是项目结构。

```
pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
 http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.zetcode</groupId>
    <artifactId>JavaLog4jRollingFileEx</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
    </properties>

    <dependencies>

        <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-core</artifactId>
            <version>2.9.1</version>
        </dependency>

    </dependencies>    
</project>
```

这是`pom.xml`文件，其中包含`log4j-core`依赖项。

```
JavaLog4jRollingFileEx.java
package com.zetcode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JavaLog4jRollingFileEx {

    private static final Logger logger = LogManager.getLogger(
        JavaLog4jRollingFileEx.class);

    public static void main(String[] args) {

        logger.info("Information message");
        logger.warn("Warning message");
        logger.error("Error message");
    }
}
```

在`JavaLog4jRollingFileEx`类中，我们记录了三个消息。

```
log4j2.xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration status="info">
    <Properties>
        <Property name="logdir">/home/janbodnar/tmp</Property>
        <Property name="layout">%d [%t] %-5level %logger{36} - %m%n</Property>
    </Properties>

    <Appenders>

        <Console name="Console">
            <PatternLayout pattern="${layout}" />
        </Console>           

        <RollingFile name="MyFile" fileName="${logdir}/app.log"
                     filePattern="${logdir}/app.%d{yyyy-MM-dd}-%i.log">
            <PatternLayout pattern="${layout}" />
            <Policies>
                <TimeBasedTriggeringPolicy />
                <SizeBasedTriggeringPolicy size="1 MB" />
            </Policies>
            <DefaultRolloverStrategy max="10" />
        </RollingFile>

    </Appenders>

    <Loggers>

        <Logger name="com.zetcode" level="info" additivity="false">
            <AppenderRef ref="MyFile" />
        </Logger>

        <Root level="error">
            <AppenderRef ref="Console" />
        </Root>    

    </Loggers>
</Configuration>
```

在`log4j2.xml`中配置 Log4j。

```java
<RollingFile name="MyFile" fileName="${logdir}/app.log"
                filePattern="${logdir}/app.%d{yyyy-MM-dd}-%i.log">
    <PatternLayout pattern="${layout}" />
...
    <DefaultRolloverStrategy max="10" />
</RollingFile>
```

使用`RollingFile`标签创建滚动文件附加程序。 我们使用`fileName`属性设置日志文件的位置。 `PatternLayout`设置日志消息的布局。 如果存档数量达到十个，`DefaultRolloverStrategy`将删除较旧的存档。

```java
<Policies>
  <TimeBasedTriggeringPolicy />
  <SizeBasedTriggeringPolicy size="1 MB" />
</Policies>
```

触发策略在`Policies`标记中定义。 它们控制发生翻转的条件。 在这里，我们使用两个策略：`TimeBasedTriggeringPolicy`和`SizeBasedTriggeringPolicy`。 `TimeBasedTriggeringPolicy`根据最具体的日期和时间模式开始翻转； 就我们而言，每小时 如果日志文件的大小达到 1 MB，则`SizeBasedTriggeringPolicy`开始翻转。

```java
<Loggers>

    <Logger name="com.zetcode" level="info" additivity="false">
        <AppenderRef ref="MyFile" />
    </Logger>

    <Root level="error">
        <AppenderRef ref="Console" />
    </Root>    

</Loggers>
```

我们定义了两个记录器。 `com.zetcode`记录器登录到文件附加器。 根记录器未在此应用中使用。

```java
$ cat app.log 
2017-11-17 16:44:14,251 [main] INFO  com.zetcode.JavaLog4jRollingFileEx - Information message
2017-11-17 16:44:14,254 [main] WARN  com.zetcode.JavaLog4jRollingFileEx - Warning message
2017-11-17 16:44:14,255 [main] ERROR com.zetcode.JavaLog4jRollingFileEx - Error message
2017-11-17 16:44:28,158 [main] INFO  com.zetcode.JavaLog4jRollingFileEx - Information message
2017-11-17 16:44:28,160 [main] WARN  com.zetcode.JavaLog4jRollingFileEx - Warning message
2017-11-17 16:44:28,161 [main] ERROR com.zetcode.JavaLog4jRollingFileEx - Error message
2017-11-17 18:11:58,189 [main] INFO  com.zetcode.JavaLog4jRollingFileEx - Information message
2017-11-17 18:11:58,207 [main] WARN  com.zetcode.JavaLog4jRollingFileEx - Warning message
2017-11-17 18:11:58,208 [main] ERROR com.zetcode.JavaLog4jRollingFileEx - Error message
```

这是日志文件的示例输出。

## 使用 Spring Boot 的 Log4j

下一个示例显示了如何在 Spring Boot 应用中使用 Log4j。 该应用是控制台 Java 程序。

Spring Boot 默认使用 Logback 进行日志记录。 因此，我们需要配置 Spring Boot 以排除 Logback 并包含 Log4j。

常规日志设置在`application.properties`文件中设置。 要配置日志系统的更细粒度的设置，我们需要使用本机配置格式。 在本例中，为 Log4j 的设置。

```java
$ tree
.
├── pom.xml
└── src
    ├── main
    │   ├── java
    │   │   └── com
    │   │       └── zetcode
    │   │           ├── Application.java
    │   │           └── MyRunner.java
    │   └── resources
    │       ├── app.log
    │       └── log4j2.xml
    └── test
        └── java
```

这是项目结构。 日志消息将写入`app.log`文件。

```
pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" 
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.zetcode</groupId>
    <artifactId>JavaLog4jSpringBootEx</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
    </properties>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>1.5.8.RELEASE</version>
    </parent>    

    <dependencies>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-logging</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-log4j2</artifactId>
        </dependency>     

    </dependencies>    

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>            
        </plugins>
    </build>      

</project>
```

在`pom.xml`文件中，我们排除了`spring-boot-starter-logging`依赖性，并添加了`spring-boot-starter-log4j2`依赖性。

```
log4j2.xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration status="info">
    <Properties>
        <Property name="layout">%d [%t] %-5level %logger{36} - %m%n</Property>
    </Properties>

    <Appenders>

        <Console name="Console">
            <PatternLayout pattern="${layout}" />
        </Console>     

        <File name="MyFile" fileName="src/main/resources/app.log">
            <PatternLayout pattern="${layout}" />
        </File>        

    </Appenders>

    <Loggers>

        <Logger name="com.zetcode" level="info" additivity="false" >
            <AppenderRef ref="MyFile" />
        </Logger>

        <Root level="error">
            <AppenderRef ref="Console" />
        </Root>    

    </Loggers>
</Configuration>
```

Spring Boot 在`src/main/resources`目录中找到`log4j2.xml`配置文件。

```java
<File name="MyFile" fileName="src/main/resources/app.log">
    <PatternLayout pattern="${layout}" />
</File> 
```

日志消息将写入`src/main/resources/app.log`文件。

```
MyRunner.java
package com.zetcode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class MyRunner implements CommandLineRunner {

    private static final Logger logger = LogManager.getLogger(MyRunner.class);

    @Override
    public void run(String... args) throws Exception {

        logger.info("Information message");
        logger.warn("Warning message");
    }
}
```

这是我们的命令行运行程序。 `run()`方法生成信息和警告消息。

```
Application.java
package com.zetcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

在`Application`类中，我们设置了 Spring Boot 应用。

在本教程中，我们使用了 Log4j 库。