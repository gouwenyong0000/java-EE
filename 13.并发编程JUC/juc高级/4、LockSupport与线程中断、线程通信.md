1、线程中断机制
========

1.1、阿里蚂蚁金服面试题
---------

如何停止、中断一个运行中的线程？

![](image/4、LockSupport与线程中断、线程通信/a24cb86c1e0b4fe8bae7dc767476caa7-1662477260506-1-1663482937453-2.png)

1.2、什么是中断？
----------

1.  **一个线程不应该由其他线程来强制中断或停止，而是应该由线程自己自行停止**。所以，`Thread.stop`, `Thread.suspend`, `Thread.resume` 都已经被废弃了。

2.  在 Java 中没有办法立即停止一条线程，然而停止线程却显得尤为重要，如取消一个耗时操作。

因此，**Java提供了一种用于停止线程的协商机制**——**中断**。



中断只是一种协作机制，Java 没有给中断增加任何语法，中断的过程完全需要程序员自己实现。

**若要中断一个线程，你需要`手动调用该线程的 interrupt() 方法`， 该方法也仅仅是将线程对象的`中断标识设成 true` ；**

接着你需要自己写代码不断地检测当前线程的标识位，如果为 true，表示别的线程要求这条线程中断，

此时究竟该做什么需要你自己写代码实现。



每个线程对象中都有一个标识，用于表示线程是否被中断；该标识位为 true 表示中断，为 false 表示未中断；

**通过调用线程对象的 `interrupt() 方法将该线程的标识位设为 true`；可以在别的线程中调用，也可以在自己的线程中调用。**



> 在 Java 中，当阻塞方法（如 `sleep`、`wait`、`join`）捕获到中断信号并抛出 `InterruptedException` 时，会自动清除（重置）中断状态，将其变回 `false`。
>
> **为什么会清除中断状态？**
>
> - **设计原则**：Java 的设计认为，当抛出 `InterruptedException` 时，代表线程已经“处理”或“响应”了该中断请求，因此需要重置标志位，避免上层代码重复响应。
> - **潜在风险**：如果在 `catch` 块中捕获了该异常却不做任何处理（吞掉异常），也没有恢复中断标志，会导致更高层的调用栈丢失中断信息，使线程无法正确停止。
>
> **正确的处理方式**
>
> 为了不丢失中断状态，通常有两种做法：
>
> 1. **继续向上抛出**：在方法签名上声明 `throws InterruptedException`，让调用者去处理。
> 2. **恢复中断状态**：如果在当前层无法抛出异常，应该在 `catch` 块中调用 `Thread.currentThread().interrupt()` 重新设置中断标志。
>
> **代码示例：**
>
> ```java
> try {
>     Thread.sleep(1000);
> } catch (InterruptedException e) {
>     // 重新恢复中断状态
>     Thread.currentThread().interrupt();
>     // 恢复后续的中断响应逻辑或退出
> }
> ```

1.3、中断的相关 API 方法
----------------

 ![](image/4、LockSupport与线程中断、线程通信/296eb7006b184b2ba101e8cf81d63458-1662477260507-2.png)



| 方法                                  | 说明                                                         |
| ------------------------------------- | ------------------------------------------------------------ |
| `public void interrupt()`             | 实例方法，<br>实例方法 `interrupt() 仅仅是设置线程的中断状态为 true`，不会停止线程 ,`发起协商` |
| `public static boolean interrupted()` | 静态方法，Thread.interrupted();  <br/>判断线程是否被中断，并清除当前中断状态[设置为`false`] 这个方法做了两件事： <br/>1 返回当前线程的中断状态 <br/>2 将当前线程的中断状态设为 false，清除线程的中断状态 <br/>这个方法有点不好理解，因为连续调用两次的结果可能不一样 。 |
| `public boolean isInterrupted()`      | 实例方法，<br/>判断当前线程是否被中断（通过检查中断标志位）  |



1.4、面试题：
---------------------

### 1.4.1 如何使用中断标识停止线程？  协商

在需要中断的线程中不断监听中断状态，

一旦发生中断，就执行相应的中断处理业务逻辑。

修改状态

停止程序的运行。。。

#### 方法一：通过一个 volatile 变量实现  

```java
package com.atguigu.juc.interrupt;
 
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
 
/**
 * @auther zzyy
 * @create 2021-03-03 18:20
 */
public class InterruptDemoCopy {
    static volatile boolean isStop = false;
 
    public static void main(String[] args) {
 
        new Thread(() -> {
            while (true) {
                if (isStop) {
                    System.out.println("-----isStop = true，程序结束。");
                    break;
                }
                System.out.println("------hello isStop");
            }
        }, "t1").start();
 
        //暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
 
        new Thread(() -> {
            isStop = true;
        }, "t2").start();
 
    }
 
}
 
返回结果：
------hello isStop
------hello isStop
------hello isStop
------hello isStop
------hello isStop
------hello isStop
-----isStop = true，程序结束。
```

代码中启动了一个线程，线程的run方法中有个死循环，内部通过exit变量的值来控制是否退出。`TimeUnit.SECONDS.sleep(3);`让主线程休眠3秒，此处为什么使用TimeUnit？TimeUnit使用更方便一些，能够很清晰的控制休眠时间，底层还是转换为Thread.sleep实现的。程序有个重点：**volatile**关键字，exit变量必须通过这个修饰，如果把这个去掉，程序无法正常退出。volatile控制了变量在多线程中的可见性。

#### 方法二：通过 AtomicBoolean 原子类

```java
public class InterruptDemoCopy {
 
    static AtomicBoolean atomicBoolean = new AtomicBoolean(false);
    public static void main(String[] args) {
 
        new Thread(() -> {
            while(true)
            {
                if(atomicBoolean.get())
                {
                    System.out.println("-----atomicBoolean.get() = true，程序结束。");
                    break;
                }
                System.out.println("------hello atomicBoolean");
            }
        },"t1").start();
 
        //暂停几秒钟线程
        try { TimeUnit.SECONDS.sleep(1); } catch (InterruptedException e) { e.printStackTrace(); }
 
        new Thread(() -> {
            atomicBoolean.set(true);
        },"t2").start();
 
    }
 
}
 
返回结果：
------hello atomicBoolean
------hello atomicBoolean
------hello atomicBoolean
------hello atomicBoolean
------hello atomicBoolean
------hello atomicBoolean
------hello atomicBoolean
-----atomicBoolean.get() = true，程序结束。
```

#### 方法三：通过 Thread 类自带的中断 api 方法实现

> 在需要中断的线程中不断监听中断状态，一旦发生中断，就执行相应的中断处理业务逻辑stop线程

![image-20220906002314794](image/4、LockSupport与线程中断、线程通信/image-20220906002314794-1662399718373-24-1662477260507-3.png)

**code**

```java
public class InterruptDemo{
    public static void main(String[] args){
        Thread t1 = new Thread(() -> {
            while (true)
            {
                if(Thread.currentThread().isInterrupted())
                {
                    System.out.println(Thread.currentThread().getName()+"\t isInterrupted()被修改为true，程序停止");
                    break;
                }
                System.out.println("t1 -----hello interrupt api");
            }
        }, "t1");
        t1.start();

        System.out.println("-----t1的默认中断标志位："+t1.isInterrupted());

        //暂停毫秒
        try { TimeUnit.MILLISECONDS.sleep(20); } catch (InterruptedException e) { e.printStackTrace(); }

        //t2向t1发出协商，将t1的中断标志位设为true希望t1停下来
        new Thread(() -> {
            t1.interrupt();
        },"t2").start();

    }
```

运行上面的程序，程序可以正常结束。线程内部有个中断标志，当调用线程的interrupt()实例方法之后，线程的中断标志会被置为true，可以通过线程的实例方法isInterrupted()获取线程的中断标志。

**源码分析：**

实例方法 `interrupt()`，没有返回值

```java
//Thread.java
public void interrupt() {
        if (this != Thread.currentThread())
            checkAccess();

        synchronized (blockerLock) {
            Interruptible b = blocker;
            if (b != null) {
                interrupt0();           // Just to set the interrupt flag----调用了interrupt0（）方法
                b.interrupt(this);
                return;
            }
        }
        interrupt0();
    }
```

```java
//Thread.java
    /* Some private helper methods */
    private native void setPriority0(int newPriority);
    private native void stop0(Object o);
    private native void suspend0();
    private native void resume0();
    private native void interrupt0();  //-------------调用了c底层
    private native void setNativeName(String name);
```



**实例方法 isInterrupted，返回布尔值**

```java
    //Thread.java
    public boolean isInterrupted() {
        return isInterrupted(false);
    }

    //Thread.java
    private native boolean isInterrupted(boolean ClearInterrupted);//也调用了c底层
```

![](image/4、LockSupport与线程中断、线程通信/7ea99f5e35164990b8bb6610faa49636-1662477260507-4.png)



### 1.4.2、当前线程的中断标识为 true，是不是就立刻停止？

#### 说明

- 具体来说，当对一个线程，调用 interrupt() 时：
  1. 如果线程处于**正常活动状态**，那么会将该线程的中断标志设置为 true，仅此而已。被设置中断标志的线程将继续正常运行，不受影响。所以， interrupt() 并不能真正的中断线程，需要被调用的线程自己进行配合才行。
  2. 如果线程处于**被阻塞状态**（例如处于 sleep, wait, join 等状态），在别的线程中调用当前线程对象的`interrupt`方法，那么线程将**立即退出被阻塞状态**（并中断状态将被清除），并抛出一个 InterruptedException 异常。
   >其他线程调用线程 A 的 interrupt() 方法，会使线程 A 返回到 RUNNABLE 状态，重置interrupt() 标记位，同时线程 A 的代码会触发 InterruptedException 异常
  3. （**中断不活动**的线程**不会**产生任何影响，看下面案例）

#### **code1 **  验证1、3 点

```java
public class InterruptDemo2
{
    public static void main(String[] args)
    {
        //实例方法interrupt()仅仅是设置线程的中断状态位设置为true，不会停止线程
        Thread t1 = new Thread(() -> {
            for (int i = 1; i <=300; i++)
            {
                System.out.println("-----: "+i);
            }
            System.out.println("t1线程调用interrupt()后的的中断标识02："+Thread.currentThread().isInterrupted());
        }, "t1");
        t1.start();

        System.out.println("t1线程默认的中断标识："+t1.isInterrupted());//false

        //暂停毫秒
        try { TimeUnit.MILLISECONDS.sleep(2); } catch (InterruptedException e) { e.printStackTrace(); }
        t1.interrupt();//true
        System.out.println("t1线程调用interrupt()后的的中断标识01："+t1.isInterrupted());//true

        try { TimeUnit.MILLISECONDS.sleep(2000); } catch (InterruptedException e) { e.printStackTrace(); }
        System.out.println("t1线程调用interrupt()后的的中断标识03："+t1.isInterrupted());//????---false中断不活动的线程不会产生任何影响。
    }
}

//before t1.interrupt()----false
//---------0
//---------1
//---------2
//---------3
//....
//---------136
//after t1.interrupt()---第1次---true    ------此处中断标志位设置为了true,但是t1仍然在运行
//---------137
//---------298
//---------299
//after t1.interrupt()---第2次----true
//after t1.interrupt()---第3次---false  //中断不活动的线程不会产生任何影响，线程结束后应该是自动变为了false
```



#### **后手案例 - 深入 wait sleep被阻塞状态 时被中断**

- 在我们基本中断程序的骨架上 + 一个 sleep 阻塞
- 中断异常 且 会导致程序无限循环.

```java
public class InterruptDemo03 {
    public static void main(String[] args) {
        Thread t1 =  new Thread(()->{
            while(true){
                if(Thread.currentThread().isInterrupted()){
                    System.out.println(Thread.currentThread().getName()+"\t"+
                            "中断标志位："+Thread.currentThread().isInterrupted()+"程序终止");
                    break;
                }
                try {
                    Thread.sleep(200);//阻塞状态时被中断，抛出InterruptedException异常，且重置interrupt()标记
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    // Thread.currentThread().interrupt();  假如加了这个，程序可以终止，只会爆异常
                }
                System.out.println("-----hello InterruptDemo03");
            }
        },"t1");
        t1.start();
        try {TimeUnit.MILLISECONDS.sleep(1);} catch (InterruptedException e) {e.printStackTrace();}
        new Thread(() -> t1.interrupt()).start();
    }
}
//爆异常了，并且程序一直在跑
//java.lang.InterruptedException: sleep interrupted
//  at java.lang.Thread.sleep(Native Method)
//-----hello InterruptDemo03
//-----hello InterruptDemo03
//-----hello InterruptDemo03
//......
//----------------------------
//---------加了Thread.currentThread().interrupt();
//java.lang.InterruptedException: sleep interrupted
// at java.lang.Thread.sleep(Native Method)
//  at com.zhang.admin.controller.InterruptDemo03.lambda$main$0(InterruptDemo03.java:15)
//  at java.lang.Thread.run(Thread.java:748)
//-----hello InterruptDemo03
//t1  中断标志位：true程序终止
```

> ② 如果线程处于被阻塞状态（例如处于 sleep, wait, join 等状态），在别的线程中调用当前线程对象的`interrupt`方法，那么线程将**立即退出被阻塞状态**（中断状态将被清除），并抛出一个 InterruptedException 异常。

分析：

```
/**
 * 1 中断标志位 默认是false
 * 2 t2 ----->t1发出了中断协商，t2调用t1.interrupt()，中断标志位true
 * 3 中断标志位true，正常情况下，程序停止，^-^
 * 4 中断标志位true，异常情况下，InterruptedException，将会把中断状态清除，并且将收到InterruptedException。中断标志位false导致无限循环。
 * 
 * 5 在catch块中，需要再次给中断标志位设置为true，2次调用停止
 */
```

![](image/4、LockSupport与线程中断、线程通信/5a1ee2b2f5df4ac5b3620bff0ebb0d27-1662477260507-5.png)

```
sleep方法抛出InterruptedException后，中断标识也被清空置为false,我们在catch没有通过th.interrupt()方法再次将中断标志设置为true，这就导致无限循环了
```

小总结：

中断只是一种协同机制，修改中断标识位仅此而已，不是立刻 stop 打断

### 1.4.3、静态方法 `Thread.interrupted()`

静态方法 `public static boolean interrupted()`：

- 静态方法，`Thread.interrupted();`判断线程是否被中断，并清除当前中断状态这个方法做了**两件事**：
  - 1 返回当前线程的中断状态
  -  2 将当前线程的中断状态设为 false（这个方法有点不好理解，因为连续调用两次的结果可能不一样。

```java
public class InterruptDemo04 {
    public static void main(String[] args) {
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println("-----1");
        Thread.currentThread().interrupt();//中断标志位设置为true
        System.out.println("-----2");
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
        System.out.println(Thread.currentThread().getName()+"\t"+Thread.interrupted());
    }
}
//main  false
//main  false
//-----1
//-----2
//main  true
//main  false
```

![](image/4、LockSupport与线程中断、线程通信/bd1c3d8597de4150a1538a249673df46-1662477260507-10.png)

#### 静态方法与实例方法的对比

 ![](image/4、LockSupport与线程中断、线程通信/5daed00e50174369988cebd80d7c9f5e-1662477260507-6.png)

 

- 看下源码，`interrupted()`对比`isInterrupted()`

  ```java
  public static boolean interrupted() {
          return currentThread().isInterrupted(true);
      }
      
  private native boolean isInterrupted(boolean ClearInterrupted);
  
  
  
  public boolean isInterrupted() {
          return isInterrupted(false);
      }
  
  private native boolean isInterrupted(boolean ClearInterrupted);
  ```

  - 他们在底层都调用了 native 方法 isInterrupted。
  - 只不过传入参数 ClearInterrupted 一个传参传了`true`，一个传了`false`。
    - 静态方法`interrupted() `中`true`表示清空当前中断状态。
    - 实例方法`isInterrupted` 则不会。



> 方法的注释也清晰的表达了 “中断状态将会根据传入的 ClearInterrupted 参数值确定是否重置”。  
>
> 所以， 
>
> + 静态方法 interrupted 将       会清除中断状态（传入的参数 ClearInterrupted 为 true）
>
> + 实例方法 isInterrupted 则不会（传入的参数 ClearInterrupted 为 false）。 



1.5、总结   Java 线程中断机制
------

## Java 线程中断

### 1. 中断机制

#### 1.1 什么是线程中断

Java 中的线程中断不是“强制杀死线程”，而是一种**协作式线程停止机制**。

一个线程可以向另一个线程发送中断请求：

```java
thread.interrupt();
```

但 `interrupt()` **不会直接终止线程**。

它表达的是：

> **请求目标线程停止当前工作。**

目标线程是否响应、什么时候响应，以及如何处理，都由目标线程自己的代码决定。

因此：

```text
interrupt()
    ≠
强制终止线程

interrupt()
    =
发送中断请求
```

------

#### 1.2 interrupt() 的本质

可以把线程中断理解成一种**线程之间传递的取消信号**：

```text
线程 A
   │
   │ interrupt()
   ↓
线程 B
   │
   │ 收到中断请求
   ↓
自己决定如何处理
   │
   ├── 检查中断状态
   ├── 响应 InterruptedException
   ├── 清理资源
   ├── 退出任务
   └── 继续执行
```

`interrupt()` 的具体表现与目标线程当前状态有关。

如果线程正在执行普通 CPU 计算：

```java
while (true) {
    doWork();
}
```

调用：

```java
thread.interrupt();
```

通常只是使线程的中断状态被设置。

线程不会自动停止。

如果线程正在执行某些可中断阻塞操作：

```java
Thread.sleep()
Object.wait()
Thread.join()
```

则可能立即结束阻塞，并抛出：

```java
InterruptedException
```

因此不能简单地认为：

```java
interrupt()
```

永远只是：

```text
interrupted = true
```

更准确的理解是：

> **`interrupt()` 向目标线程发送中断请求，目标线程根据自身状态和代码逻辑进行响应。**

------

#### 1.3 中断 ≠ 强制终止

例如：

```java
Thread t = new Thread(() -> {
    while (true) {
        // 完全不检查中断状态
    }
});

t.start();

t.interrupt();
```

即使执行：

```java
t.interrupt();
```

线程仍然可能继续运行。

因为：

```text
interrupt()
    ↓
发送请求
    ↓
线程没有响应
    ↓
继续运行
```

因此 Java 的线程取消是：

> **协作式取消，而不是强制式取消。**

------

### 2. 中断 API

Java 线程中断最重要的三个 API：

```text
interrupt()          → 发送中断请求，负责“发请求”，线程负责“决定怎么响应”。
isInterrupted()      → 查看指定线程的中断状态，不清除
Thread.interrupted() → 查看当前线程的中断状态，并清除
```

------

#### 2.1 interrupt()

```java
thread.interrupt();
```

作用：

> **向指定线程发送中断请求。**

它不会强制终止目标线程。

例如：

```java
Thread t = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {        /* 执行任务 */   }
    System.out.println("线程退出");
});
t.start();

t.interrupt();
```

执行流程：

```text
t.interrupt()
      ↓
向 t 发送中断请求
      ↓
t 的中断状态发生变化
      ↓
t 自己检查中断状态
      ↓
主动退出
```

------

#### 2.2 isInterrupted()

```java
thread.isInterrupted();
```

作用：

> **查看指定线程的中断状态。**

特点：

- 实例方法
- 可以查看指定线程
- **不会清除中断状态**

例如：

```java
t.interrupt();

System.out.println(t.isInterrupted()); // true
System.out.println(t.isInterrupted()); // true
System.out.println(t.isInterrupted()); // true
```

状态：

```text
interrupt()
    ↓
中断状态 = true
    ↓
isInterrupted()
    ↓
true
    ↓
中断状态仍然 = true
```

因此：

```text
isInterrupted()
    =
查看
+
不清除
```

------

#### 2.3 Thread.interrupted()

```java
Thread.interrupted();
```

这是一个**静态方法**。

作用：

> **检查当前线程的中断状态，并清除该状态。**

例如：

```java
Thread.currentThread().interrupt();

System.out.println(Thread.interrupted()); // true
System.out.println(Thread.interrupted()); // false
```

执行过程：

```text
当前线程
    ↓
interrupt()
    ↓
中断状态 = true
    ↓
Thread.interrupted()
    ↓
返回 true
    ↓
清除中断状态
    ↓
中断状态 = false
```

因此：

```text
Thread.interrupted()
    =
检查 + 清除
```

注意：

```java
Thread.interrupted();
```

只能检查：

> **当前线程**

不能指定其他线程。

------

#### 2.4 三个 API 对比

| 方法                     | 类型     | 检查对象 | 作用         | 是否清除 |
| ------------------------ | -------- | -------- | ------------ | -------- |
| `thread.interrupt()`     | 实例方法 | 指定线程 | 发送中断请求 | —        |
| `thread.isInterrupted()` | 实例方法 | 指定线程 | 查看中断状态 | ❌        |
| `Thread.interrupted()`   | 静态方法 | 当前线程 | 查看中断状态 | ✅        |

##### 快速记忆

```text
interrupt()
    → 发请求

isInterrupted()
    → 看，不清

Thread.interrupted()
    → 看，并清
```

------

### 3. InterruptedException

#### 3.1 什么是 InterruptedException

很多阻塞操作可以响应线程中断，例如：

```java
Thread.sleep()
Object.wait()
Thread.join()
```

当线程处于这些可中断阻塞状态时，其他线程调用：

```java
thread.interrupt();
```

阻塞操作可能结束阻塞并抛出：

```java
InterruptedException
```

典型过程：

```text
线程
  ↓
进入可中断阻塞
  ↓
sleep()
  ↓
其他线程 interrupt()
  ↓
阻塞操作响应中断
  ↓
InterruptedException
```

------

#### 3.2 sleep() 被中断

例如：

```java
try {
    Thread.sleep(10000);
} catch (InterruptedException e) {
    System.out.println("线程被中断");
}
```

另一个线程：

```java
t.interrupt();
```

可能发生：

```text
sleep(10000)
      ↓
线程阻塞
      ↓
收到 interrupt()
      ↓
sleep() 响应中断
      ↓
抛出 InterruptedException
```

------

#### 3.3 InterruptedException 与中断状态

这是线程中断中非常重要的知识点。

当某些可中断阻塞方法因为中断而抛出：

```java
InterruptedException
```

时，**中断状态通常会被清除**。

例如：

```java
try {
    Thread.sleep(10000);
} catch (InterruptedException e) {

    System.out.println(
        Thread.currentThread().isInterrupted()
    );
}
```

通常输出：

```text
false
```

完整过程：

```text
中断状态 = true
      ↓
sleep()
      ↓
检测到中断
      ↓
抛出 InterruptedException
      ↓
中断状态被清除
      ↓
进入 catch
      ↓
中断状态 = false
```

因此要区分：

```text
InterruptedException
    ↓
告诉你：阻塞操作收到了中断请求

中断状态
    ↓
线程当前保存的中断信号
```

二者有关，但不是同一个东西。

------

#### 3.4 为什么 catch 中经常重新 interrupt()？

典型代码：

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

原因：

```text
sleep()
   ↓
收到中断
   ↓
InterruptedException
   ↓
中断状态被清除
   ↓
进入 catch
   ↓
重新 interrupt()
   ↓
恢复中断状态
```

所以：

```java
Thread.currentThread().interrupt();
```

不是“重新触发一次异常”。

它的作用是：

> **重新设置中断状态，把中断信号保留下来。**

这样上层代码仍然可以通过：

```java
Thread.currentThread().isInterrupted()
```

感知到中断。

------

### 4. 中断处理

#### 4.1 处理中断的基本原则

处理 `InterruptedException` 时，核心原则：

```text
能继续抛出
    → 向上抛出

不能继续抛出
    → 恢复中断状态
```

也就是说：

> **不要随意吞掉中断。**

------

#### 4.2 方式一：恢复中断状态

如果当前方法不能继续向上抛出 `InterruptedException`：

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {

    // 恢复中断状态
    Thread.currentThread().interrupt();

    // 清理资源
    cleanup();

    return;
}
```

执行过程：

```text
InterruptedException
       ↓
恢复中断状态
       ↓
清理资源
       ↓
退出当前任务
```

这种方式特别适合：

> 当前方法无法通过异常把中断继续交给调用者。

------

#### 4.3 方式二：向上抛出

如果当前方法不负责决定任务如何取消，可以继续抛出：

```java
void task() throws InterruptedException {
    Thread.sleep(1000);
}
```

调用者处理：

```java
try {
    task();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return;
}
```

也可以继续向更上层抛出：

```java
catch (InterruptedException e) {
    throw e;
}
```

核心思想：

```text
底层方法
    ↓
InterruptedException
    ↓
上层调用者
    ↓
决定任务如何取消
```

------

#### 4.4 为什么不能吞掉 InterruptedException？

不推荐：

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    // 什么都不做
}
```

因为可能导致：

```text
其他线程
    ↓
interrupt()
    ↓
阻塞操作
    ↓
InterruptedException
    ↓
catch
    ↓
什么都不做
    ↓
中断信息丢失
```

上层代码可能因此不知道：

> 当前任务已经收到取消/停止请求。

所以通常应该：

```java
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

或者：

```java
catch (InterruptedException e) {
    throw e;
}
```

------

#### 4.5 interrupt() 与 break / return 的区别

这是实际代码中非常容易混淆的一点。

例如：

```java
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    break;
}
```

两个语句作用不同。

##### `interrupt()`

```java
Thread.currentThread().interrupt();
```

作用：

> **恢复中断状态。**

##### `break`

```java
break;
```

作用：

> **退出当前循环。**

因此：

```text
InterruptedException
       ↓
interrupt()
       → 保留中断信号

break
       → 结束当前循环
```

真正让循环退出的是：

```java
break;
```

------

### 5. 协作式取消

#### 5.1 CPU 计算任务如何响应中断

如果线程一直执行 CPU 计算：

```java
while (true) {
    doWork();
}
```

不会因为：

```java
thread.interrupt();
```

而自动停止。

应该主动检查中断状态：

```java
while (!Thread.currentThread().isInterrupted()) {
    doWork();
}
```

执行过程：

```text
interrupt()
    ↓
中断状态 = true
    ↓
isInterrupted()
    ↓
发现中断
    ↓
循环条件失败
    ↓
任务退出
```

------

#### 5.2 阻塞任务如何响应中断

如果任务执行的是可中断阻塞操作：

```java
try {
    Thread.sleep(10000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return;
}
```

执行过程：

```text
线程阻塞
    ↓
interrupt()
    ↓
阻塞操作响应
    ↓
InterruptedException
    ↓
恢复中断状态
    ↓
return
    ↓
任务退出
```

------

#### 5.3 标准优雅停止模板

这是非常典型的 Java 线程优雅停止方式：

```java
Thread worker = new Thread(() -> {

    while (!Thread.currentThread().isInterrupted()) {

        try {
            Thread.sleep(1000);

            doWork();

        } catch (InterruptedException e) {

            // sleep 响应中断后，中断状态通常已被清除
            // 因此重新设置中断状态
            Thread.currentThread().interrupt();

            // 当前任务决定退出
            break;
        }
    }

    // 释放资源
    cleanup();
});
```

启动：

```java
worker.start();
```

请求停止：

```java
worker.interrupt();
```

------

#### 5.4 标准执行流程

##### 情况一：线程正在 CPU 计算

```text
主线程
   │
   │ worker.interrupt()
   ↓
工作线程
   │
   │ 中断状态 = true
   ↓
isInterrupted()
   ↓
发现中断
   ↓
退出循环
   ↓
cleanup()
   ↓
线程结束
```

##### 情况二：线程正在 sleep

```text
主线程
   │
   │ worker.interrupt()
   ↓
工作线程
   │
   │ sleep()
   ↓
InterruptedException
   ↓
中断状态被清除
   ↓
catch
   ↓
重新 interrupt()
   ↓
break
   ↓
cleanup()
   ↓
线程结束
```

------

### 6. 面试速查

#### 6.1 高频问题

##### Q1：`interrupt()` 会杀死线程吗？

不会。

它只是：

> **向目标线程发送中断请求。**

------

##### Q2：`isInterrupted()` 会清除中断状态吗？

不会。

```java
t.isInterrupted();
```

只是：

```text
查看
+
不清除
```

------

##### Q3：`Thread.interrupted()` 会清除中断状态吗？

会。

```java
Thread.interrupted();
```

是：

```text
查看当前线程
+
清除中断状态
```

------

##### Q4：`Thread.interrupted()` 检查哪个线程？

检查：

> **当前线程。**

因为它是静态方法。

------

##### Q5：为什么 `InterruptedException` 后经常重新 `interrupt()`？

因为某些可中断阻塞操作在抛出 `InterruptedException` 时会清除中断状态。

所以：

```java
catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

可以：

> **恢复中断状态，让上层继续感知中断请求。**

------

##### Q6：为什么不能吞掉 `InterruptedException`？

因为这可能导致：

```text
中断请求
   ↓
InterruptedException
   ↓
被吞掉
   ↓
上层无法感知取消请求
```

从而破坏协作式取消机制。

------

##### Q7：线程正在死循环，调用 `interrupt()` 会停止吗？

不会。

例如：

```java
while (true) {
}
```

如果完全不检查中断状态：

```java
thread.interrupt();
```

线程仍可能继续运行。

应该：

```java
while (!Thread.currentThread().isInterrupted()) {
    doWork();
}
```

------

##### Q8：`interrupt()` 和 `InterruptedException` 是什么关系？

可以理解为：

```text
interrupt()
    ↓
发送中断请求
    ↓
如果目标线程正在某些可中断阻塞操作
    ↓
阻塞操作响应
    ↓
InterruptedException
```

但是：

> **调用 `interrupt()` 并不意味着一定会产生 `InterruptedException`。**

如果目标线程正在普通 CPU 计算，就不会因为 `interrupt()` 自动抛出这个异常。

------

#### 6.2 API 速查表

| API                      | 作用对象 | 核心作用     | 清除状态 |
| ------------------------ | -------- | ------------ | -------- |
| `thread.interrupt()`     | 指定线程 | 发送中断请求 | —        |
| `thread.isInterrupted()` | 指定线程 | 查看中断状态 | ❌        |
| `Thread.interrupted()`   | 当前线程 | 查看中断状态 | ✅        |

------

#### 6.3 中断处理速查

```text
收到 InterruptedException
          │
          ↓
当前方法能继续抛出吗？
      │           │
     能           不能
      │           │
      ↓           ↓
   throw e    interrupt()
                  ↓
              清理资源
                  ↓
             return / break
```

------

#### 6.4 最终心智模型

```text
                 Java 线程中断
                       │
                       ↓
                interrupt()
                       │
                 发送中断请求
                       │
             ┌─────────┴─────────┐
             ↓                   ↓
          CPU计算             阻塞操作
             │                   │
             ↓                   ↓
     检查中断状态          响应中断请求
             │                   │
             ↓                   ↓
    isInterrupted()       InterruptedException
             │                   │
             │             状态通常被清除
             │                   │
             └─────────┬─────────┘
                       ↓
                  正确处理中断
                       │
              ┌────────┴────────┐
              ↓                 ↓
           throw           interrupt()
              │                 │
              ↓                 ↓
           交给上层          恢复状态
                                │
                                ↓
                         break / return
                                │
                                ↓
                            cleanup()
                                │
                                ↓
                            任务结束
```

------

#### 6.5 一句话口诀

> **`interrupt()` 发请求，`isInterrupted()` 只查看，`Thread.interrupted()` 查看并清除。**

再记住：

> **中断不是杀线程，而是发出“请停止”的信号；线程必须主动响应。**

处理 `InterruptedException`：

> **能抛就抛，不能抛就恢复；不要无声吞掉中断。**

最终可以浓缩成：

```text
interrupt()
    → 发请求

isInterrupted()
    → 看，不清

Thread.interrupted()
    → 看，并清

InterruptedException
    → 阻塞操作响应中断

catch
    → 能抛就抛
    → 不能抛就恢复

优雅停止
    → interrupt()
    → 检查/响应
    → break / return
    → cleanup()
```





## 1.6 java 解惑84【Thread.interrupted()】

在下面这个程序中，一个线程试图中断自己，然后检查中断是否成功。它会打印什么呢？

```java
public class SelfInterruption {
  public static void main(String[ ] args) {
    Thread.currentThread().interrupt();
    if(Thread.interrupted()) {
      System.out.println("Interrupted: " +
          Thread.interrupted());
    } else{
      System.out.println("Not interrupted: " +
          Thread.interrupted());
    }
  }
}
```

虽然一个线程中断自己不是很常见，但这也不是没有听说过的。当一个方法捕捉到了一个InterruptedException异常，而且没有做好处理这个异常的准备时，那么这个方法通常会将该异常重新抛出（rethrow）。但是由于这是一个“被检查的异常”，所以只有在方法声明允许的情况下该方法才能够将异常重新抛出。如果不能重新抛出，该方法可以通过中断当前线程对异常“再构建”(reraise)。这种方式工作得很好，所以这个程序中的线程中断自己应该是没有任何问题的。所以，该程序应该进入if语句的第一个分支，打印出 Interrupted: true。如果你运行该程序，你会发现并不是这样。但是它也没有打印 Not interrupted: false，它打印的是 Interrupted: false。
看起来该程序好像不能确定线程是否被中断了。当然，这种看法是毫无意义的。实际上发生的事情是，Thread.interrupted方法第一次被调用的时候返回了true，并且清除了线程的中断状态，所以在if-then-else语句的分支中第2次调用该方法的时候，返回的就是false。**调用Thread.interrupted方法总是会清除当前线程的中断状态**。方法的名称没有为这种行为提供任何线索，而对于5.0版本，在相应的文档中有一句话概要地也同样具有误导性地叙述道：“测试当前的线程是否中断”[Java-API]。所以，可以理解为什么很多程序员都没有意识到Thread.interrupted方法会对线程的中断状态造成影响。
Thread类有2个方法可以查询一个线程的中断状态。另外一个方法是一个名为isInterrupted的实例方法，而它不会清除线程的中断状态。如果使用这个方法重写程序，它就会打印出我们想要的结果true：

```java
public class SelfInterruption {
  public static void main(String[ ] args) {
    Thread.currentThread().interrupt();
    if(Thread.currentThread().isInterrupted()) {
      System.out.println("Interrupted: " +
          Thread.currentThread().isInterrupted());
    }else{
      System.out.println("Not interrupted: " +
          Thread.currentThread().isInterrupted());
    }
  }
}
```

这个谜题的教训是：**不要使用Thread.interrupted方法，除非你想要清除当前线程的中断状态**。如果你只是想查询中断状态，请使用isInterrupted方法。这里给API设计者们的教训是方法的名称应该用来描述它们主要功能。根据Thread.interrupted方法的行为，它的名称应该是 clearInterruptStatus，因为相对于它对中断状态的改变，它的返回值是次要的。特别是当一个方法的名称并不完美的时候，文档是否能清楚地描述它的行为就显得非常重要了。



2、LockSupport 是什么
=================

![](image/4、LockSupport与线程中断、线程通信/d4917e5f867e4e618cccfaec5023f46d-1662399718369-3-1662477260507-8.png)

`LockSupport` 是用来创建锁和其他同步类的基本线程阻塞原语。 

**核心**就是`park()`和`unpark()`方法

- `park()`方法是**阻塞线程**

- `unpark()`方法是**解除阻塞线程**

  ![img](image/4、LockSupport与线程中断、线程通信/1ca493a6ed07446a90dafaef90d25d23-1662477260507-7.png)

  



3、线程等待唤醒机制
==========

3.1、3 种让线程等待和唤醒的方法
------------------

方式 1：使用 Object 中的 `wait()` 方法让线程等待，使用 Object 中的 `notify()` 方法唤醒线程

方式 2：使用 JUC 包中 `Condition` 的 `await()` 方法让线程等待，使用 `signal()` 方法唤醒线程

方式 3：`LockSupport` 类可以阻塞当前线程以及唤醒指定被阻塞的线程

![img](image/4、LockSupport与线程中断、线程通信/b67b91fb5ce34ba0a226d1b024ea428e-1662477260507-9.png)



3.2、`Object` 类中的 `wait` 和 `notify` 方法实现线程等待和唤醒
----------------------------------------

### 正常代码：

> 如果 synchronized 锁定的是 this，那么对应的一定是 this.wait()、this.notify()、this.notifyAll()；
>
> 如果 synchronized 锁定的是 target，那么对应的一定是 target.wait()、target.notify()、target.notifyAll() 。
>
> 而且 wait()、notify()、notifyAll() 这三个方法能够被调用的前提是已经获取了相应的互斥锁，所以我们会发现 wait()、notify()、notifyAll() 都是在 synchronized{} 内部被调用的。
>
> 如果在 synchronized{} 外部调用，或者锁定的 this，而用 target.wait() 调用的话，JVM 会抛出一个运行时异常：`java.lang.IllegalMonitorStateException`。
>
> 
>
> 当条件满足时调用 notify()，会通知等待队列（**互斥锁的等待队列**）中的线程，告诉它**条件曾经满足过**。【虚假唤醒】

```java
public class LockSupportDemoCopy {
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
        Object objectLock = new Object();
        // 同一把锁，类似资源类
        new Thread(() -> {
            synchronized (objectLock) {
                System.out.println(Thread.currentThread().getName()+"\t"+"---come in");
                try {
                    objectLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(Thread.currentThread().getName() + " \t " + " 被唤醒了 ");
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        new Thread(() -> {
            synchronized (objectLock) {
                objectLock.notify();
                System.out.println(Thread.currentThread().getName()+"\t"+"---发出通知");
            }
        }, "t2").start();
    }
 
 
}
```

![](image/4、LockSupport与线程中断、线程通信/ac05038a218c463893c24062039f59ae-1662477260507-11.png)

### 异常代码 1：wait 方法和 notify 方法，两个都去掉同步代码块,

- 说明要使用`wait`和`notify`必须加`synchronized`

```java
public class LockSupportDemoCopy {
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
        Object objectLock = new Object();
        // 同一把锁，类似资源类
        new Thread(() -> {
            System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
            try {
                objectLock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + " \t " + " 被唤醒了 ");
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        new Thread(() -> {
            objectLock.notify();
            System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
        }, "t2").start();
    }
 
 
}
```

![](image/4、LockSupport与线程中断、线程通信/0bb978c9fd4746c38d14b0e84184ac5b-1662477260507-13.png)

### 异常代码 2：将 notify 放在 wait 方法前面，程序无法执行，无法唤醒

- 说明顺序不能对换

```java
public class LockSupportDemoCopy {
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
        Object objectLock = new Object();
        // 同一把锁，类似资源类
        new Thread(() -> {
            synchronized (objectLock) {
                System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
                objectLock.notify();
                System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
 
            }
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        new Thread(() -> {
            synchronized (objectLock) {
                try {
                    objectLock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(Thread.currentThread().getName() + " \t " + " 被唤醒了 ");
        }, "t2").start();
    }
}
```

 ![](image/4、LockSupport与线程中断、线程通信/4e5e6600c0494e7486926eb90464b155-1662477260507-12.png)

### 小总结：

*   wait 和 notify 方法必须要在同步块或者方法里面，且成对出现使用   否则会出现IllegalMonitorStateException
*   先 wait 后 notify 才 OK, **顺序**



3.3、`Condition` 接口中的 `await` 后 `signal` 方法实现线程的等待和唤醒
----------------------------------------------

### 正常代码：

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        new Thread(() -> {
 
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
                condition.await();
                System.out.println(Thread.currentThread().getName() + "\t" + "---被唤醒");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
 
        new Thread(() -> {
            lock.lock();
            try {
                condition.signal();
                System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
            } finally {
                lock.unlock();
            }
        }, "t2").start();
    }
}
```

 ![](image/4、LockSupport与线程中断、线程通信/7a8de47a0cc14cd6ad57a5a0de3b17eb-1662477260508-15.png)

### 异常代码 1：去掉 lock/unlock

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        new Thread(() -> {
 
            try {
                System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
                condition.await();
                System.out.println(Thread.currentThread().getName() + "\t" + "---被唤醒");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
 
        new Thread(() -> {
            try {
                condition.signal();
                System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "t2").start();
    }
 
 
}
```

![](image/4、LockSupport与线程中断、线程通信/37c6b87e15634d8e97af81dd507b081e-1662477260507-14.png)

condition.await(); 和 condition.signal(); 都触发了 IllegalMonitorStateException 异常。 

#### 结论： 

lock、unlock 对里面才能正确调用调用 condition 中线程等待和唤醒的方法

### 异常代码 2：先 signal 后 await

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        new Thread(() -> {
 
            lock.lock();
            try {
                condition.signal();
                System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
 
            } finally {
                lock.unlock();
            }
        }, "t1").start();
        // 暂停几秒钟线程
        try {
            TimeUnit.SECONDS.sleep(3L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
 
        new Thread(() -> {
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
                condition.await();
                System.out.println(Thread.currentThread().getName() + "\t" + "---被唤醒");
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }, "t2").start();
    }
 
 
}
```

代码运行无法结束：线程无法被唤醒

![](image/4、LockSupport与线程中断、线程通信/ac7d6942c57140aea24a7b3e2ffabcad-1662477260508-16.png)

### 小总结：

*   Condtion 中的线程等待和唤醒方法之前，需要先获取锁
*   一定要先 await 后 signal，不要反了

## 3.4、`Object` 和 `Condition` 使用的限制条件 -->LockSupport 

线程先要获得并持有锁，必须在锁块 (synchronized 或 lock) 中

必须要先等待后唤醒，线程才能够被唤醒

3.5、`LockSupport` 类中的 `park` 等待和 `unpark` 唤醒
--------------------------------------

### 3.5.1、是什么

通过 park() 和 unpark(thread) 方法来实现阻塞和唤醒线程的操作

官方解释：

![](image/4、LockSupport与线程中断、线程通信/f0b2704dedc94200ad115ccefefccac4-1662477260508-17.png)

+ LockSupport 是用来创建锁和其他同步类的基本线程阻塞原语。 
+ LockSupport 类使用了一种名为 Permit（许可）的概念来做到 阻塞和唤醒线程 的功能， 每个线程都有一个许可 (permit)， 
+ permit 只有两个值 1 和零，默认是零。 
+ 可以把许可看成是一种 (0,1) 信号量（Semaphore），但与 Semaphore 不同的是，许可的累加上限是 1。 

### 3.5.2、主要方法

#### 3.5.2.1、API

![](image/4、LockSupport与线程中断、线程通信/3aba9f6792224bcab5e9446456e4be9c-1662477260508-18.png)

#### 3.5.2.2、阻塞`park()`

- `park()/park(Object blocker)`

- 调用`LockSupport.park()`时，发现它调用了`unsafe类`，并且默认传了一个 0

  ```java
  public static void park() {
          UNSAFE.park(false, 0L);
  }
  ```

![img](image/4、LockSupport与线程中断、线程通信/cdd87c9b436a435ab07a2fecec304229-1662477260508-19.png)

- permit 默认是零，所以一开始调用 park() 方法，当前线程就会阻塞，直到别的线程将当前线程的 permit 设置为 1 时，park 方法会被唤醒，然后会将 permit 再次设置为零并返回。

#### 3.5.2.3、唤醒`unpark`

- 调用`LockSupport.unpark();`时，也调用了`unsafe类`

  ```java
  public static void unpark(Thread thread) {
          if (thread != null)
              UNSAFE.unpark(thread);
      }
  ```

![img](image/4、LockSupport与线程中断、线程通信/d784a1d4a5e54c09a6a8258983506b29-1662477260508-20.png)

- 调用 `unpark(thread)` 方法后，就会将 thread 线程的许可 permit 设置成 **1**(注意多次调用 unpark 方法，不会累加，permit 值还是 1) 会自动唤醒 thread 线程，即之前阻塞中的 LockSupport.park() 方法会立即返回。

### 3.5.3、代码

#### 3.5.3.1、正常 + 无锁块要求

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        Thread t1 = new Thread(() -> {
            System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
            LockSupport.park();
            System.out.println(Thread.currentThread().getName() + "\t" + "---被唤醒");
        }, "t1");
        t1.start();
        try { TimeUnit.SECONDS.sleep(3); } catch (InterruptedException e) { e.printStackTrace(); }
        new Thread(() -> {
            LockSupport.unpark(t1);
            System.out.println(Thread.currentThread().getName()+"\t"+"---发出通知");
        },"t2").start();
    }
}
```

![](image/4、LockSupport与线程中断、线程通信/13185c79a1744eb28c352a83e314d368-1662477260508-21.png)

#### 3.5.3.2、之前错误的先唤醒后等待，LockSupport 照样支持

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        Thread t1 = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + "\t" + System.currentTimeMillis() + "\t" + "---come in");
            LockSupport.park();
            System.out.println(Thread.currentThread().getName() + "\t" + System.currentTimeMillis() + "\t" + "---被唤醒");
        }, "t1");
        t1.start();
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        new Thread(() -> {
            LockSupport.unpark(t1);
            System.out.println(Thread.currentThread().getName() + "\t" + System.currentTimeMillis() + "\t" + "---发出通知");
        }, "t2").start();
    }
}
```

![](image/4、LockSupport与线程中断、线程通信/1fc1779c0b454f05b9073b75f02cc906-1662477260508-22.png)

####  3.5.3.3、异常情况：没有考虑到 permit 上限值为 1

```java
public class LockSupportDemoCopy {
    static Lock lock = new ReentrantLock();
    static Condition condition = lock.newCondition();
 
    public static void main(String[] args)//main 方法，主线程一切程序入口
    {
 
        Thread t1 = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + "\t" + "---come in");
            LockSupport.park();//消费一个许可证
            LockSupport.park();//消费一个许可证
            System.out.println(Thread.currentThread().getName() + "\t" + "---被唤醒");
        }, "t1");
        t1.start();
 
        new Thread(() -> {
            LockSupport.unpark(t1);//发放一个许可证，许可证最多为1，不会累计
			 LockSupport.unpark(t1);
            LockSupport.unpark(t1);
            LockSupport.unpark(t1);
            System.out.println(Thread.currentThread().getName() + "\t" + "---发出通知");
        }, "t2").start();
    }
}
```

 程序运行结果：**由于 permit 的上限值为 1，所以执行两次 LockSupport.park() 操作将导致 t1 线程阻塞**

![](image/4、LockSupport与线程中断、线程通信/61ac1710aef8496a91e07ebf7ca2b24c-1662477260508-23.png)

### 小总结

+ Lock Support 是用来创建锁和其他同步类的基本线程阻塞原语。
+ Lock Support 是一个`线程阻塞工具类`， 所有的方法都是静态方法， 可以让线程在任意位置阻塞， 阻塞之后也有对应的唤醒方法。归根结底， Lock Support 调用的 Unsafe 中的 native 代码。
+ Lock Support 提供`park()` 和`unpark() `方法实现**阻塞线程**和**解除线程阻塞**的过程
+ Lock Support 和每个使用它的线程都有一个许可 (permit) 关联。
+ 每个线程都有一个相关的 permit， permit 最多只有一个， 重复调用 un park 也不会积累凭证。



> **形象的理解**
> 线程阻塞需要消耗凭证 (permit) ， 这个凭证最多只有 **1** 个。
>
> + 当调用park()方法时
>
>   + 如果有凭证，则会直接消耗掉这个凭证然后正常退出；
>
>   + 如果无凭证，就必须阻塞等待凭证可用；
>
> + 而unpark() 则相反， 它会增加一个凭证， 但凭证最多只能有 1个， 累加无效。



#### 面试题

- **为什么可以突破 wait/notify 的原有调用顺序?**
  因为 unpark 获得了一个凭证， 之后再调用 park 方法， 就可以名正言顺的凭证消费， 故不会阻塞。
  先发放了凭证后续可以畅通无阻。
- **为什么唤醒两次后阻塞两次，但最终结果还会阻塞线程?**
  因为凭证的数量最多为 1， 连续调用两次 unpark 和调用一次 unpark 效果一样， 只会增加一个凭证；
  而调用两次 park 却需要消费两个凭证， 证不够， 不能放行。