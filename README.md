# TCP 接口（network-socket）

面向 Minecraft **26.3 / Fabric** 的红石方块 TCP 接口模组。方块外观和放置方向参考侦测器：正面检测相邻方块的红石信号，背面输出服务端返回的红石强度；每个方块可通过 `name` 关联到一张随地图保存的连接配置表。

> 协议仅建议在内网/测试环境使用。AES-256-GCM 只能防篡改，不能防重放；本模组按需求在加密明文内加入 `requestid` 和 `timestamp` 作为应用层补充措施，不能替代网络安全措施。

## 获取方块

- 创造模式物品栏中的 **TCP接口** 页签
- 指令：`/give @s network-socket:tcp_interface`
- 没有合成表，也不在生存模式自然获取

## 使用

1. 放置方块，右键方块打开方块配置，填写 `name` 并保存（`name` 默认空）。
2. 按快捷键 **K**（可在“控制”中修改）打开配置界面：
   - **全局网络**（UI1）：`address`、`port`、`token`、`maxConnectCount`、`keepalivetime`、`timeout`
   - **连接配置表**（UI2）：`name` / `status` 表，逐行“开启连接”“关闭连接”
3. 先保存 UI1，再把服务端地址/端口/token 对齐，然后在 UI2 对某个 `name` 开启连接。
4. 方块正面的相邻方块红石信号发生变化时，会发送 `request`；服务端返回 `response` 后，方块背面按 `response.power` 输出 `response.tick` 个游戏刻。

默认值：`maxConnectCount=10`、`keepalivetime=60` 秒、`timeout=10` 秒。
`address` / `port` / `token` 的默认值分别是 `127.0.0.1` / `9000` / `change-me`。

调试命令（仅管理员；不经过红石，直接用已有连接发一条 request）：

```
/tcpi test <name> <power>
# 例如
/tcpi test t2:2 15
```

管理员还可以管理白名单：

```
/tcpi whitelist add <player>
/tcpi whitelist remove <player>
/tcpi whitelist list
```

默认只有管理员（权限等级 ≥2，或单人游戏主机）能打开 UI、修改配置、开关连接；白名单成员可以操作 UI，但不能管理白名单。

### 状态

| 状态 | 含义 |
| --- | --- |
| 未连接 | 新建的配置项，从未发起连接 |
| 正在连接 | TCP 连接正在建立 |
| 连接中 | TCP 已连接（对应“连接成功”） |
| 连接断开 | 连接失败、被关闭、心跳超时、IO 断开或重放防护断开 |

- 关闭连接后状态为“连接断开”。
- 退出地图会关闭所有 TCP 连接，但**不修改**状态。
- 重新进入地图时，只对状态为“连接中”的项重新发起连接。
- 全局网络配置修改后只影响之后新建的连接；已存在的连接继续使用建立时的参数快照。

### 名称规则

- `name` 不允许重名。
- `name` 不得包含逗号 `,`、等号 `=`、换行符 `\n`/`\r`。
- `name` 上限是 **80 个 UTF-8 字节**，不是 80 个字符；中文通常占 3 字节。UI 会实时显示“已用 X/80 字节”，超限时红字提示并禁用保存。
- 修改为非法内容时服务端拒绝并提示。
- `name` 为空表示不加入配置表；方块被破坏、清除或被活塞移动时，对应配置项会立即删除并关闭连接。
- 方块配置界面的“保存”会写入方块 NBT 和地图配置表。

### 红石方向

- **正面**（侦测器脸部纹理）是检测端，读取该面相邻方块朝本方块方向的红石信号。
- **背面**（侦测器红点纹理）是输出端，向相邻方块输出 `response.power`。
- 放置朝向与侦测器一致：正面朝向玩家视线方向（即贴靠/看向的面）。
- 检测到任何变化（包括变为 0）都会发请求；`power` 为 0–15。

## 测试服务器（Python）

```bash
python tools/tcp_test_server.py --host 127.0.0.1 --port 9000 --token change-me
```

参数：

- `--keepalive`：服务端空闲断开秒数，默认 90；应大于客户端 `keepalivetime`（默认 60），避免心跳刚到服务端就因超时被断开
- `--tick`：测试服务端返回的 `response.tick` 保持时长，默认 2
- `--split`：把每个响应拆成两次 TCP 写入，用于测试拆包
- `--no-console`：不启动控制台（后台测试）
- `--debug`：额外打印每一帧的原始长度/头尾字节，排查链路问题
- `--log-file`：日志文件，默认 `tools/tcp_test_server.log`
- `--no-log-file`：只输出控制台

日志会同时写到控制台和 `tools/tcp_test_server.log`，包括：

```
listening on 127.0.0.1:9000
accepted 127.0.0.1:xxxxx
message received: type=request name=t2:2 power=15 requestid=... timestamp=...
handle_request: name=t2:2 power=15
business result: response power=15 tick=20
response sent: ... requestid=...
disconnected
```

看到 `message received` 说明请求确实到了服务端；看到 `invalid business result` 说明业务返回值非法（例如 power >15），此时不会发送响应。

控制台命令：

```
list
push <power> [tick] [name]     # 服务端主动下发 response（新 requestid/timestamp）
kick <name>
quit
```

测试服务端的业务返回值由 `handle_request()` 决定。当前文件保留了你正在测试的实现（固定返回 `power=15`、`tick` 在 20–120 之间随机）；如果要默认回显，取消 `return message.power, self.server.default_tick` 注释并删除当前的 `return 15, random.randint(20, 120)`。它实现了 256 字节定长帧、粘包/拆包、AES-256-GCM、227 字节换行填充、时间戳/重复校验、30 秒计数表重置和空闲断开。

### 修改测试服务端业务逻辑

协议、加解密、计数、发送都已经封装好。**通常只需要改一个方法**：

[`ClientConnection.handle_request()`](tools/tcp_test_server.py:370)

```python
def handle_request(self, message):
    # message.name      方块配置的 name
    # message.power     请求中的红石强度 0..15
    # message.requestid 本次请求 id（框架自动回填到 response）
    # message.timestamp 客户端 Unix 秒

    if message.name == "lamp":
        return 15, 20                 # 输出 15，保持 20 个游戏刻

    if message.name == "button":
        return (15 if message.power > 0 else 0), 10

    return message.power, self.server.default_tick   # 默认：回显
```

- 返回值是 `(response_power, response_tick)`：
  - `response_power`：0–15，客户端输出端输出的红石强度；
  - `response_tick`：红石保持多少**游戏刻**，不是时间戳；
  - 返回 `None`：本次不回复（不计数、不发送）。
- 不需要自己加密、补 227 字节或回填 `requestid`，框架会做。
- `handle_request()` 在对应连接的 reader 线程执行，不要做长时间阻塞操作。
- 需要跨请求/跨连接共享状态时，使用 `self.server.business_state`，并用 `self.server.business_lock` 加锁：
  ```python
  with self.server.business_lock:
      count = int(self.server.business_state.get(message.name, 0)) + 1
      self.server.business_state[message.name] = count
  return (15 if count % 2 else 0), self.server.default_tick
  ```
- 修改后重启 Python 服务端即可；`--tick` 只是默认值，`handle_request` 的返回值优先。

服务端主动下发不需要等客户端请求：

```bash
# 控制台：给 name=lamp 的连接主动发 power=15、保持 20 tick
push 15 20 lamp
```

代码里可以在任意线程调用：

```python
server.push(15, 20, name="lamp")       # 给所有匹配 name 的连接
connection.push_response(15, 20)       # 只给某一条连接
```

主动下发会新生成 `requestid`/`timestamp`，客户端会按普通 `response` 处理。

如果需要“每个 name 使用不同 token”，需要修改 `ClientConnection.__init__` 里选择 key 的逻辑；默认所有连接共用 UI1 的 token。

### 服务端没收到请求？按这个顺序排查

**第 1 步：不启动 Minecraft，先单独测服务端**

```bash
# 终端 A：启动服务端（保持运行）
python tools/tcp_test_server.py --host 127.0.0.1 --port 9000 --token change-me

# 终端 B：模拟客户端发一条 request
python tools/send_test_request.py --host 127.0.0.1 --port 9000 \
  --token change-me --name t2:2 --power 15 --keepalive
```

`send_test_request.py` 会打印发送/接收的 256 字节帧和 response 字段。出现 `PASS` 说明 Python 服务端、token、加解密、端口都正常。

**第 2 步：看服务端日志 `tools/tcp_test_server.log`**

- 没有 `listening`：服务端启动失败（端口被占用/地址不对），看控制台的 `FAILED TO START`。
- 有 `accepted` 但没有 `message received`：TCP 连上了，但客户端没发帧，或帧解密失败（会打印 `dropped frame`）。
- 有 `message received` 但没有 `response sent`：看紧随其后的 `business result` 或 `invalid business result`。
- 有 `response sent`：服务端已经回了，问题在客户端接收/输出侧。

**第 3 步：看 Minecraft 日志**

日志位置：开发环境 `run/logs/latest.log`；正式启动器 `.minecraft/logs/latest.log`。搜索 `network-socket`：

```
Connecting TCP interface 't2:2' to 127.0.0.1:9000
TCP interface 't2:2' connected
Sending TCP request 't2:2' power=15
[tcp:t2:2] request queued: power=15 requestid=... frame=256 bytes
Received TCP response for 't2:2' power=15 tick=20
```

也可以不进红石流程，直接在游戏里强制发一条（仅管理员）：

```
/tcpi test t2:2 15
```

- 成功会提示“已发送测试请求…”，服务端日志应出现对应的 `message received`。
- 如果提示 name 不在配置表/状态不是连接中，先修 UI2。
- 如果命令发送了但服务端没有 `message received`，是端口/地址/token/防火墙问题；先用第 1 步确认服务端。
- 如果命令正常、红石不发送，问题在方块检测面/红石信号变化。

- 只有 `connected`，没有 `Sending TCP request`：红石没有触发发送。检查：
  - 方块 `name` 与配置表里的 name 完全一致；
  - UI2 状态是“连接中”；
  - 红石信号加在**正面**（侦测器脸部纹理那一面）的相邻方块上；背面是输出端；
  - 信号必须发生变化（0→15、15→0、或强度变化），一直保持不变不会发。
- 有 `Sending TCP request`，但服务端没有 `message received`：端口/地址/token/防火墙问题；先用第 1 步确认服务端。
- 服务端重启后，客户端会显示“连接断开”；需要在 UI2 重新“开启连接”，或重新进入地图。

**第 4 步：检查业务返回值**

`response_power` 必须是 **0..15**。例如：

```python
return message.power + 3, 40
```

当 `power=13..15` 时会返回 16..18，协议判为非法，服务端会记录：

```
invalid business result: response power must be 0..15
```

并且**不会发送响应**。正确写法：

```python
return min(15, message.power + 3), 40
```

另外，`tools/tcp_test_server.log` 中的 `handle_request` 行可以确认业务方法是否被调用；如果只改了代码但没有重启 Python 服务端，运行的仍是旧逻辑。

## 协议

完整字节布局、字段格式、重放防护算法和客户端/服务端流程见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

摘要：

- 帧固定 256 字节：`nonce[12] || AES-256-GCM密文[227] || tag[16] || 0x0A`。明文先按 UTF-8 用 `\n` 补齐到 227 字节再加密；明文超过 227 字节直接阻断。没有 base64。
- 密钥：`SHA-256(token UTF-8)`。
- 明文（均在加密数据内，不在帧外传输）：
  - `type=request,name=...,power=...,requestid=...,timestamp=...`
  - `type=response,power=...,tick=...,requestid=...,timestamp=...`
  - `type=keepalive,name=...,requestid=...,timestamp=...`
  - `type=keepaliveok,requestid=...,timestamp=...`
- `name`：UTF-8 编码后 1–80 字节；`requestid`：[A-Za-z0-9_-]，1–64 字符；`timestamp`：十进制 Unix 秒，接收方与本机绝对差 >10 秒即非法。
- 每条连接独立维护内存请求计数表，30 秒重置；时间戳非法/重复计数加 1，计数 >10 立即断开。
- 连接建立后客户端立即发送一次 `keepalive` 完成 name 注册；之后每 `keepalivetime` 秒发送一次，`timeout` 秒内无 `keepaliveok` 则断开。

## 构建

需要 **JDK 25**（项目使用 Java 25 / Minecraft 26.3 / Fabric Loader 0.20.1）。

```bash
./gradlew build
```

产物：`build/libs/network-socket-1.0.0.jar`。

## 测试

### Python 单元/集成测试

```bash
python tools/test_protocol.py
```

覆盖：帧补齐/超长阻断、错误 key、格式解析、重复 requestid、时间戳非法、计数 >10 断开、空闲断开、粘包/拆包、服务端主动下发，以及“修改 `handle_request` 后 response 的 power/tick 随之改变”。

### Java ↔ Python 跨语言探针

先启动测试服务器（端口 19000、token `probe-token`），然后：

```bash
javac -cp build/classes/java/main -d build/tmp/probe tools/java/ProtocolProbe.java
java -cp "build/classes/java/main;build/tmp/probe" ProtocolProbe 127.0.0.1 19000 probe-token probe
```

Linux/macOS 将 classpath 分隔符改为 `:`。

### 客户端 Gametest

需要先启动端口 **19001**、token **`gametest-token`** 的测试服务器：

```bash
python tools/tcp_test_server.py --host 127.0.0.1 --port 19001 --token gametest-token --tick 20 --no-console
```

PowerShell：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfabric.client.gametest=true -Dfabric.client.gametest.modid=network-socket'
./gradlew runClient
```

Gametest 覆盖：方块放置/模型、UI1/UI2/方块界面截图、重名/非法名拒绝、保存配置、开启连接、`maxConnectCount` 限制、红石请求/响应输出、退出地图后状态保留、重新进入地图重连、方块破坏删除配置。截图输出到 `run/screenshots/`。

## 皮肤替换

方块纹理位于：

```
src/main/resources/assets/network-socket/textures/block/
  tcp_interface_front.png
  tcp_interface_back.png
  tcp_interface_back_on.png
  tcp_interface_side.png
  tcp_interface_top.png
```

模型按这些路径引用，替换同名 PNG 即可，不需要改 Java 代码。模型：

- `assets/network-socket/models/block/tcp_interface.json`
- `assets/network-socket/models/block/tcp_interface_on.json`

## 目录

```
src/main/java/ws/fabricmod/networksocket/
  protocol/      纯 Java 协议：帧、AES-GCM、消息、重放计数
  config/        地图 SavedData、name/status 配置表
  network/       TCP 连接、连接管理器、载荷与服务端处理
  permission/    权限判定、全服白名单、权限同步
  command/       /tcpi 命令
  block/         TCP 接口方块与方块实体
src/client/java/ws/fabricmod/networksocket/client/
  NetworkSocketClient.java   快捷键与客户端载荷接收
  ClientAccess.java          客户端权限缓存
  NetworkConfigScreen.java   UI1/UI2
  BlockConfigScreen.java     方块 name 配置
  TcpInterfaceClientGameTest.java  客户端 Gametest
tools/
  tcp_protocol.py            Python 参考协议
  tcp_test_server.py         Python 测试服务端
  test_protocol.py           Python 测试
  java/ProtocolProbe.java    跨语言探针
  gen_blockstate.py          生成 96 个 blockstate 变体
```

## 安全与限制

- 仅建议内网使用；没有 TLS，token 会下发给有权限打开 UI 的客户端。
- 默认拒绝：只有管理员（权限等级 ≥2、单人游戏主机，或服务器控制台）和白名单成员能打开 UI、修改配置、开关连接；无权限的右键/K 静默无效。
- 白名单全服生效，存在主世界 `SavedData`（`network-socket:whitelist`）中；只有管理员能增删，白名单成员不能管理白名单。
- 重放计数表只在内存中，30 秒窗口；协议不持久化它。
- 同名 `name` 通过 NBT/结构方块复制出多个方块时，删除其中一个会删除该名字的配置项；请避免复制带名字的方块。
- 服务端主动下发的 `response` 没有对应客户端请求，客户端仍会执行时间戳/重复校验并输出红石；`keepaliveok` 仍要求匹配客户端心跳的 `requestid`。
- `response.tick` 按本次需求澄清解释为“红石信号保持的游戏刻数”，不是游戏时间戳。

## License

MIT，见 [LICENSE](LICENSE)。