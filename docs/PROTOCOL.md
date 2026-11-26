# TCP 接口协议（256 字节定长帧）

本文描述本模组实际实现的线上协议。Minecraft 模组始终是 **TCP 客户端**；`address:port` 对应的外部服务端是 **TCP 服务端**。参考服务端见 `tools/tcp_test_server.py`。

> 仅建议内网使用。AES-256-GCM 提供机密性和完整性，不提供重放防护；`requestid` + `timestamp` 是应用层补充措施。

## 1. 帧格式

每条应用层报文编码成**恰好 256 字节**的一个 TCP 帧：

```
┌──────────────────────────── 256 bytes ────────────────────────────┐
│ nonce[12] │ AES-256-GCM ciphertext[227] │ tag[16] │ 0x0A │
└───────────────────────────────────────────────────────────────────┘
```

发送方编码步骤：

1. 明文为 UTF-8 的 `key=value` 字符串，**最多 227 字节**。
2. 在明文末尾追加 `\n`（0x0A），直到恰好 227 字节；明文本身正好 227 字节时不追加。
3. 生成 12 字节随机 nonce。
4. 对**补齐后的 227 字节**做 `AES-256-GCM` 加密，认证标签 16 字节；密文长度仍为 227。
5. 按 `nonce || ciphertext || tag || 0x0A` 组装，恰好 256 字节。
6. 明文超过 227 字节：**阻断，不发送**。

接收方解码步骤：

1. 读取恰好 256 字节（处理 TCP 粘包/拆包）。
2. 校验最后一个字节是 `0x0A`；否则阻断。
3. 前 12 字节为 nonce，中间 227 字节为密文，随后 16 字节为 tag；AES-256-GCM 解密；失败则阻断。
4. 解密结果必须恰好 227 字节。
5. 从第一个 `\n` 处截断；没有 `\n` 时使用全部 227 字节；截断后的明文不能为空。
6. 第一个 `\n` 之后必须全部是 `\n` 填充；否则阻断。
7. 对明文做严格 UTF-8 和字段解析。

这里**没有 base64**：密文直接放进定长帧。`0x0A` 只作为“明文填充”和“帧尾标记”使用；协议字段禁止换行，因此第一个 `\n` 不会歧义。

密钥：

```
key = SHA-256(token 的 UTF-8 字节)     // 32 字节
```

`token` 为 UI1 中的字符串。nonce 每条报文重新随机生成，不重放。

### 长度预算

- 帧固定 256 字节。
- 固定开销：12(nonce) + 16(tag) + 1(帧尾) = 29 字节。
- `256 - 29 = 227`，即**明文最多 227 字节**。
- 最坏 `request`（80 字节 name + 64 字符 requestid）约 212 字节，仍在 227 以内；更长报文必须由上层阻断。

## 2. 应用层报文

字段之间用英文逗号分隔，**字段顺序固定，字段数固定**。所有字段都在 AES-GCM 加密的明文里，256 字节帧外不传输 `requestid` / `timestamp`。

```
request     : type=request,name=<name>,power=<0-15>,requestid=<id>,timestamp=<unix秒>
response    : type=response,power=<0-15>,tick=<保持刻数>,requestid=<id>,timestamp=<unix秒>
keepalive   : type=keepalive,name=<name>,requestid=<id>,timestamp=<unix秒>
keepaliveok : type=keepaliveok,requestid=<id>,timestamp=<unix秒>
```

字段规则：

- `type`：`request` / `response` / `keepalive` / `keepaliveok`。
- `name`：非空；UTF-8 编码后 ≤80 字节；不得含 `,`、`=`、`\n`、`\r`。
- `power`：十进制 0–15。
- `tick`：十进制非负整数，最大 `2147483647`；本实现中表示红石输出保持的游戏刻数。
- `requestid`：`[A-Za-z0-9_-]{1,64}`；不得含逗号、等号、换行。
- `timestamp`：十进制 Unix 秒，>0；不得带符号/小数，最多 19 位。
- 不允许缺失字段、额外字段、字段乱序或未知类型。

`requestid` 生成：

- 客户端每条 `request`、每条 `keepalive` 都生成新的 `requestid`。
- 服务端每条 `response` 回填对应 `request` 的 `requestid`；每条 `keepaliveok` 回填对应 `keepalive` 的 `requestid`。
- 服务端主动下发 `response` 时生成全新的 `requestid` 和当前 `timestamp`。
- 建议在 30 秒窗口内不重复使用同一个 `requestid` 作为新的独立请求。

参考实现生成 12 字节随机数，URL-safe base64 无填充后得到 16 个字符。

## 3. 接收流程与重放防护

接收方对每一帧：

```
解密 -> 去填充 -> 解析 -> 取得 type/requestid/timestamp
```

然后执行：

1. 若 `timestamp` 缺失/非法/与本机当前 Unix 秒绝对差 **> 10**：
   - 对该 `requestid` 计数 +1；
   - 阻断，不发送报文、不输出红石；
   - 若计数 >10，立即断开连接。
2. 否则若 `requestid` 已存在于本连接计数表：
   - 对该 `requestid` 计数 +1；
   - 阻断；
   - 若计数 >10，立即断开。
3. 否则继续业务处理。
4. 业务/接收处理成功后，将该 `requestid` 写入本连接计数表并 +1（不存在则创建为 1）。
5. 每次计数增加后立即检查；计数 **>10** 断开。
6. 时间戳非法与 `requestid` 重复同时成立时，只走时间戳分支，只 +1 一次。

计数表：

- 每条 TCP 连接独立一份；只存内存，不持久化。
- 连接建立时为空；连接关闭/断开时销毁。
- 从连接建立时开始，每 30 秒清空一次；只清空计数，不改变状态、不关闭连接、不发报文。
- 重置后同一 `requestid` 可再次使用。

被重放防护阻断的报文**不算成功请求**：服务端不发送响应，也不更新空闲计时；客户端不输出红石。

## 4. 客户端（Minecraft 模组）流程

### 4.1 红石发送

检测端（方块正面）相邻方块的红石信号发生变化时：

1. 检查方块 `name` 非空，且配置表对应状态为“连接中”；否则阻断。
2. 生成新 `requestid`、当前 `timestamp`。
3. 构造 `type=request,name=...,power=...,requestid=...,timestamp=...`。
4. AES-256-GCM 加密 227 字节填充明文，组装 256 字节帧；明文超过 227 字节则阻断。
5. 通过该 `name` 对应连接发送。
6. 发送 `request` 本身**不**把 `requestid` 计入客户端接收计数表；收到 `response` 并处理成功后才计入。

### 4.2 接收

收到一帧：

1. 解密、去填充、解析。
2. 按第 3 节执行时间戳/重复校验。
3. `response`：校验 `power`/`tick` 合法、`requestid` 合法；通过后按 `power` 输出 `tick` 个游戏刻。
   - 不要求该 `requestid` 对应某个待处理请求：服务端主动下发的 `response` 也接受。
   - 输出完成后把该 `requestid` 计入本连接接收计数表。
4. `keepaliveok`：必须与客户端记录的心跳 `requestid` 匹配；校验时间戳/重复；成功后计入计数表，**不输出红石**。
5. 其他类型或校验失败：阻断。

### 4.3 心跳

- 连接建立后立即发送一次 `keepalive`，用于让服务端把新 socket 与 `name` 建立映射；否则服务端要等到第一次 `request` 或第一个 60 秒心跳才知道这是哪个方块。
- 之后每条连接每隔 `keepalivetime` 秒发送一次 `keepalive`，生成新 `requestid` 和当前 `timestamp`。
- 加密/去填充/256 字节长度规则与普通报文一致。
- 发送后记录 `requestid` 和发送时间。
- `timeout` 秒内未收到对应 `keepaliveok`：状态改为“连接断开”，关闭 TCP 连接并提示玩家。
- 心跳超时、连接失败、连接断开、重放计数 >10 断开都要提示玩家（本实现提示配置项的所有者/操作者）。

## 5. 服务端流程

1. 监听 `address:port`，接受连接。
2. 按 256 字节定长帧读取；必须处理粘包/拆包。
3. 校验帧尾 `0x0A`，用 UI1 配置的 `token` 解密；失败阻断。
4. 解密后必须恰好 227 字节；取第一个 `\n` 前的明文并校验填充；失败阻断。
5. 严格解析格式；失败阻断。
6. 执行第 3 节重放防护。
7. `request` → 业务处理；`keepalive` → 心跳处理；其他阻断。
8. 业务处理成功并完成 `requestid` 计数后，构造：
   - `type=response,power=<服务端决定>,tick=<保持刻数>,requestid=<回填请求id>,timestamp=<当前时间>`
9. 加密、227 字节填充、组装 256 字节帧；超过 227 字节阻断。
10. 发送响应。
11. 发送失败按连接断开处理。

服务端可以主动下发 `response`：从第 8 步开始，使用新 `requestid` 和当前 `timestamp`，再执行第 9–10 步。参考服务端的控制台 `push` 命令用于此场景。

服务端心跳：

- 收到 `keepalive`，先执行第 3 节重放防护。
- 成功后回填同一 `requestid`，新 `timestamp`，返回 `keepaliveok`。
- 加解密/填充/256 字节规则一致。
- 某个连接超过 `keepalivetime` 秒没有收到**成功**的 `request`/`keepalive`，断开该连接。
- 被重放防护阻断的报文不更新空闲计时。

## 6. 权限与白名单（Minecraft 侧）

- 默认拒绝：普通玩家不能打开配置 UI、不能修改配置、不能开启/关闭连接、不能使用测试命令。
- 管理员：权限等级 ≥2（`GAMEMASTERS`），或单人游戏的主机玩家；服务器控制台视为管理员。
- 白名单：全服生效，保存在主世界 `SavedData`（id `network-socket:whitelist`）中，存玩家 UUID；不跟单个地图的 UI1 配置走。
- 只有管理员能管理白名单，白名单成员不能给自己或别人增删白名单。
- 命令：
  - `/tcpi whitelist add <player>`：管理员添加白名单。
  - `/tcpi whitelist remove <player>`：管理员移除白名单。
  - `/tcpi whitelist list`：管理员查看白名单。
  - `/tcpi test <name> <power>`：仅管理员，使用已有连接发送测试请求并绕过红石。
- 服务端始终做最终权限校验；客户端收到 `PermissionS2C` 只用于本地提前拦截（无权限时 K 不发包、右键不挥手）。权限被撤销时，已打开的配置界面会关闭。
- 无权限的右键/K 不做任何提示；被服务端拒绝的请求也不返回错误消息。

## 7. 状态与持久化（Minecraft 侧）

- 状态固定为：`未连接`、`正在连接`、`连接中`、`连接断开`。
- 新建配置项为“未连接”。
- 开启连接：先“正在连接”；TCP 建立成功 → “连接中”；失败 → “连接断开”。
- 关闭连接 → “连接断开”。
- 退出地图：关闭所有 TCP 连接，**不修改状态**。
- 进入地图：对状态“连接中”的项重新发起连接；上次保存时处于“正在连接”的项恢复为“未连接”。
- 配置表保存在地图存档的 `dimensions/minecraft/overworld/data/network-socket/network_config.dat`（26.3 的 per-dimension SavedData 路径）。
- 方块被破坏/清除/移动：立即删除对应 `name` 配置项并关闭连接。
- 所有连接受 `maxConnectCount` 限制；达到上限阻止新建并提示。

## 8. 本实现的明确约定

需求文本中有几处需要明确，本实现采用以下约定（已与需求方确认）：

1. **加密顺序**：发送端“UTF-8 明文 → 换行补齐到 227 → AES-GCM 加密 → 加 12 字节 nonce/16 字节 tag/1 字节帧尾”；接收端“校验帧长和帧尾 → 解密 → 固定 227 字节去填充”。不存在 base64 层。
2. **`tick` 含义**：`response.tick` 是红石信号保持的游戏刻数，不是游戏时间戳。客户端不要求 `response.tick` 与发送时的 tick 相等。
3. **请求/响应 `requestid` 对应**：普通响应按需求回填请求 `requestid`；客户端**不强制**该 `requestid` 对应某个待处理请求，以便接受服务端主动下发。`keepaliveok` 仍必须匹配客户端记录的心跳 `requestid`。
4. **服务端主动下发**：没有对应客户端请求时也接受，执行时间戳/重复校验和计数；通过后按 `power`/`tick` 输出红石。
5. **服务端可改写 `power`**：客户端使用 `response.power`，不要求与请求 `power` 相同；参考测试服务端回显请求 `power`。
6. **提示玩家范围**：连接失败/断开/心跳超时/重放断开提示配置项所有者（开启连接的操作者）；UI 操作错误直接提示操作者；所有者离线时消息暂存，上线后补发。
7. **UI1 全局配置跟随地图保存**，不是全局 `config/*.json`。
8. **UI1/UI2 通过快捷键打开**（默认 K），右键方块只打开方块 `name` 配置界面。
9. **`name` 上限为 80 个 UTF-8 字节**，不是 80 个字符；中文通常占 3 字节。UI 会显示已用字节数，超限禁止保存。

## 9. 参考测试服务端

```bash
python tools/tcp_test_server.py --host 127.0.0.1 --port 9000 --token change-me
```

- `--keepalive`：空闲断开秒数（默认 90；应大于客户端 `keepalivetime`，默认 60）
- `--tick`：默认响应保持刻数（默认 2）
- `--split`：响应分两次 `sendall`，用于拆包测试
- `--no-console`：后台模式

控制台：

```
list
push <power> [tick] [name]
kick <name>
quit
```

服务端处理顺序与前面一致：重放防护 → 成功计数 → 发送响应/心跳响应；主动下发使用新 `requestid`。`tools/test_protocol.py` 覆盖帧、解析、重复/时间戳、计数断开、粘包/拆包、主动下发和空闲断开。