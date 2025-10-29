# HashMap

## Java 中 HashMap 的技巧

------

### 🔹1. 使用层面的技巧

1. **合理设置初始容量**

   - `new HashMap<>(initialCapacity, loadFactor)`
   - 如果你大概知道要放多少元素，提前指定容量，避免频繁扩容和 rehash。
   - 公式：`容量 = 预估元素数 / 负载因子 + 1`，常用负载因子是 `0.75f`。

   > **初始化容量公式**
   >
   > ```java
   > int capacity = (int)(expectedSize / 0.75f) + 1;//通过预期容量计算需要容量
   > Map<String, Integer> map = new HashMap<>(capacity);
   > ```
   >
   > ✅ 减少扩容次数
   > ⚠️ 容量始终是 2 的幂次方（源码会自动调整）

---

2. **避免键值为可变对象**

- HashMap 的 key 依赖于 `hashCode()` 和 `equals()`，如果 key 是可变的对象，放入后修改内容，可能导致无法取出。
- 常见坑：用 `List`、`Set` 之类当 key，且内容被修改。

> **避免使用可变对象作为 Key**，如下使用list作为key，添加元素后list的hash值变化
>
> ```java
> List<String> list = new ArrayList<>();
> map.put(list, "test");
> list.add("changed"); // 取不出来了
> ```
>
> ✅ 使用不可变对象（`String`、`Integer`、自定义不可变类）

---

3. 高效的操作方法

| 方法               | 场景                | 示例                                                     |
| ------------------ | ------------------- | -------------------------------------------------------- |
| `computeIfAbsent`  | 初始化 value        | `map.computeIfAbsent(k, x -> new ArrayList<>()).add(v);` |
| `computeIfPresent` | 仅当 key 存在时更新 | `map.computeIfPresent(k, (kk,vv) -> vv+1);`              |
| `merge`            | 合并值（计数）      | `map.merge("apple", 1, Integer::sum);`                   |

---

4. **遍历方式选对**

- **遍历最高效方式**

  ```java
  // 需要 key 和 value  推荐 `entrySet` 遍历效率最高。
  for (Map.Entry<String, Integer> e : map.entrySet()) {
      System.out.println(e.getKey() + " = " + e.getValue());
  }
  ```

- **只要 key 或 value**

  ```java
  //只要 key
  for (String k : map.keySet()) {}
  //只要 value
  for (Integer v : map.values()) {}
  ```



------

### 🔹2. 性能优化技巧

1. **避免过度装箱拆箱**
   - 如果 key 或 value 是 `int` / `long` / `double`，考虑用 `Int2ObjectMap` (fastutil) 或 `Trove` 等库。
2. **高并发环境不要直接用 HashMap**
   - HashMap 在多线程下可能死循环【jdk7】、数据丢失【jdk8】。
   - 用 `ConcurrentHashMap` 替代，或用 `Collections.synchronizedMap(map)` 包装。
3. **减少 hash 冲突**
   - 设计合理的 `hashCode()`，避免所有 key 落在同一个桶里。
   - JDK 8 之后，如果某个桶内链表长度超过 **8**，会转为红黑树，提高查询效率。
4. **迭代过程中避免修改**
   - 会抛 `ConcurrentModificationException`。
   - 解决方法：用 `Iterator` 的 `remove()`，或用 `ConcurrentHashMap`。

------

### 🔹3. 源码与实现层面的技巧





1. **扩容机制**

   - HashMap 的容量n总是 **2 的幂次方**（2, 4, 8, 16 ...），这样 `(n - 1) & hash` 代替取余操作，高效定位桶。
   - 扩容时新容量是原来的 2 倍。
   - 触发条件：`size > capacity * loadFactor` (默认 0.75)

2. **扰动函数 (hash spreading)**

   - JDK 8 中 `hash(key) ^ (hash >>> 16)`，减少低位 hash 冲突。

   - > ```java
     > int h = key.hashCode();
     > h = h ^ (h >>> 16); // 扰动函数
     > ```

3. **树化 (Treeify)** 链表 → 红黑树

   - 桶内链表长度 > 8 且容量 ≥ 64 时，链表会转为红黑树。
   - 如果链表长度降到 6 以下，会退化为链表。

4. **Fail-Fast 机制**

   - 迭代时如果结构被修改（非 `iterator.remove`），会抛出异常。

     > ```java
     > for (String k : map.keySet()) {
     >     map.put("newKey", 100); // ❌ ConcurrentModificationException
     > }
     > ```
     >
     > ✅ 解决方案：
     >
     > ```java
     > Iterator<String> it = map.keySet().iterator();
     > while (it.hasNext()) {
     >     it.remove(); // 安全删除
     > }
     > ```
     >
     > 

------

### 🔹4. 常见实用技巧场景

- **频率统计**（单词出现次数）：

  ```java
  map.merge(word, 1, Integer::sum);
  ```

- **一键多值存储**（类似 Multimap）：

  ```java
  map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  ```

- **LRU 缓存替代方案**（LinkedHashMap 实现）：

  ```java
  new LinkedHashMap<K,V>(16, 0.75f, true) {
      protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
          return size() > 100;
      }
  };
  ```



------

## 🔹 HashMap 桶定位与扩容优化原理

------

### 1. 桶定位的优化原理

在 JDK 8 中，HashMap 使用 **扰动函数** + **位运算** 来定位桶的位置：

```java
// hash 计算
int h = key.hashCode();
h = h ^ (h >>> 16); // 扰动函数

// 桶索引计算
int index = (n - 1) & h; // n 是数组长度（2 的幂）
```

#### 🚀 优化点：

1. **扰动函数（hash spreading）**
   - 避免低位分布不均匀，减少 hash 冲突。
   - `h ^ (h >>> 16)` 等价于把高 16 位混入低 16 位。
2. **位运算代替取模**
   - `(n - 1) & h` 比 `h % n` 更快，因为 `n` 始终是 2 的幂。
   - 示例：如果 `n = 16` (二进制 `10000`)，`n - 1 = 1111`，相当于只取 hash 的后 4 位。

------

### 2. 扩容机制与迁移优化

#### 🚩 扩容条件

- 当 `size > capacity * loadFactor` 时，扩容为原来的 2 倍。

#### 🚩 扩容迁移规则

在 **JDK 7 之前**，HashMap 扩容时会 **重新计算 hash → 定位桶 → 插入**，成本高。

**JDK 8 优化：不用重新计算 hash，只看 hash 的新位**：

- 新容量 = `oldCapacity * 2`
- 桶位置只可能有 **两个位置**：
  1. **原索引位置 (index)**
  2. **原索引 + oldCapacity (index + oldCap)**

#### ✅ 原理：

因为新的桶数量是旧桶的 **2 倍**，所以 `(n - 1) & hash` 的结果只取决于 **多出来的那一位**（高 1 位）：

- 如果该位是 `0` → 节点位置不变
- 如果该位是 `1` → 节点移动到 `index + oldCapacity`

------

### 3. 示例图解

假设 `oldCapacity = 16 (10000b)`，扩容到 `32 (100000b)`：

- 旧桶索引计算：

  ```
  index = hash & (16 - 1) = hash & 1111b
  ```

- 新桶索引计算：

  ```
  newIndex = hash & (32 - 1) = hash & 11111b
  ```

差别在于 **第 5 位（二进制第 16 的位置）**：

- 如果这一位是 `0` → index 不变
- 如果这一位是 `1` → index = oldIndex + 16

------

#### 🟢 举例

假设一个元素的 hash 值：

```
hash = 100101b (37)
```

- 旧容量 `16`：

  ```
  index = hash & 1111b = 0101b = 5
  ```

- 新容量 `32`：

  ```
  newIndex = hash & 11111b = 100101b & 11111b = 100101b (37) = 5 + 16 = 21
  ```

➡️ 所以该元素在扩容后从桶 `5` 移动到了桶 `21`。

------

### 4. 优化效果

- **JDK 7 及之前**：迁移时每个元素都要重新 hash → 定位 → 插入（开销大）
- **JDK 8**：只需检查 hash 的某一位，决定留在原桶或移动到 `oldIndex + oldCap`，极大优化了扩容性能。

------

## 📑 HashMap 高频面试题清单

------

### 🔹1. 基础原理类

1. **HashMap 的底层实现？**
   - 数组 + 链表 + 红黑树（JDK 8 之后）
   - 冲突时先放链表，链表长度 > 8 且桶容量 ≥ 64 时转为红黑树。
2. **HashMap 的默认初始容量和负载因子？**
   - 初始容量：16
   - 负载因子：0.75
   - 扩容条件：`size > capacity * loadFactor`
3. **为什么 HashMap 的容量总是 2 的幂？**
   - 便于使用 `(n - 1) & hash` 代替 `% n`，提升性能。
   - 这样能保证 hash 均匀分布在桶中。

------

### 🔹2. Hash 冲突类

1. **HashMap 如何解决 hash 冲突？**
   - JDK 7 及以前：数组 + 链表
   - JDK 8：链表 + 红黑树（链表长度 > 8 → 树化；< 6 → 退化回链表）
2. **为什么要用红黑树？**
   - 链表查询 O(n)，极端情况下退化成链表 → 性能差
   - 红黑树查询 O(log n)，提高效率。

------

### 🔹3. 扩容与定位类

1. **HashMap 的扩容机制？**
   - 当 `size > capacity * loadFactor` → 扩容为原来的 2 倍。
   - JDK 8 迁移时 **不重新计算 hash**，只根据新的一位判断：
     - `0` → 留在原桶
     - `1` → 移到 `oldIndex + oldCap`
2. **HashMap 的 key 定位桶的过程？**
   - 计算 `hashCode` → 扰动函数 `h ^ (h >>> 16)` → `(n - 1) & hash` 定位桶。

------

### 🔹4. 并发问题类

1. **为什么 HashMap 线程不安全？**
   - 多线程 put 时可能导致：
     - 数据丢失（覆盖）
     - 扩容时链表形成环，造成死循环（JDK 7 常见问题）
2. **线程安全的替代方案？**
   - `ConcurrentHashMap`（推荐）
   - `Collections.synchronizedMap(new HashMap<>())`（性能差）
3. **ConcurrentHashMap 和 HashMap 的区别？**

- HashMap：数组 + 链表/红黑树，线程不安全
- ConcurrentHashMap：分段锁（JDK 7），CAS + synchronized（JDK 8），线程安全

------

### 🔹5. 源码机制类

1. **为什么要有扰动函数 (hash spreading)？**

- 避免高位 hash 失效，只用低位导致冲突集中。
- `h ^ (h >>> 16)` 能让高 16 位参与运算。

1. **Fail-Fast 机制是什么？**

- 迭代 HashMap 时，如果结构被修改（非迭代器的 remove 方法），会抛 `ConcurrentModificationException`。
- 通过 `modCount` 监控结构变化实现。

1. **为什么链表树化阈值是 8？**

- 根据泊松分布分析，在负载因子 0.75 下，链表长度超过 8 的概率非常低。
- 超过 8 时，性能风险大于转换成本，所以转红黑树。

------

### 🔹6. 实战类

1. **如何实现一个 LRU 缓存？**

- 用 `LinkedHashMap`，开启 `accessOrder=true`，重写 `removeEldestEntry` 方法。

1. **HashMap 和 Hashtable 的区别？**

- Hashtable：线程安全（synchronized），性能差，不允许 null key/value。
- HashMap：线程不安全，性能好，允许一个 null key 和多个 null value。

1. **HashMap 和 TreeMap 的区别？**

- HashMap：无序，基于 hash。
- TreeMap：有序，基于红黑树，按照 key 的自然顺序或比较器排序。

------

✅ 总结：

- 初级面试：HashMap 底层原理 + 默认参数 + Hash 冲突
- 中级面试：扩容机制 + 扰动函数 + Fail-Fast
- 高级面试：线程安全问题 + JDK 7 死循环问题 + 红黑树优化原理



------

# ConcurrentHashMap JDK1.7 vs JDK1.8

## 1. 数据结构差异

### JDK 1.7

- **分段锁机制 (Segment + HashEntry 数组)**
  - `ConcurrentHashMap` 内部分为 **若干 Segment**（默认 16）。
  - 每个 Segment 类似于一个小的 `HashMap`，内部是 **数组 + 链表**。
  - 并发控制在 Segment 上，加锁粒度较粗。
  - `size()` 操作需要遍历所有 Segment 并加锁，开销大。

### JDK 1.8

- **取消 Segment，采用 Node 数组 + 链表 + 红黑树**
  - 与 `HashMap 1.8` 类似，桶冲突时链表长度超过 **8** 转换为红黑树。
  - 并发控制方式改为 **CAS + synchronized (锁粒度在桶/Node 级别)**，大大减少锁竞争。
  - `size()` 通过 `baseCount + counterCells`（类似 LongAdder）进行统计，效率更高。

------

## 2. 加锁机制差异

### JDK 1.7

- **Segment 锁**
  - 基于 **ReentrantLock** 实现。
  - 同一时间只能有一个线程访问某个 Segment。
  - 多线程访问不同 Segment 时能并发执行，但 Segment 数量有限（默认 16），高并发下会有锁竞争。

### JDK 1.8

- **CAS + synchronized**
  - 插入数据时，先用 **CAS** 尝试写入空桶。
  - 如果失败或需要链表操作，则使用 `synchronized` 锁定 **桶的头结点**，不是整个 Segment。
  - 锁粒度更小，冲突概率更低，性能更好。

------

## 3. 扩容机制

### JDK 1.7

- **分段扩容**
  - 每个 Segment 独立扩容。
  - 扩容时会重新计算 hash，迁移数据到新数组。
  - 容易出现 “扩容风暴” + **rehash 开销大**。

### JDK 1.8

- **全表扩容 + 多线程协助迁移**
  - 采用类似 `HashMap` 的扩容方式。
  - 扩容时多个线程可同时参与数据迁移，减轻单线程扩容压力。
  - 利用 `ForwardingNode` 标记已经迁移的桶。

------

## 4. Null Key / Null Value

- JDK1.7 和 JDK1.8 都 **不允许 key 或 value 为 null**
  - 因为无法区分是 key 本身为 null，还是并发下查不到值。

------

## 📌 高频面试题

#### Q1: JDK1.7 和 JDK1.8 的 `ConcurrentHashMap` 的最大区别是什么？

- JDK1.7 使用 **Segment 分段锁**，锁粒度大；
- JDK1.8 使用 **CAS + synchronized 锁桶**，锁粒度小，性能更优。

------

#### Q2: 为什么 JDK1.8 抛弃了 Segment？

- Segment 粒度较大（默认 16 个），高并发下仍会产生竞争。
- CAS + synchronized 在 JDK1.6 之后效率更高，锁优化（偏向锁、轻量级锁）使 synchronized 不再是性能瓶颈。

------

#### Q3: JDK1.8 中为什么引入红黑树？

- 链表查询时间复杂度 O(n)，极端情况下会退化为 O(n)。
- 红黑树查询时间复杂度 O(log n)，大大提升查询性能。

------

#### Q4: `size()` 在 JDK1.7 和 JDK1.8 的实现有何不同？

- JDK1.7：需要遍历所有 Segment 并加锁，统计所有元素数量。
- JDK1.8：使用 **baseCount + CounterCell (类似 LongAdder)**，通过 CAS 更新计数，更高效。

------

#### Q5: `ConcurrentHashMap` 为什么不能存储 null key 和 null value？

- null key：难以区分 `map.get(null)` 返回 null 是 key 不存在还是 key 对应的 value 为 null。
- null value：同样难以区分 key 对应的值是否为 null，还是没有该 key。
- 在并发场景下，模糊不清会导致数据一致性问题。

------

#### Q6: 扩容时 JDK1.8 如何避免阻塞所有线程？

- 采用 **分段迁移机制**：多个线程可以同时迁移不同桶的数据；
- 使用 `ForwardingNode` 占位，标记已迁移完成的桶，防止重复迁移。

------

⚡总结一句话：

> **JDK1.7：Segment + ReentrantLock + 链表，扩容低效**
>  **JDK1.8：CAS + synchronized + 链表/红黑树，支持多线程扩容，性能更优**

------

