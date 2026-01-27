# ConcurrentHashMap并发原理和误区

---

## 1. 整体并发设计思想

### 1.1 三大核心

1. **CAS（无锁）**：控制空桶初始化、size 统计、扩容协作
2. **synchronized（锁桶）**：只锁当前桶头 Node
3. **红黑树化**：链表过长转 TreeBin，降低查找复杂度

### 1.2 与 JDK7 Segment 对比

| 版本 | 锁模型 | 粒度 | 并发度 |
|------|--------|------|--------|
| JDK7 | Segment ReentrantLock | 段级 | 固定（16） |
| JDK8 | CAS + synchronized Node | 桶级 | 理论无限 |

---

## 2. 关键数据结构

```java
static class Node<K,V> {
    final int hash;
    final K key;
    volatile V val;
    volatile Node<K,V> next;
}

static final class TreeNode<K,V> extends Node<K,V> { ... }

static final class TreeBin<K,V> extends Node<K,V> { // 红黑树根
    volatile TreeNode<K,V> root;
    volatile TreeNode<K,V> first;
    volatile Thread waiter;
}
```

---

## 3. put 流程源码执行图（简化）

```
putVal(key, value)
   |
   v
spread(hash)  // 扰动运算
   |
   v
(tab == null) ? initTable() : 定位桶 index
   |
   v
CAS 放入空桶成功？ —— yes → 结束
   |
   no
   v
synchronized(桶头 Node)
   |
   +--> 链表遍历插入
   |
   +--> TreeBin 红黑树插入
   |
   v
binCount >= TREEIFY_THRESHOLD ? treeifyBin()
   |
   v
addCount(1) → 是否触发扩容
```

---

## 4. get 流程（无锁）

```
定位桶 → 直接遍历链表 / 红黑树
全程 volatile 读，无加锁
```

---

## 5. 扩容机制（并发协作式）

### 5.1 触发条件

```java
size > capacity * loadFactor
```

### 5.2 扩容流程

```
线程 A 触发扩容
   |
   v
创建 newTable (2 倍)
   |
   v
ForwardingNode 标记已迁移桶
   |
   v
其他线程发现 ForwardingNode → helpTransfer()
   |
   v
多线程并行迁移不同区间桶
```

---

## 6. 原子 API 原理

### 6.1 computeIfAbsent

```java
synchronized (bin) {
    if (node == null)
        node = new Node(key, mappingFunction.apply(key));
}
```

### 6.2 merge

```java
synchronized (bin) {
    val = remappingFunction.apply(oldVal, newVal);
}
```

保证：**查 + 改 + 写 在同一桶锁内完成**。

---

## 7. 高并发计数器最佳实践

```java
ConcurrentHashMap<String, LongAdder> map = new ConcurrentHashMap<>();
map.computeIfAbsent(key, k -> new LongAdder()).increment();
```

原因：
- LongAdder 内部分段累加
- 减少 CAS 冲突

---

## 8. 经典面试题与标准答案

### Q1：为什么 key/value 不能为 null？

A：并发场景下无法区分：
- get 返回 null 是不存在？
- 还是 value 本身为 null？

---

### Q2：size() 是否准确？

A：不是强一致，只是近似值，使用 baseCount + CounterCell 统计。

---

### Q3：为什么不用 ReentrantLock？

A：synchronized 在 JDK8 已优化：
- 偏向锁
- 轻量级锁
- 锁消除
比 Lock 结构更轻量。

---

### Q4：红黑树何时触发？

| 条件 | 值 |
|------|----|
| 链表长度 | >= 8 |
| 数组容量 | >= 64 |

---

### Q5：扩容时还能读写吗？

A：可以。ForwardingNode 保证：
- 读走新表
- 写协助迁移

---

### Q6：ConcurrentHashMap 和 Collections.synchronizedMap 区别？

| 维度 | CHM | synchronizedMap |
|------|-----|------------------|
| 锁粒度 | 桶级 | 整表 |
| 并发性能 | 高 | 低 |
| 扩容 | 并行迁移 | 阻塞 |

---

## 9. 面试一句话总结模板

> ConcurrentHashMap 在 JDK8 中基于 CAS + synchronized 实现桶级锁，读操作无锁，写操作只锁冲突桶，扩容采用多线程协作迁移，计数使用 LongAdder 分段累加，迭代器为弱一致性，不支持 null key/value，复合操作必须使用 compute / merge 等原子方法。

---

## 10. 常见误区



没问题，这就为你补上具体的代码示例。通过对比“错误写法”和“正确写法”，能更直观地看到如何在实际编码中避开那些坑。

### 🛑 误区一：使用 `size()` 判断空或精确计数

#### 错误示例

```java
// 危险：在高并发场景下，size() 是一个估算值，且在判断和使用之间可能已经变化
if (concurrentHashMap.size() == 0) {
    System.out.println("容器为空");
}

// 危险：如果需要精确监控，size() 可能不准
int currentSize = concurrentHashMap.size(); 
```

#### 正确示例

```java
// 推荐：使用 isEmpty() 来判断空状态，这是原子且准确的
if (concurrentHashMap.isEmpty()) {
    System.out.println("容器为空");
}

// 推荐：如果需要精确计数，使用 LongAdder (高性能并发计数器)
private final LongAdder counter = new LongAdder();

// 增加
counter.increment();

// 获取精确值
long preciseCount = counter.sum();
```

### ⚔️ 误区二：复合操作非原子性

#### 错误示例

```java
// 危险：经典的 "检查再插入" 竞态条件
// 线程 A 和 线程 B 可能同时通过 containsKey 检查，然后都执行 put，导致覆盖或重复初始化
if (!concurrentHashMap.containsKey("key")) {
    // 这里可能有耗时操作
    concurrentHashMap.put("key", expensiveObjectCreation());
}
```

#### 正确示例

```java
// 推荐：使用 putIfAbsent，它是原子的
// 如果键不存在则放入，返回之前的值（如果之前存在则返回旧值，不存在返回 null）
Object oldValue = concurrentHashMap.putIfAbsent("key", expensiveObjectCreation());

// 推荐：使用 computeIfAbsent (更优雅，Lambda 内部是线程安全的)
// 只有当键不存在时，才会执行后面的 Lambda 表达式
concurrentHashMap.computeIfAbsent("key", k -> {
    // 这里的代码在 map 的锁保护下执行，不用担心并发重复初始化
    return expensiveObjectCreation(); 
});
```

### 🔍 误区三：遍历与修改的陷阱

#### 错误示例

```java
// 危险：虽然不会抛出 ConcurrentModificationException，
// 但可能会出现 "漏删" 或者遍历到 null 值的情况，逻辑难以控制
for (String key : concurrentHashMap.keySet()) {
    if (someCondition(key)) {
        // 这里的 remove 可能导致当前迭代行为不稳定
        concurrentHashMap.remove(key); 
    }
}
```

#### 正确示例

```java
// 推荐方案 1：收集后删除 (逻辑清晰，适合删除量少的情况)
List<String> keysToRemove = new ArrayList<>();
concurrentHashMap.forEach((key, value) -> {
    if (someCondition(key)) {
        keysToRemove.add(key);
    }
});
// 统一删除
keysToRemove.forEach(concurrentHashMap::remove);

// 推荐方案 2：使用 JDK8 的 forEach 配合原子操作 (更高效)
// 这里的操作是在 map 内部的分段锁下进行的
concurrentHashMap.forEach(1, (key, value) -> {
    if (someCondition(key)) {
        // 在遍历回调中删除，由 ConcurrentHashMap 内部处理并发
        concurrentHashMap.remove(key); 
    }
});

// 推荐方案 3：使用 EntryIterator (如果必须用传统 for 循环风格)
// 注意：这种方式下，如果 entry.getValue() 在遍历过程中被其他线程删除，可能会返回 null
// 所以必须判空
for (Iterator<Map.Entry<String, Object>> it = concurrentHashMap.entrySet().iterator(); it.hasNext(); ) {
    Map.Entry<String, Object> entry = it.next();
    Object value = entry.getValue(); // 弱一致性：可能看到旧值或 null
    if (value != null && someCondition(entry.getKey())) {
        it.remove(); // 使用迭代器自带的 remove
    }
}
```

### 🚀 误区四：初始容量设置不当

#### 错误示例

```java
// 危险：默认初始容量是 16
// 如果预计要存 10000 个元素，这会导致频繁的扩容（resize），非常消耗性能
ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
```

#### 正确示例

```java
// 推荐：预估容量并计算
// 公式：初始容量 = 预计元素总数 / 负载因子(0.75) + 1
int expectedSize = 10000;
int initialCapacity = (int) (expectedSize / 0.75f) + 1; // 结果约为 13334

// 这样可以极大减少扩容次数
ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>(initialCapacity);
```

### 📦 误区五：Value 对象非线程安全

#### 错误示例

```java
// 危险：虽然 map 是线程安全的，但 ArrayList 不是
ConcurrentHashMap<String, List<String>> map = new ConcurrentHashMap<>();

// 多个线程同时执行下面的操作会导致 ArrayList 被破坏
List<String> list = map.get("key");
if (list == null) {
    list = new ArrayList<>(); // 非线程安全
    map.put("key", list);
}
list.add("item"); // 危险操作！
```

#### 正确示例

```java
// 推荐方案 1：使用线程安全的集合
List<String> list = map.computeIfAbsent("key", k -> new CopyOnWriteArrayList<>());
list.add("item"); // 安全

// 推荐方案 2：使用 compute 方法，将修改逻辑包裹在原子操作中
map.compute("key", (key, currentValue) -> {
    if (currentValue == null) {
        currentValue = new ArrayList<>();
    }
    currentValue.add("item"); // 在锁内操作，安全
    return currentValue;
});
```



## 经典例题

这个例子非常精准，直接切中了并发编程中最典型的“竞态条件”要害。这段代码不仅展示了错误的逻辑，还给出了一种利用底层原子指令解决问题的思路。我们可以深入剖析一下这两段代码的差异，以及为什么第二种写法能确保数据的一致性。

### ❌ 错误用法深度解析

```java
static void add(Map<String, Integer> map, String, key) {
    if (map.containsKey(key)) {
        // 陷阱1: 在 contains 和 get 之间，key 可能被其他线程删除 (虽然概率低，但存在)
        // 陷阱2: 在 get 和 put 之间，其他线程可能已经修改了值，导致覆盖
        map.put(key, map.get(key) + 1);
    } else {
        map.put(key, 1);
    }
}
```

**问题剖析：**
这段代码包含了**两次**非原子的复合操作风险：

1. **检查与插入的间隙：** 线程 A 执行完 `containsKey` 发现为 true，准备进入 if 分支。此时线程 B 执行 `remove` 删除了该 key。线程 A 继续执行 `map.get(key)`，结果返回 `null`，导致 `NullPointerException` 或逻辑错误。
2. **读取-修改-写入的间隙（丢失更新）：** 这是最常见的情况。线程 A 和线程 B 同时读取到值为 10。线程 A 计算 10+1=11 并写回。线程 B 也计算 10+1=11 并写回。最终结果是 11，而不是期望的 12。

### ✅ 正确用法深度解析

```java
static void add(Map<String, Integer> map, String key) {
    while (true) {
        // 1. 尝试放入默认值 1，如果 key 不存在。返回旧值（如果存在的话）。
        var oldValue = map.putIfAbsent(key, 1);
        
        // 2. 判断逻辑：
        //    - 如果 oldValue == null：说明我是第一个线程，成功放入了 1。任务完成，退出循环。
        //    - 如果 oldValue != null：说明 key 原本存在，值为 oldValue。
        //      此时我们需要做 oldValue + 1 的操作。
        if (oldValue == null || map.replace(key, oldValue, oldValue + 1)) {
            break;
        }
        // 3. 循环重试：
        //    如果 replace 失败，说明在 putIfAbsent 之后，replace 之前，
        //    有其他线程修改了该 key 的值（导致当前值 != oldValue）。
        //    此时循环继续，再次尝试 putIfAbsent（其实通常 key 已存在，会直接拿到最新值），
        //    然后再次尝试 CAS 更新。
    }
}
```

**核心机制：**
这段代码利用了 `ConcurrentHashMap` 的两个核心原子能力：

1. **`putIfAbsent`：** 保证只有 key 不存在时才会放入，解决了“初始化”的竞态条件。
2. **`replace(K, V expect, V update)`：** 这是一个典型的 **CAS 操作**。它只有在当前值等于 `expect` 时，才会将其更新为 `update`。如果返回 `false`，说明值已经被其他线程修改，我们需要重试。

### 💡 更优雅的替代方案

虽然你给出的 `while + putIfAbsent + replace` 写法在逻辑上是完全正确的，且性能很高（避免了锁），但在业务代码中，为了可读性，我们通常推荐使用 JDK 8 提供的更高阶的原子方法：

#### 方案一：使用 `compute`

这是最简洁、最不容易出错的写法，内部已经封装了循环和 CAS 逻辑。

```java
static void add(Map<String, Integer> map, String key) {
    map.compute(key, (k, oldValue) -> oldValue == null ? 1 : oldValue + 1);
}
```

#### 方案二：使用 `merge`

针对“累加”这种场景，`merge` 更具语义化。

```java
static void add(Map<String, Integer> map, String key) {
    map.merge(key, 1, Integer::sum);
}
```

### 总结对比

| 方案                    | 核心机制                            | 优点                             | 缺点                                | 推荐场景                 |
| ----------------------- | ----------------------------------- | -------------------------------- | ----------------------------------- | ------------------------ |
| **错误写法**            | `contains` + `get` + `put`          | 逻辑直观                         | **线程不安全**，存在竞态条件        | **绝对禁止**             |
| **手动 CAS 循环**       | `putIfAbsent` + `replace` + `while` | 性能极高，无锁                   | 代码复杂，容易写错                  | 对性能极致要求的底层框架 |
| **`compute` / `merge`** | 内部封装循环                        | **代码简洁**，逻辑清晰，线程安全 | 相比纯 CAS 略微有一丢丢函数调用开销 | **绝大多数业务场景**     |

