# 协议扩展说明

## 1. Protocol 接口

协议只关心两个方向：

```text
Command -> bytes
bytes   -> Response
```

```java
public interface Protocol {
    ProtocolEncoder newEncoder();
    ProtocolDecoder newDecoder();
}
```

## 2. 新增协议

建议实现：

```java
public final class MyProtocol implements Protocol {
    @Override
    public ProtocolEncoder newEncoder() { ... }

    @Override
    public ProtocolDecoder newDecoder() { ... }
}
```

Decoder 必须支持：

- fragmentation
- sticky packet
- multiple frames
- malformed frame resynchronization
- maximum frame length

## 3. 不要在 Protocol 中做业务

不要：

```java
if (command.equals("START")) { ... }
```

协议层只处理 frame。

业务层负责：

```text
Why send?
What does response mean?
What should happen after response?
```

## 4. Checksum 命名

如果算法是：

```java
checksum ^= value;
```

它是 XOR checksum，不应写成 CRC-8。

真正 CRC-8 需要明确 polynomial、initial、refin、refout、xorout 等参数。
