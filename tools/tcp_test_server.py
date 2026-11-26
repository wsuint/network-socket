#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TCP 接口模组的参考/测试服务端。

这个文件可以分成三层，阅读时建议按顺序看：

1. 协议层（不要改）：tools/tcp_protocol.py
   - AES-256-GCM 加解密
   - 明文用换行补齐到 227 字节，再加密；整帧固定 256 字节
   - 报文格式解析
   - 每连接 requestid 计数表

2. 连接框架（一般不用改）：本文件的 ClientConnection
   - 每个 TCP 客户端一个线程
   - 处理粘包/拆包：按 256 字节定长帧读取
   - 解密 -> 解析 -> 时间戳/重复校验 -> 分发
   - 心跳、空闲断开、发送响应
   - 负责“先计数、再发送响应”的顺序

3. 业务逻辑：不要写在这个文件里。新建一个只含 handle() 的业务文件
   （模板见 tools/business_server.py），然后：
       python tools/business_server.py --port 9000 --token change-me
   框架会把每条 request 交给 handle()，并在同一个文件里启动控制台。
   只想临时试一下，也可以直接改 ClientConnection.handle_request()。

运行：
    python tools/tcp_test_server.py --host 127.0.0.1 --port 9000 --token change-me

控制台命令：
    list
    push <power> [tick] [name]   # 服务端主动下发 response
    kick <name>
    quit

线程模型（重要）：
- 主线程：接受连接、读控制台命令。
- 每个客户端一个 reader 线程：读取/解密/校验/调用 handle_request/发送。
- 发送时用 self._send_lock 串行化，因此其他线程也可以调用 push_response()。
- handle_request() 运行在对应客户端的 reader 线程里：
  - 不要做非常耗时的阻塞操作；
  - 需要共享状态时自己加锁（见 handle_request 里的示例）。
"""

from __future__ import annotations
import random
import argparse
import atexit
import os
import socket
import sys
import threading
import time
import weakref
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, TextIO

import tcp_protocol as proto


# 每次 recv 最多取多少字节。取大一点可以减少系统调用；
# 真正的“一帧”仍然是 proto.FRAME_SIZE(256) 字节。
RECV_CHUNK = 4096

# 默认日志文件放在脚本旁边，方便用户直接查看。
DEFAULT_LOG_FILE = str(Path(__file__).with_name("tcp_test_server.log"))

# 日志同时写控制台和文件。configure_logging() 在 main() 里调用。
_LOG_LOCK = threading.Lock()
_LOG_FILE: Optional[TextIO] = None
_LOG_PATH: Optional[str] = None

# 进程正在退出：此时后台线程（reader/ticker）可能还在写日志，
# 继续往 stdout 写会触发 "Fatal Python error: _enter_buffered_busy"，
# 进程直接 abort（测试虽然全绿，但退出码变成非 0）。退出阶段只写文件。
_SHUTTING_DOWN = False

# 所有 start() 过的服务器；进程退出时统一 stop()，把后台线程收干净。
_LIVE_SERVERS: "weakref.WeakSet[TcpTestServer]" = weakref.WeakSet()


def configure_logging(path: Optional[str]) -> None:
    """启用文件日志；path=None 表示只输出控制台。"""
    global _LOG_FILE, _LOG_PATH
    _LOG_PATH = path
    if not path:
        return
    try:
        _LOG_FILE = open(path, "a", encoding="utf-8", buffering=1)
    except OSError as exc:
        print(f"cannot open log file {path}: {exc}", file=sys.stderr)
        _LOG_FILE = None


def log(message: str) -> None:
    """统一日志格式，带时间戳；同时写控制台和日志文件，立即 flush。"""
    line = f"[{time.strftime('%H:%M:%S')}] {message}"
    with _LOG_LOCK:
        if not _SHUTTING_DOWN and not sys.is_finalizing():
            try:
                print(line, flush=True)
            except (OSError, ValueError):
                pass
        if _LOG_FILE is not None:
            try:
                _LOG_FILE.write(line + "\n")
                _LOG_FILE.flush()
            except (OSError, ValueError):
                pass


def _stop_all_servers() -> None:
    """atexit 钩子：先禁止控制台输出，再把还活着的服务器停掉并收线程。

    reader/ticker 都是 daemon 线程；不先停掉它们，解释器收尾时它们可能正在
    print()，会触发 "Fatal Python error: _enter_buffered_busy" 让进程 abort。
    """
    global _SHUTTING_DOWN
    _SHUTTING_DOWN = True
    for server in list(_LIVE_SERVERS):
        try:
            server.stop()
        except Exception:      # 退出路径上不再让异常打断清理
            pass


atexit.register(_stop_all_servers)


# 业务回调签名：handle(request, ctx) -> (power, tick) 或 None。
BusinessHandler = Callable[[proto.Message, "BusinessContext"], Optional[tuple[int, int]]]

# 业务初始化回调：serve(setup=...) 在监听成功后调用一次，适合启动定时线程。
BusinessSetup = Callable[["TcpTestServer"], None]


class BusinessContext:
    """handle() 的第二个参数：只暴露业务真正需要的东西。

    - ctx.name / ctx.address：最近一次 request/keepalive 里的 name、对端地址
    - ctx.log(message)      ：带连接前缀写日志（控制台 + 日志文件）
    - ctx.push(power, tick) ：向本条连接主动下发一条 response
    - ctx.server            ：TcpTestServer，共享状态用 business_state / business_lock

    属性都是每次读取时现取的，因此 ctx 可以在连接生命周期内一直持有。
    """

    __slots__ = ("_connection",)

    def __init__(self, connection: "ClientConnection") -> None:
        self._connection = connection

    @property
    def name(self) -> Optional[str]:
        return self._connection.name

    @property
    def address(self) -> tuple[str, int]:
        return self._connection.address

    @property
    def server(self) -> "TcpTestServer":
        return self._connection.server

    def log(self, message: str) -> None:
        log(f"{self._connection.label()} {message}")

    def push(self, power: int, tick: Optional[int] = None) -> bool:
        """向本条连接主动下发一条 response，返回是否发送成功。"""
        return self._connection.push_response(
            power, self.server.default_tick if tick is None else tick)


class ClientConnection:
    """一条已接受的 TCP 连接。

    生命周期：
        accept -> start() -> reader 线程循环 -> close()

    每个连接独立维护：
        - 接收缓冲（处理粘包/拆包）
        - requestid 计数表（30 秒重置）
        - 最近一次“成功业务”时间（服务端空闲断开用）
        - 发送锁（响应和主动下发可能来自不同线程）
    """

    def __init__(self, server: "TcpTestServer", sock: socket.socket,
                 address: tuple[str, int]) -> None:
        self.server = server
        self.sock = sock
        self.address = address
        self.key = server.key           # SHA-256(token)，由 TcpTestServer 统一算好

        # 本连接的 requestid -> 计数。只存在内存里，断开即丢弃。
        self.replay = proto.ReplayGuard()

        # 最近一次 request/keepalive 里携带的 name。
        # 业务里可以按 name 区分不同红石设备；控制台 push 也按它过滤。
        self.name: Optional[str] = None

        # 传给业务回调的上下文；属性动态读取，不需要每次新建。
        self.ctx = BusinessContext(self)

        self.created_at = time.monotonic()

        # 最近一次“成功业务”的时间（不是收到任意字节的时间）。
        # 被重放防护阻断的报文不会更新它，因此不能靠坏包“保活”。
        self.last_activity = time.monotonic()

        # TCP 是字节流，一次 recv 可能拿到半帧，也可能拿到多帧。
        # 这里缓存还没凑满一帧/还没处理完的字节。
        self._buffer = bytearray()

        self._stop = threading.Event()

        # 同一连接的响应/主动下发都通过这个锁串行写入，避免两个线程的
        # sendall 交叉导致 256 字节帧错位。
        self._send_lock = threading.Lock()

        self._thread = threading.Thread(
            target=self._run,
            name=f"tcp-client-{address[1]}",
            daemon=True,
        )

        # 仅用于 list 命令展示/调试，不参与协议。
        self.requests = 0
        self.keepalives = 0

    # ------------------------------------------------------------------
    # 连接生命周期
    # ------------------------------------------------------------------

    def start(self) -> None:
        self._thread.start()

    def close(self) -> None:
        """幂等关闭：关闭 socket、唤醒阻塞、从服务器连接表移除。"""
        if self._stop.is_set():
            return
        self._stop.set()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
        self.server._remove(self)

    def _run(self) -> None:
        """reader 线程主循环。

        关键点：
        - socket 设置 0.5 秒超时，是为了能周期性检查空闲断开和计数表重置。
        - 超时不等于断开，继续循环。
        - 每次把新收到的字节追加到 _buffer，然后只要 >=256 字节就取出一帧。
          这同时解决了粘包（一次多帧）和拆包（一帧分多次到）。
        """
        try:
            self.sock.settimeout(0.5)
        except OSError:
            # 极端情况下这个连接在 reader 线程真正跑起来之前就被 close() 了
            # （比如启动后立刻 stop()），此时 socket 已失效，直接收尾。
            self.close()
            return
        try:
            while not self._stop.is_set():
                try:
                    chunk = self.sock.recv(RECV_CHUNK)
                except socket.timeout:
                    chunk = None
                except OSError:
                    break

                if chunk:
                    self._buffer.extend(chunk)
                    while len(self._buffer) >= proto.FRAME_SIZE:
                        frame = bytes(self._buffer[:proto.FRAME_SIZE])
                        del self._buffer[:proto.FRAME_SIZE]
                        self._process_frame(frame)
                        if self._stop.is_set():
                            break
                elif chunk == b"":
                    # 对端正常关闭
                    break

                # 每 30 秒清空一次本连接的 requestid 计数表。
                # 只清计数，不影响连接、不修改任何玩家状态。
                if self.replay.reset_if_due():
                    log(f"{self.label()} requestid table reset (30s window)")

                # 服务端空闲断开：超过 keepalive 秒没有“成功”的 request/keepalive。
                # 注意 last_activity 只在成功处理后才更新（见 _record_and_send）。
                if time.monotonic() - self.last_activity > self.server.keepalive:
                    log(f"{self.label()} idle for more than {self.server.keepalive}s, closing")
                    break
        finally:
            self.close()
            log(f"{self.label()} disconnected")

    # ------------------------------------------------------------------
    # 收包处理：解密 -> 解析 -> 重放防护 -> 分发
    # ------------------------------------------------------------------

    def _process_frame(self, frame: bytes) -> None:
        """处理恰好 256 字节的一帧。

        顺序必须和协议文档一致：
            解密/去填充/解析 -> timestamp -> requestid 重复 -> 业务/心跳
        任何一步失败都“阻断”，不发送响应，也不更新 last_activity。
        """
        if self.server.debug:
            log(f"{self.label()} raw frame received: {len(frame)} bytes, "
                f"head={frame[:12].hex()} tail={frame[-4:].hex()}")

        # 1) AES-GCM 解密 + 去填充 + 严格格式解析。
        #    decode_frame 会检查：
        #      - 帧长 256、最后一个字节是 \n
        #      - 解密后固定 227 字节，取第一个 \n 前的明文
        #      - 明文尾部必须全是 \n 填充
        #      - AES-GCM 认证标签
        try:
            plaintext = proto.decode_frame(frame, self.key)
            message = proto.parse_message(plaintext)
        except proto.ProtocolError as exc:
            log(f"{self.label()} dropped frame: {exc}")
            return

        # 能走到这里说明：这一帧确实到了服务端，token 对、格式对。
        # 后面的“没有请求/没有响应”就可以排除网络和加密问题。
        log(f"{self.label()} message received: type={message.type} "
            f"name={message.name} power={message.power} "
            f"requestid={message.requestid} timestamp={message.timestamp}")

        # 2) 时间戳校验：与本机 Unix 秒绝对差 > 10 秒即非法。
        #    非法时对该 requestid 计数 +1，然后阻断。
        #    如果计数 >10，立即断开。
        if not proto.timestamp_is_valid(message.timestamp):
            count = self.replay.increment(message.requestid)
            log(f"{self.label()} invalid timestamp for {message.requestid} "
                f"(skew > {proto.MAX_CLOCK_SKEW_SECONDS}s), count={count}")
            self._abort_if_needed(count)
            return

        # 3) 重复校验：同一个 requestid 已存在则计数 +1 并阻断。
        #    注意：这里不区分 type；协议要求所有收到的报文都做重放检查。
        #    如果 timestamp 非法和重复同时成立，上面的分支已经 return，
        #    所以只会加一次计数（符合“每个报文最多一次计数”）。
        if self.replay.contains(message.requestid):
            count = self.replay.increment(message.requestid)
            log(f"{self.label()} duplicate requestid {message.requestid}, count={count}")
            self._abort_if_needed(count)
            return

        # 4) 按 type 分发。
        if message.type == "request":
            self.name = message.name
            self.requests += 1

            # ▼▼▼ 业务逻辑调用点：返回值决定 response 的 power/tick ▼▼▼
            try:
                result = self.handle_request(message)
            except Exception as exc:  # 业务异常不应拖垮整个连接
                log(f"{self.label()} business error: {exc!r}")
                return

            if result is None:
                # 业务主动选择“不回复”。此时不计数、不发送。
                # 如果你希望即使不回复也计数，可在这里调用
                # self.replay.record_success(message.requestid)。
                log(f"{self.label()} business returned None, no response")
                return

            try:
                response_power, response_tick = result
                response_power = int(response_power)
                response_tick = int(response_tick)
                if not 0 <= response_power <= 15:
                    raise ValueError("response power must be 0..15")
                if response_tick < 0:
                    raise ValueError("response tick must be >= 0")
                # requestid 由框架回填，业务不用管。
                response_text = proto.build_response(
                    response_power, response_tick, message.requestid)
            except (TypeError, ValueError) as exc:
                log(f"{self.label()} invalid business result: {exc!r}; "
                    f"server will NOT send a response")
                return

            log(f"{self.label()} business result: "
                f"response power={response_power} tick={response_tick}")

            self._record_and_send(
                message.requestid,
                response_text,
                f"request name={message.name} power={message.power} "
                f"-> response power={response_power} tick={response_tick}",
            )
            return

        if message.type == "keepalive":
            # 心跳不经过业务。协议规定：
            #   收到 keepalive -> 重放防护 -> 计数 -> 回 keepaliveok。
            self.name = message.name
            self.keepalives += 1
            response_text = proto.build_keepaliveok(message.requestid)
            self._record_and_send(
                message.requestid,
                response_text,
                f"keepalive name={message.name}",
            )
            return

        # 服务端只处理 request / keepalive。
        # 收到 response / keepaliveok 属于方向错误，直接阻断。
        log(f"{self.label()} ignored unexpected message type {message.type}")

    def _record_and_send(self, request_id: str, response_text: str, what: str) -> None:
        """业务/心跳成功后的统一收尾。

        顺序很重要（需求规定）：
            先完成 requestid 计数 -> 再发送响应。
        如果发送失败，按连接断开处理（send_frame 内部 close）。
        """
        count = self.replay.record_success(request_id)
        self._abort_if_needed(count)

        try:
            frame = proto.encode_frame(response_text, self.key)
        except proto.ProtocolError as exc:
            # 明文超过 227 字节：按协议阻断，不发送。
            log(f"{self.label()} response blocked: {exc}")
            return

        if not self.send_frame(frame):
            return

        # 只有成功发送后才更新空闲计时。
        self.last_activity = time.monotonic()
        log(f"{self.label()} response sent: {what} requestid={request_id}")

    def _abort_if_needed(self, count: int) -> None:
        """计数 >10 立即断开（需求 6.6）。"""
        if count > proto.REPLAY_COUNT_LIMIT:
            log(f"{self.label()} requestid count {count} > "
                f"{proto.REPLAY_COUNT_LIMIT}, closing")
            self.close()

    # ------------------------------------------------------------------
    # ★★★ 默认业务实现（推荐在 business_server.py 里写业务）★★★
    # ------------------------------------------------------------------

    def handle_request(self, message: proto.Message) -> Optional[tuple[int, int]]:
        """处理一条 request，返回 response 的 (power, tick)。

        参数 message 是已解析、已通过时间戳/重复校验的 request：
            message.type       == "request"
            message.name       == 方块配置的 name
            message.power      == 请求里的红石强度 0..15
            message.requestid  == 本次请求 id（框架会自动回填到 response）
            message.timestamp  == 客户端 Unix 秒

        返回值：
            (response_power, response_tick)
            response_power : 0..15，客户端输出端输出的红石强度
            response_tick  : 保持多少个游戏刻（不是时间戳！）
            返回 None      : 本次不回复（不计数、不发送）

        不要在这个方法里：
            - 自己加密/补齐/拼 type=response；
            - 自己回填 requestid；
            - 直接 socket.sendall()。
        这些都由 _record_and_send / send_frame 完成。

        运行线程：对应客户端的 reader 线程。
        如果这里要访问共享数据，请加锁（示例在下面）。

        —— 默认业务：见下方 return（当前是临时测试版本）——

        如果 TcpTestServer 传入了 business 回调，这里直接转交给它，
        下面这段默认实现不会执行。
        """
        # 推荐路径：业务写在独立文件里（见 tools/business_server.py）。
        if self.server.business is not None:
            return self.server.business(message, self.ctx)

        # 调试日志：确认默认业务被调用（会写进 tools/tcp_test_server.log）
        log(f"{self.label()} handle_request: name={message.name} power={message.power}")
        # ==================================================================
        # 当前业务（你正在测试的版本）：模拟 2 秒处理时间，固定返回 15 强度、
        # 保持 100 个游戏刻。正式使用建议删掉 sleep，并改回下面的回显。
        # ==================================================================
        return 15,random.randint(20,120)

        # if message.name == "random":
        #     return random.randint(10, 15), random.randint(20,40)
        # return message.power, self.server.default_tick

        # ==================================================================
        # 你之前想测试的“加 3”示例（注意必须 clamp 到 0..15）：
        # 如果直接 return message.power + 3，power=13..15 会得到 16..18，
        # 协议只允许 0..15，框架会记录 "invalid business result" 并拒绝发送。
        # ==================================================================
        # return min(15, message.power + 3), 40

        # ==================================================================
        # 示例 2：固定输出 15，保持 40 tick（把上面 return 注释掉再启用）
        # ==================================================================
        # return 15, 40

        # ==================================================================
        # 示例 3：按 name 做不同设备逻辑
        # ==================================================================
        # if message.name == "lamp":
        #     return 15, 20
        # if message.name == "door":
        #     return 0 if message.power > 0 else 15, 10
        # return 0, 20

        # ==================================================================
        # 示例 4：使用服务端共享状态（注意加锁）
        # ==================================================================
        # with self.server.business_lock:
        #     count = int(self.server.business_state.get(message.name, 0)) + 1
        #     self.server.business_state[message.name] = count
        # # 奇数次请求输出 15，偶数次输出 0
        # return (15 if count % 2 == 1 else 0), self.server.default_tick

        # ==================================================================
        # 示例 5：业务失败/不想回复
        # ==================================================================
        # return None

        # ==================================================================
        # 主动下发（不属于 request 的响应）：
        #   可以在任何线程中调用 self.push_response(power, tick)，
        #   它会新生成 requestid/timestamp 并发送一条服务端主动 response。
        #   注意：主动下发不会增加本连接的“接收”计数表（那是接收侧的事）。
        #
        #   如果是定时/外部事件，推荐用：
        #       self.server.push(power, tick, name="lamp")
        #   它会遍历匹配 name 的连接，逐个 push_response()。
        # ==================================================================

    # ------------------------------------------------------------------
    # 发送与主动下发
    # ------------------------------------------------------------------

    def send_frame(self, frame: bytes) -> bool:
        """发送恰好 256 字节的一帧。

        多个线程可能同时调用（响应 + 主动下发），因此用 _send_lock 串行化。
        split_responses 仅用于测试“接收端拆包”，生产不需要。
        """
        try:
            with self._send_lock:
                if self._stop.is_set():
                    return False
                if self.server.split_responses:
                    self.sock.sendall(frame[:100])
                    time.sleep(0.005)
                    self.sock.sendall(frame[100:])
                else:
                    self.sock.sendall(frame)
            return True
        except OSError:
            self.close()
            return False

    def push_response(self, power: int, tick: int) -> bool:
        """服务端主动下发一条 response。

        - 新生成 requestid 和当前 timestamp；
        - 使用本连接创建时的 token 加密；
        - 不经过 handle_request，也不增加服务端接收计数表。
        客户端会把主动下发当作普通 response 处理（不需要有对应请求）。
        """
        request_id = proto.new_request_id()
        response = proto.build_response(power, tick, request_id)
        try:
            frame = proto.encode_frame(response, self.key)
        except proto.ProtocolError as exc:
            log(f"{self.label()} push blocked: {exc}")
            return False
        if self.send_frame(frame):
            log(f"{self.label()} pushed response power={power} tick={tick} "
                f"requestid={request_id}")
            return True
        return False

    def push(self, power: int, tick: Optional[int] = None) -> bool:
        """业务侧按连接对象主动下发：tick 不传就用 server.default_tick。"""
        return self.push_response(
            power, self.server.default_tick if tick is None else tick)

    def disconnect(self) -> None:
        """业务侧主动断开这条连接（幂等，重复调用无副作用）。"""
        self.close()

    def label(self) -> str:
        """日志用：name@ip:port 或 @ip:port。"""
        if self.name:
            return f"{self.name}@{self.address[0]}:{self.address[1]}"
        return f"@{self.address[0]}:{self.address[1]}"


class TcpTestServer:
    """监听端口、管理所有 ClientConnection。

    典型使用：
        server = TcpTestServer(port=9000, token="change-me")
        server.start()
        ...
        server.stop()

    业务共享状态：
        business_state / business_lock 是留给业务代码用的空地；
        可以自己换成数据库连接、设备表、MQTT 客户端等。
    """

    def __init__(self, host: str = "127.0.0.1", port: int = 9000,
                 token: str = "change-me", keepalive: int = 90,
                 default_tick: int = 2, split_responses: bool = False,
                 verbose: bool = True, debug: bool = False,
                 business: Optional[BusinessHandler] = None) -> None:
        self.host = host
        self.port = port
        self.token = token

        # 所有连接共用同一个 token/key；如果你的业务需要“每个设备一个 token”，
        # 需要把 key 改成按 name 查表（并在 handle_request 前完成选择）。
        self.key = proto.key_from_token(token)

        # 服务端空闲断开阈值（秒）：超过这么久没有成功 request/keepalive 就断开。
        self.keepalive = keepalive

        # response.tick 的默认值，handle_request 可以直接使用或覆盖。
        self.default_tick = default_tick

        # 仅测试用：把每个响应拆成两次 sendall，验证客户端拆包。
        self.split_responses = split_responses
        self.verbose = verbose
        self.debug = debug

        # 业务回调；None 表示使用 ClientConnection.handle_request 的默认实现。
        # 每条 request 都会调用 business(message, ctx)。
        self.business = business

        self._socket: Optional[socket.socket] = None
        self._connections: set[ClientConnection] = set()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._accept_thread: Optional[threading.Thread] = None

        # 业务逻辑可用的共享状态。默认没用到；示例见 handle_request 示例 4。
        # 访问时请使用 business_lock。
        self.business_lock = threading.Lock()
        self.business_state: dict[str, object] = {}

    def start(self) -> None:
        """绑定端口并启动 accept 线程。"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.host, self.port))
        sock.listen(16)
        sock.settimeout(0.5)
        self._socket = sock
        self.port = sock.getsockname()[1]  # port=0 时用于测试取得实际端口
        log(f"listening on {self.host}:{self.port} (pid={os.getpid()})")
        log(f"token length={len(self.token)}, key fingerprint="
            f"{self.key.hex()[:12]}... (token value is never written to logs)")
        self._accept_thread = threading.Thread(
            target=self._accept_loop, name="tcp-test-accept", daemon=True)
        self._accept_thread.start()
        _LIVE_SERVERS.add(self)

    def _accept_loop(self) -> None:
        """接受新连接；每个连接一个 reader 线程。"""
        while not self._stop.is_set():
            try:
                conn, address = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            connection = ClientConnection(self, conn, address)
            with self._lock:
                self._connections.add(connection)
            log(f"accepted {address[0]}:{address[1]}")
            connection.start()

    def stop(self) -> None:
        """停止监听、关闭全部连接，并等后台线程退出（最多 2 秒）。

        线程都是 daemon；不等它们退出，进程收尾时它们可能还在 print()，
        会触发 "Fatal Python error: _enter_buffered_busy" 把进程 abort 掉
        （测试全绿，但退出码变成非 0）。
        """
        self._stop.set()
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass
        with self._lock:
            connections = list(self._connections)
        for connection in connections:
            connection.close()

        threads = [self._accept_thread] if self._accept_thread is not None else []
        threads += [connection._thread for connection in connections]
        for thread in threads:
            if thread.is_alive():
                thread.join(timeout=2.0)
        _LIVE_SERVERS.discard(self)

    def _remove(self, connection: ClientConnection) -> None:
        with self._lock:
            self._connections.discard(connection)

    def connections(self) -> list[ClientConnection]:
        """获取当前连接快照（业务/控制台都可以用）。"""
        with self._lock:
            return list(self._connections)

    @property
    def connections_by_name(self) -> dict[str, ClientConnection]:
        """name -> 连接对象 的表，每次读取时按当前连接现算。

        - 只在客户端第一次上报 name（request/keepalive）之后才出现；
        - 连接断开后自动消失，不需要业务自己维护；
        - 同一个 name 有多条连接时这里只留一条（后遍历到的），
          要给同名全部发消息请用 push(name=...) 或自行遍历 connections()。
        """
        table: dict[str, ClientConnection] = {}
        for connection in self.connections():
            if connection.name:
                table[connection.name] = connection
        return table

    def connection(self, name: str) -> Optional[ClientConnection]:
        """按 name 取连接对象；没上线/已断开返回 None。"""
        return self.connections_by_name.get(name)

    @property
    def stop_event(self) -> threading.Event:
        """服务停止信号，stop() 时被 set()。

        业务自己起的定时线程请这样写，才能随 Ctrl+C 干净退出：
            while not server.stop_event.wait(5.0):
                ...
        """
        return self._stop

    def push(self, power: int, tick: Optional[int] = None,
             name: Optional[str] = None) -> int:
        """向匹配的连接主动下发 response，返回成功发送的连接数。

        - 都不传：发给所有连接；
        - name="lamp"：只发给最近一次 request/keepalive 里 name 为 lamp 的连接。
        名字由框架从连接上现取，断开/重连都不用你维护连接表。
        """
        tick = self.default_tick if tick is None else tick
        sent = 0
        for connection in self.connections():
            if name is not None and connection.name != name:
                continue
            if connection.push_response(power, tick):
                sent += 1
        return sent

    def kick(self, name: str) -> int:
        """按 name 断开连接，返回断开数量。"""
        kicked = 0
        for connection in self.connections():
            if connection.name == name:
                connection.close()
                kicked += 1
        return kicked


@dataclass
class ServerArgs:
    """命令行参数。"""
    host: str = "127.0.0.1"
    port: int = 9000
    token: str = "change-me"
    keepalive: int = 90
    tick: int = 2
    split: bool = False
    quiet: bool = False
    no_console: bool = False
    debug: bool = False
    log_file: Optional[str] = None


def _parse_args(argv: list[str]) -> ServerArgs:
    parser = argparse.ArgumentParser(description="TCP interface reference test server")
    parser.add_argument("--host", default="127.0.0.1",
                        help="监听地址，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=9000,
                        help="监听端口，默认 9000")
    parser.add_argument("--token", default="change-me",
                        help="AES-256 密钥来源 token，必须和模组 UI1 一致")
    parser.add_argument("--keepalive", type=int, default=90,
                        help="服务端空闲断开秒数，默认 90；应大于客户端 keepalivetime(默认 60)")
    parser.add_argument("--tick", type=int, default=2,
                        help="默认 response.tick（红石保持游戏刻数），默认 2")
    parser.add_argument("--split", action="store_true",
                        help="响应拆成两次 sendall，仅用于测试拆包")
    parser.add_argument("--quiet", action="store_true",
                        help="不打印每条请求的详细日志")
    parser.add_argument("--no-console", action="store_true",
                        help="不启用控制台（后台/测试用）")
    parser.add_argument("--debug", action="store_true",
                        help="打印每帧原始长度/前后字节（排查链路问题）")
    parser.add_argument("--log-file", default=DEFAULT_LOG_FILE,
                        help=f"日志文件路径，默认 {DEFAULT_LOG_FILE}")
    parser.add_argument("--no-log-file", action="store_true",
                        help="只输出控制台，不写文件")
    parsed = parser.parse_args(argv)
    log_file = None if parsed.no_log_file else parsed.log_file
    return ServerArgs(parsed.host, parsed.port, parsed.token, parsed.keepalive,
                      parsed.tick, parsed.split, parsed.quiet, parsed.no_console,
                      parsed.debug, log_file)


def _console(server: TcpTestServer) -> None:
    """简单控制台，方便手工测试主动下发/踢人。"""
    print("commands: list | push <power> [tick] [name] | kick <name> | quit", flush=True)
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        parts = line.split()
        command = parts[0].lower()
        try:
            if command in {"quit", "exit"}:
                break

            if command == "list":
                connections = server.connections()
                if not connections:
                    print("no connections", flush=True)
                for connection in connections:
                    print(f"  {connection.label()}  requests={connection.requests} "
                          f"keepalives={connection.keepalives} "
                          f"counts={connection.replay.size}", flush=True)
                continue

            if command == "push":
                if len(parts) < 2:
                    print("usage: push <power> [tick] [name]", flush=True)
                    continue
                power = int(parts[1])
                tick = int(parts[2]) if len(parts) >= 3 else server.default_tick
                name = parts[3] if len(parts) >= 4 else None
                sent = server.push(power, tick, name)
                print(f"pushed to {sent} connection(s)", flush=True)
                continue

            if command == "kick":
                if len(parts) < 2:
                    print("usage: kick <name>", flush=True)
                    continue
                print(f"kicked {server.kick(parts[1])} connection(s)", flush=True)
                continue

            print("unknown command; try: list | push | kick | quit", flush=True)
        except ValueError as exc:
            print(f"invalid command argument: {exc}", flush=True)


def _business_label(business: Optional[BusinessHandler]) -> str:
    """日志里显示当前业务回调来自哪个文件/函数。"""
    if business is None:
        return "(built-in handle_request)"
    module = getattr(business, "__module__", "?")
    name = getattr(business, "__qualname__", repr(business))
    return f"{module}.{name}"


def serve(business: Optional[BusinessHandler] = None,
          argv: Optional[list[str]] = None,
          setup: Optional[BusinessSetup] = None) -> int:
    """启动服务 + 控制台，返回进程退出码；业务文件的标准写法就是调用它。

        from tcp_test_server import serve
        raise SystemExit(serve(handle))

    business=None 时使用 ClientConnection.handle_request 的默认实现。
    argv=None 时读取 sys.argv[1:]，因此 --port/--token 等参数照常可用。
    setup 在端口监听成功后调用一次，参数是 TcpTestServer，用来启动
    定时线程等后台任务；线程里用 server.stop_event 判断何时退出。
    """
    global _LOG_FILE
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    configure_logging(args.log_file)
    log("=== TCP interface test server ===")
    log(f"script       : {Path(__file__).resolve()}")
    log(f"python       : {sys.version.split()[0]}")
    log(f"log file     : {args.log_file or '(console only)'}")
    log(f"debug frames : {args.debug}")
    log(f"business     : {_business_label(business)}")

    try:
        server = TcpTestServer(args.host, args.port, args.token, args.keepalive,
                               args.tick, args.split, not args.quiet, args.debug,
                               business=business)
        server.start()
    except OSError as exc:
        # 最常见：端口被占用、监听地址不可用、防火墙/权限。
        log(f"FAILED TO START: {exc!r}")
        log("check: is another tcp_test_server.py already running? wrong --host/--port?")
        return 1

    log(f"address      : {server.host}")
    log(f"port         : {server.port}")
    log(f"keepalive    : {server.keepalive}s")
    log(f"default tick : {server.default_tick}")
    log(f"split writes : {server.split_responses}")
    log("Use these values in the in-game global network settings.")
    log("WARNING: intended for intranet/testing only.")

    if setup is not None:
        # 业务自己的后台线程在这里启动；失败就直接退出，避免半启动状态。
        try:
            setup(server)
        except Exception as exc:  # 业务初始化出错，不必继续跑
            log(f"business setup failed: {exc!r}")
            server.stop()
            return 1

    try:
        # 非交互环境（比如后台 job）没有 stdin，不能读控制台；
        # 这时保持进程存活，靠 Ctrl+C / 外部终止。
        if args.no_console or not sys.stdin or not sys.stdin.isatty():
            while True:
                time.sleep(1)
        else:
            _console(server)
    except KeyboardInterrupt:
        log("stopping...")
    finally:
        server.stop()
        log("server stopped")
        with _LOG_LOCK:
            if _LOG_FILE is not None:
                try:
                    _LOG_FILE.close()
                except OSError:
                    pass
                _LOG_FILE = None
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    """命令行入口：直接运行本文件时使用，等同于 serve(None, argv)。"""
    return serve(None, argv)


if __name__ == "__main__":
    raise SystemExit(main())