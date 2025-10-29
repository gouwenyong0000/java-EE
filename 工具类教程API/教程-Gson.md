> 本文由 [简悦 SimpRead](http://ksria.com/simpread/) 转码， 原文地址 [geek-docs.com](https://geek-docs.com/java/java-tutorial/gson.html)

> Gson 教程展示了如何使用 Gson 库在 Java 中使用 JSON。

Gson 教程展示了如何使用 Gson 库在 Java 中使用 JSON。 我们使用三种不同的 Gson API 来处理 JSON。 源代码可在作者的 Github [存储库](https://github.com/janbodnar/Java-Gson-Examples)中获得。

JSON（JavaScript 对象表示法）是一种轻量级的数据交换格式。 人类很容易读写，机器也很容易解析和生成。 与 XML 相比，它不那么冗长且更具可读性。 JSON 的官方 Internet 媒体类型为`application/json`。 JSON 文件扩展名是`.json`。 JSON 可直接由 JavaScript 使用。

Java Gson 库
-----------

Gson 是 Java 序列化 / 反序列化库，用于将 Java 对象转换为 JSON 并返回。 Gson 由 Google 创建，供内部使用，后来开源。

Java Gson Maven 依赖
------------------

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.8.2</version>
</dependency>
```

这是对 Gson 的 Maven 依赖。

Java Gson 特性
------------

这些是 Gson 特性：

*   用于 Java 对象 JSON 序列化和反序列化的简单工具。
*   Java 泛型的广泛支持。
*   对象的自定义表示。
*   支持任意复杂的对象。
*   快速，低内存占用。
*   允许紧凑的输出和漂亮的打印。

Java Gson API
-------------

Gson 具有三种 API：

*   数据绑定 API
*   树模型 API
*   流 API

数据绑定 API 使用属性访问器将 JSON 与 POJO 之间进行转换。 Gson 使用数据类型适配器处理 JSON 数据。 它类似于 XML JAXB 解析器。

树模型 API 创建 JSON 文档的内存树表示。 它构建`JsonElements`的树。 它类似于 XML DOM 解析器。

流 API 是一种低级 API，它使用`JsonReader`和`JsonWriter`作为离散记号读取和写入 JSON 内容。 这些类将数据读取为`JsonTokens`。 该 API 具有最低的开销，并且在读 / 写操作中速度很快。 它类似于 XML 的 Stax 解析器。

Java Gson 类
-----------

`Gson`是使用 Gson 库的主要类。 有两种创建`Gson`的基本方法：

*   新 Gson（）
*   新的 GsonBuilder（）。create（）

`GsonBuilder`可用于使用各种配置设置来构建 Gson。

Java Gson `toJson()`
--------------------

`toJson()`方法将指定的对象序列化为其等效的 JSON 表示形式。

`GsonToJson.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

public class GsonToJson {

    public static void main(String[] args) {

        Map<Integer, String> colours = new HashMap<>();
        colours.put(1, "blue");
        colours.put(2, "yellow");
        colours.put(3, "green");

        Gson gson = new Gson();

        String output = gson.toJson(colours);

        System.out.println(output);
    }
}
```

在示例中，我们使用`toJSon()`方法将映射序列化为 JSON。

```json
{"1":"blue","2":"yellow","3":"green"}
```

这是示例的输出。

Java Gson `fromJson()`
----------------------

`fromJson()`方法将指定的 JSON 反序列化为指定类的对象。

`GsonFromJson.java`

```java
package com.zetcode;

import com.google.gson.Gson;

class User {

    private final String firstName;
    private final String lastName;

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("User{").append("First name: ")
                .append(firstName).append(", Last name: ")
                .append(lastName).append("}").toString();
    }
}

public class GsonFromJson {

    public static void main(String[] args) {

        String json_string = "{\"firstName\":\"Tom\", \"lastName\": \"Broody\"}";

        Gson gson = new Gson();
        User user = gson.fromJson(json_string, User.class);

        System.out.println(user);
    }
}
```

该示例使用`fromJson()`方法将 JSON 读取到 Java 对象中。

```java
class User {

    private final String firstName;
    private final String lastName;

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("User{").append("First name: ")
                .append(firstName).append(", Last name: ")
                .append(lastName).append("}").toString();
    }
}
```

注意，没有必要使用 getter 和 setter 方法。

```
User{First name: Tom, Last name: Broody}
```

This is the output of the example.

`GsonBuilder`
-------------

`GsonBuilder`使用各种配置设置构建 Gson。 `GsonBuilder`遵循构建器模式，通常通过首先调用各种配置方法来设置所需的选项，最后调用`create()`来使用它。

`GsonBuilderEx.java`

```java
package com.zetcode;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.PrintStream;

class User {

    private final String firstName;
    private final String lastName;

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }
}

public class GsonBuilderEx {

    public static void main(String[] args) throws IOException {

        try (PrintStream prs = new PrintStream(System.out, true, 
                "UTF8")) {

            Gson gson = new GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
                    .create();

            User user = new User("Peter", "Flemming");
            gson.toJson(user, prs);
        }
    }
}
```

在示例中，我们将对象写入 JSON。 我们使用`GsonBuilder`创建`Gson`。

```java
Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
        .create();
```

我们使用`GsonBuilder`创建并配置 Gson。 字段命名策略设置为`FieldNamingPolicy.UPPER_CAMEL_CASE`。

```java
{"FirstName":"Peter","LastName":"Flemming"}
```

这是输出。

Java Gson 漂亮打印
--------------

Gson 有两种输出模式：紧凑和漂亮。

`GsonPrettyPrinting.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.Map;

public class GsonPrettyPrinting {

    public static void main(String[] args) {

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Map<String, Integer> items = new HashMap<>();

        items.put("chair", 3);
        items.put("pencil", 1);
        items.put("book", 5);

        gson.toJson(items, System.out);
    }
}
```

该示例漂亮地显示了 JSON 输出。

```java
Gson gson = new GsonBuilder().setPrettyPrinting().create();
```

`setPrettyPrinting()`方法设置漂亮的打印模式。

```json
{
  "chair": 3,
  "book": 5,
  "pencil": 1
}
```

This is the output of the example.

序列化空值
-----

默认情况下，Gson 不会将具有空值的字段序列化为 JSON。 如果 Java 对象中的字段为`null`，则 Gson 会将其排除。 我们可以使用`serializeNulls()`方法强制 Gson 通过 GsonBuilder 序列化`null`值。

`GsonSerializeNulls.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

class User {

    private String firstName;
    private String lastName;

    public User() {};

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("User{").append("First name: ")
                .append(firstName).append(", Last name: ")
                .append(lastName).append("}").toString();
    }
}

public class GsonSerializeNulls {

    public static void main(String[] args) {

        GsonBuilder builder = new GsonBuilder();

        builder.serializeNulls();

        Gson gson = builder.create();

        User user = new User();
        user.setFirstName("Norman");

        String json = gson.toJson(user);
        System.out.println(json);

    }
}
```

该示例显示了如何序列化`null`值。

```
{"firstName":"Norman","lastName":null}
```

This is the output.

Java Gson 写入列表
--------------

以下示例将 JSON 对象列表写入文件。

`GsonWriteList.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class Item {

    private final String name;
    private final int quantity;

    public Item(String name, int quantity) {
        this.name = name;
        this.quantity = quantity;
    }
}

public class GsonWriteList {

    public static void main(String[] args) throws IOException {

        String fileName = "src/main/resources/items.json";

        try (FileOutputStream fos = new FileOutputStream(fileName);
                OutputStreamWriter isr = new OutputStreamWriter(fos, 
                        StandardCharsets.UTF_8)) {

            Gson gson = new Gson();

            Item item1 = new Item("chair", 4);
            Item item2 = new Item("book", 5);
            Item item3 = new Item("pencil", 1);

            List<Item> items = new ArrayList<>();
            items.add(item1);
            items.add(item2);
            items.add(item3);

            gson.toJson(items, isr);
        }

        System.out.println("Items written to file");
    }
}
```

该示例将 JSON 数据写入`items.json`文件。

Java Gson 读入数组
--------------

下一个示例将数据读取到 Java 数组中。

```
$ cat users.json
[{"firstName":"Peter","lastName":"Flemming"}, {"firstName":"Nicole","lastName":"White"},
     {"firstName":"Robin","lastName":"Bullock"} ]
```

这些是`users.json`文件的内容。

`GsonReadArray.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

class User {

    private final String firstName;
    private final String lastName;

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("{User").append("First name: ")
                .append(firstName).append(", Last name: ")
                .append(lastName).append("}").toString();
    }
}

public class GsonReadArray {

    public static void main(String[] args) throws IOException {

        Gson gson = new GsonBuilder().create();

        String fileName = "src/main/resources/users.json";
        Path path = new File(fileName).toPath();

        try (Reader reader = Files.newBufferedReader(path, 
                StandardCharsets.UTF_8)) {

            User[] users = gson.fromJson(reader, User[].class);

            Arrays.stream(users).forEach( e -> {
                System.out.println(e);
            });
        }
    }
}
```

该示例将`items.json`文件中的数据读取到数组中。 我们将数组的内容打印到控制台。

```
User[] users = gson.fromJson(reader, User[].class);
```

`fromJson()`的第二个参数是数组类。

Java Gson 从 URL 读取 JSON
-----------------------

以下示例从网页读取 JSON 数据。 我们从`http://time.jsontest.com`获得 JSON 数据。

```
{
   "time": "02:44:19 PM",
   "milliseconds_since_epoch": 1496155459478,
   "date": "05-30-2017"
}
```

GET 请求返回此 JSON 字符串。

`GsonReadWebPage.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

class TimeData {

    private String time;
    private Long milliseconds_since_epoch;
    private String date;

    @Override
    public String toString() {
        return "TimeData{" + "time=" + time + ", milliseconds_since_epoch="
                + milliseconds_since_epoch + ", date=" + date + '}';
    }
}

public class GsonReadWebPage {

    public static void main(String[] args) throws IOException {

        String webPage = "http://time.jsontest.com";

        try (InputStream is = new URL(webPage).openStream();
                Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

            Gson gson = new Gson();
            TimeData td = gson.fromJson(reader, TimeData.class);

            System.out.println(td);
        }
    }
}
```

该代码示例从`http://time.jsontest.com`读取 JSON 数据。

```
TimeData{time=11:23:09 PM, milliseconds_since_epoch=1516317789302, date=01-18-2018}
```

This is the output.

Java Gson 使用`@Expose`排除字段
-------------------------

`@Expose`注解指示应公开成员以进行 JSON 序列化或反序列化。 `@Expose`注解可以采用两个布尔参数：`serialize`和`deserialize`。 必须使用`excludeFieldsWithoutExposeAnnotation()`方法显式启用`@Expose`注解。

`GsonExcludeFields.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

enum MaritalStatus {

    SINGLE,
    MARRIED,
    DIVORCED,
    UNKNOWN
}

class Person {

    @Expose
    private String firstName;

    @Expose
    private String lastName;

    private MaritalStatus maritalStatus;

    public Person(String firstName, String lastName, 
            MaritalStatus maritalStatus) {

        this.firstName = firstName;
        this.lastName = lastName;
        this.maritalStatus = maritalStatus;
    }

    public Person() {}
}

public class GsonExcludeFields {

    public static void main(String[] args) {

        Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .setPrettyPrinting()
                .create();

        Person p = new Person("Jack", "Sparrow", MaritalStatus.UNKNOWN);

        gson.toJson(p, System.out);        
    }
}
```

在示例中，我们从序列化中排除一个字段。

```
@Expose
private String firstName;

@Expose
private String lastName;

private MaritalStatus maritalStatus;
```

婚姻状况字段不会被序列化，因为它没有用`@Expose`注解修饰。

```
Gson gson = new GsonBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .setPrettyPrinting()
        .create();
```

`@Expose`注解通过`excludeFieldsWithoutExposeAnnotation()`方法启用了字段排除。

```
{
  "firstName": "Jack",
  "lastName": "Sparrow"
}
```

This is the output.

Java Gson 数据绑定 API
------------------

数据绑定 API 使用属性访问器在 POJO 与 JSON 之间进行转换。 Gson 使用数据类型适配器处理 JSON 数据。

### Gson 数据绑定 API 编写

在下面的示例中，我们使用数据绑定 API 编写数据。

`GsonDataBindApiWrite.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

class Car {

    private final String name;
    private final String model;
    private final int price;
    private final String[] colours;

    public Car(String name, String model, int price, String[] colours) {
        this.name = name;
        this.model = model;
        this.price = price;
        this.colours = colours;
    }
}

public class GsonDataBindApiWrite {

    public static void main(String[] args) throws FileNotFoundException, IOException {

        List<Car> cars = new ArrayList<>();
        cars.add(new Car("Audi", "2012", 22000, new String[]{"gray", "red", "white"}));
        cars.add(new Car("Skoda", "2016", 14000, new String[]{"black", "gray", "white"}));
        cars.add(new Car("Volvo", "2010", 19500, new String[]{"black", "silver", "beige"}));

        String fileName = "src/main/resources/cars.json";
        Path path = Paths.get(fileName);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {

            Gson gson = new Gson();
            gson.toJson(cars, writer);
        }

        System.out.println("Cars written to file");
    }
}
```

在示例中，我们创建了一个汽车对象列表，并使用 Gson 数据绑定 API 对其进行了序列化。

```
Gson gson = new Gson();
gson.toJson(cars, writer);
```

我们将`cars`列表传递给`toJson()`方法。 Gson 自动将汽车对象映射到 JSON。

### 读取 Gson 数据绑定 API【TypeToken】

在下面的示例中，我们使用数据绑定 API 读取数据。

`GsonDataBindingApiRead.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

class Car {

    private final String name;
    private final String model;
    private final int price;
    private final String[] colours;

    public Car(String name, String model, int price, String[] colours) {
        this.name = name;
        this.model = model;
        this.price = price;
        this.colours = colours;
    }

    @Override
    public String toString() {
        return "Car{" + " + model + 
                ", price=" + price + ", colours=" + Arrays.toString(colours) + '}';
    }
}

public class GsonDataBindingApiRead {

    public static void main(String[] args) throws FileNotFoundException, IOException {

        String fileName = "src/main/resources/cars.json";
        Path path = Paths.get(fileName);

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {

            Gson gson = new Gson();
            List<Car> cars = gson.fromJson(reader, 
                    new TypeToken<List<Car>>(){}.getType());

            cars.forEach(System.out::println);
        }        
    }
}
```

在示例中，我们使用 Gson 数据绑定 API 将数据从 JSON 文件读取到汽车对象列表中。

```
List<Car> cars = gson.fromJson(reader, 
        new TypeToken<List<Car>>(){}.getType());
```

Gson 自动将 JSON 映射到`Car`对象。 由于类型信息在运行时会丢失，因此我们需要使用`TypeToken`让 Gson 知道我们使用的是哪种类型。

Java Gson 树模型 API
-----------------

树模型 API 在内存中创建 JSON 文档的树表示。 它构建`JsonElements`的树。 `JsonElement`是代表 Json 元素的类。 它可以是`JsonObject`，`JsonArray`，`JsonPrimitive`或`JsonNull`。

### Gson 树模型写

在以下示例中，我们使用 Gson 树模型 API 将 Java 对象写入 JSON。

`GsonTreeModelWrite.java`

```java
package com.zetcode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

class Car {

    private final String name;
    private final String model;
    private final int price;
    private final String[] colours;

    public Car(String name, String model, int price, String[] colours) {
        this.name = name;
        this.model = model;
        this.price = price;
        this.colours = colours;
    }
}

public class GsonTreeModelWrite {

    public static void main(String[] args) throws FileNotFoundException, IOException {

        List<Car> cars = new ArrayList<>();
        cars.add(new Car("Audi", "2012", 22000, 
                new String[]{"gray", "red", "white"}));
        cars.add(new Car("Skoda", "2016", 14000, 
                new String[]{"black", "gray", "white"}));
        cars.add(new Car("Volvo", "2010", 19500, 
                new String[]{"black", "silver", "beige"}));

        String fileName = "src/main/resources/cars.json";
        Path path = Paths.get(fileName);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {

            Gson gson = new Gson();

            JsonElement tree = gson.toJsonTree(cars);
            gson.toJson(tree, writer);
        }

        System.out.println("Cars written to file");
    }        
}
```

汽车对象列表被序列化为 JSON 格式。

```java
JsonElement tree = gson.toJsonTree(cars);
```

`toJsonTree`方法将指定的对象序列化为其等效表示形式，作为`JsonElements`的树。

```java
JsonArray jarray = tree.getAsJsonArray();
```

我们使用`getAsJsonArray()`方法将树转换为`JsonArray`。

```java
JsonElement jel = jarray.get(1);
```

我们从数组中获取第二个元素。

```java
JsonObject object = jel.getAsJsonObject();
object.addProperty("model", "2009");
```

我们修改一个属性。

```java
gson.toJson(tree, writer);
```

最后，我们将树对象写入文件中。

### Gson 树模型读取

在以下示例中，我们使用 Gson 树模型 API 从 JSON 读取 Java 对象。

`cars.json`

```
[{"name":"Audi","model":"2012","price":22000,"colours":["gray","red","white"]},
 {"name":"Skoda","model":"2009","price":14000,"colours":["black","gray","white"]},
 {"name":"Volvo","model":"2010","price":19500,"colours":["black","silver","beige"]}]
```

这是`cars.json`文件中的 JSON 数据。

`GsonTreeModelRead.java`

```java
package com.zetcode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GsonTreeModelRead {

    public static void main(String[] args) throws FileNotFoundException, IOException {

        String fileName = "src/main/resources/cars.json";
        Path path = Paths.get(fileName);

        try (Reader reader = Files.newBufferedReader(path, 
                StandardCharsets.UTF_8)) {

            JsonParser parser = new JsonParser();
            JsonElement tree = parser.parse(reader);

            JsonArray array = tree.getAsJsonArray();

            for (JsonElement element : array) {

                if (element.isJsonObject()) {

                    JsonObject car = element.getAsJsonObject();

                    System.out.println("********************");
                    System.out.println(car.get("name").getAsString());
                    System.out.println(car.get("model").getAsString());
                    System.out.println(car.get("price").getAsInt());

                    JsonArray cols = car.getAsJsonArray("colors");

                    cols.forEach(col -> {
                        System.out.println(col);
                    });
                }
            }
        }
    }
}
```

在示例中，我们将 JSON 数据从文件读取到`JsonElements`树中。

```
JsonParser parser = new JsonParser();
JsonElement tree = parser.parse(reader);
```

`JsonParser`将 JSON 解析为`JsonElements`的树结构。

```
JsonArray array = tree.getAsJsonArray();
```

我们将树作为`JsonArray`。

```
for (JsonElement element : array) {

    if (element.isJsonObject()) {

        JsonObject car = element.getAsJsonObject();

        System.out.println("********************");
        System.out.println(car.get("name").getAsString());
        System.out.println(car.get("model").getAsString());
        System.out.println(car.get("price").getAsInt());

        JsonArray cols = car.getAsJsonArray("colors");

        cols.forEach(col -> {
            System.out.println(col);
        });
    }
}
```

我们浏览`JsonArray`并打印其元素的内容。

Java Gson 流 API
---------------

Gson 流 API 是一个低级 API，它以离散记号（`JsonTokens`）的形式读取和写入 JSON。 主要类别是`JsonReader`和`JsonWriter`。 `JsonToken`是 JSON 编码的字符串中的结构，名称或值类型。

这些是`JsonToken`类型：

*   BEGIN_ARRAY —打开 JSON 数组
*   END_ARRAY —关闭 JSON 数组
*   BEGIN_OBJECT —打开 JSON 对象
*   END_OBJECT —关闭 JSON 对象
*   NAME-JSON 属性名称
*   STRING — JSON 字符串
*   NUMBER — JSON 数字（双精度，长整型或整型）
*   BOOLEAN — JSON 布尔值
*   NULL — JSON 空值
*   END_DOCUMENT — JSON 流的末尾。

### `JsonWriter`

`JsonWriter`将 JSON 编码值写入流，一次写入一个记号。 流包含文字值（字符串，数字，布尔值和 null）以及对象和数组的开始和结束定界符。 每个 JSON 文档必须包含一个顶级数组或对象。

使用`beginObject()`和`endObject()`方法调用创建对象。 在对象内，标记在名称及其值之间交替。 在`beginArray()`和`endArray()`方法调用中创建数组。

`GsonStreamApiWrite.java`

```java
package com.zetcode;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GsonStreamApiWrite {

    public static void main(String[] args) throws IOException {

        String fileName = "src/main/resources/cars.json";
        Path path = Paths.get(fileName);

        try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(path, 
                StandardCharsets.UTF_8))) {

            writer.beginObject(); 
            writer.name("name").value("Audi");
            writer.name("model").value("2012");
            writer.name("price").value(22000);

            writer.name("colours");
            writer.beginArray();
            writer.value("gray");
            writer.value("red");
            writer.value("white");
            writer.endArray();

            writer.endObject();
        }

        System.out.println("Data written to file");
    }
}
```

在示例中，我们将一个汽车对象写入 JSON 文件。

```
try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(path, 
        StandardCharsets.UTF_8))) {
```

创建一个新的`JsonWriter`。

```
writer.beginObject(); 
...
writer.endObject();
```

如上所述，每个 JSON 文档必须具有一个顶级数组或对象。 在我们的例子中，我们有一个顶级对象。

```
writer.name("name").value("Audi");
writer.name("model").value("2012");
writer.name("price").value(22000);
```

我们将键值对写入文档。

```
writer.name("colours");
writer.beginArray();
writer.value("gray");
writer.value("red");
writer.value("white");
writer.endArray();
```

在这里，我们创建一个数组。

### `JsonReader`

`JsonReader`读取 JSON 编码值作为记号流。

`GsonStreamApiRead.java`

```java
package com.zetcode;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;

public class GsonStreamApiRead {

    public static void main(String[] args) throws IOException {

        String json_string = "{\"name\":\"chair\",\"quantity\":3}";

        try (JsonReader reader = new JsonReader(new StringReader(json_string))) {

            while (reader.hasNext()) {

                JsonToken nextToken = reader.peek();

                if (JsonToken.BEGIN_OBJECT.equals(nextToken)) {

                    reader.beginObject();

                } else if (JsonToken.NAME.equals(nextToken)) {

                    reader.nextName();

                } else if (JsonToken.STRING.equals(nextToken)) {

                    String value = reader.nextString();
                    System.out.format("%s: ", value);

                } else if (JsonToken.NUMBER.equals(nextToken)) {

                    long value = reader.nextLong();
                    System.out.println(value);

                }
            }
        }
    }
}
```

该示例使用`JsonReader`从 JSON 字符串读取数据。

```
JsonReader reader = new JsonReader(new StringReader(json_string));
```

`JsonReader`对象已创建。 它从 JSON 字符串读取。

```
while (reader.hasNext()) {
```

在`while`循环中，我们迭代流中的记号。

```
JsonToken nextToken = reader.peek();
```

我们使用`peek()`方法获得下一个标记的类型。

```
reader.beginObject();
```

`beginObject()`方法使用 JSON 流中的下一个记号，并断言它是新对象的开始。

```
reader.nextName();
```

`nextName()`方法返回下一个`JsonToken`并使用它。

```
String value = reader.nextString();
System.out.format("%s: ", value);
```

我们获取下一个字符串值并将其打印到控制台。

在本教程中，我们展示了如何通过 Gson 库使用 JSON。