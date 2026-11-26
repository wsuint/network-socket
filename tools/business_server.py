#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""业务文件模板：整个文件只有 handle() 和 ticker() 需要你改。

启动（在 tools 目录或项目根目录都行，只要路径指向本文件）：
    python tools/business_server.py --port 9000 --token change-me

参数与服务端完全一致：--host / --port / --token / --keepalive / --tick /
--split / --quiet / --no-console / --debug / --log-file / --no-log-file。
控制台命令不变：list | push <power> [tick] [name] | kick <name> | quit。

框架（tcp_test_server.py）负责：定长 256 字节帧、AES-GCM 加解密与填充、
粘包/拆包、时间戳与 requestid 重放校验、心跳、空闲断开、日志、requestid
回填。你只负责：给定一条 request，返回 response 的 (power, tick)。

handle() 约定
    入参
        request.type       "request"
        request.name       方块配置里的 name
        request.power      请求的红石强度 0..15
        request.requestid  本次请求 id（框架自动回填到 response）
        request.timestamp  客户端 Unix 秒（框架已校验 ±10 秒）
        ctx                连接上下文，见下
    返回
        (power, tick)      power 0..15，tick 保持的游戏刻数
        None               本次不回复（不计数、不发送）
    抛异常                 框架记一条 business error，连接继续存活

按 name 找连接
    server.connections_by_name 是 name -> 连接对象 的表，框架维护：
        table = server.connections_by_name        # dict[str, ClientConnection]
        connection = table.get("lamp")            # 不在线就是 None
        connection = server.connection("lamp")    # 等价写法
    连接断开后表里自动消失；同一 name 有多条连接时表里只留一条，
    要给同名全部发消息就自己遍历 server.connections()。

连接对象能干什么
        connection.name / .address / .label()
        connection.push(power, tick=None)         主动下发一条 response
        connection.disconnect()                   主动断开（幂等）
    也可以整服务端范围操作：
        server.push(power, tick=None, name=None)  不传 name 就是全部连接
        server.kick(name)                         按 name 断开，返回条数

ctx 提供
    ctx.name / ctx.address   最近一次 request/keepalive 的 name、对端地址
    ctx.log(msg)             写日志（自动带 name@ip:port 前缀）
    ctx.push(power, tick)    向本条连接主动下发一条 response
    ctx.server               TcpTestServer，共享状态放在 business_state 里

注意
    - handle() 运行在该连接的 reader 线程里：不要 sleep/阻塞太久。
    - 跨连接共享数据必须加锁（server.business_lock）。
    - power 超出 0..15 或 tick < 0 会被框架拒绝发送。
    - 主动下发不会刷新“最后活跃时间”：客户端长时间只收不发时，
      --keepalive 到点仍会被服务端断开，需要客户端自己发心跳，
      或者把 --keepalive 调大。
"""
from __future__ import annotations

import threading
from typing import Optional

import tcp_protocol as proto
from tcp_test_server import BusinessContext, TcpTestServer, log, serve

# PUSH_INTERVAL = 5.0     # 秒：ticker 每 5 秒发一次
# PUSH_TICK = 40          # 推送的 response.tick（红石保持的游戏刻数）
# PUSH_TARGET = "lamp"    # 推给哪个 name；上线后才会命中


def handle(request: proto.Message, ctx: BusinessContext) -> Optional[tuple[int, int]]:
    """收到一条 request：这里写你自己的业务，默认原样回声 power。"""
    table = ctx.server.connections_by_name
    ctx.log(f"name={request.name} power={request.power} "
            f"在线设备={sorted(table)}")

    # # 示例：记一下最近的请求，供 ticker 之类的地方读取。
    # with ctx.server.business_lock:
    #     ctx.server.business_state["power"] = request.power
    #     ctx.server.business_state["last_name"] = request.name

    # return request.power, ctx.server.default_tick


def ticker(server: TcpTestServer) -> None:
    """后台线程：每 PUSH_INTERVAL 秒给 PUSH_TARGET 发一条 response。

    线程必须 daemon 且用 stop_event 等待，才能随 Ctrl+C 干净退出。
    """
    while not server.stop_event.wait(10):
        loopconnection = server.connection("loop")
        if loopconnection:
            print("loop is send")
            loopconnection.push(15, 40)
            log(f"ticker pushed power={15} tick={40} to loop")

    # for name, connection in server.connections_by_name.items():
    #      if name.startswith("loop" + ':'):
    #         x,power = name.split(':', 1)
    #         power = int(power)
    #         connection.push(15, power)
    #         log(f"ticker pushed power={15} tick={power} to {name}")
    
    # while not server.stop_event.wait(PUSH_INTERVAL):
    #     print(server.connections_by_name)

        # loopconnection = server.connection("loop")
        # if loopconnection:
        #     print("loop1 is send")
        #     loopconnection.push(15, 40)
        #     log(f"ticker pushed power={15} tick={40} to loop")


        # sld = server.connection("sld")
        # print(f"sld connection: {sld}")
        # if sld:
        #     print("sld is send")
        #     sld.push(15, 20)
        #     log(f"ticker pushed power={15} tick={20} to sld")
        # if connection is None:
        #     log(f"ticker skipped: {PUSH_TARGET} 不在线")
        #     continue
        # with server.business_lock:
        #     power = int(server.business_state.get("power", 0))
        # if connection.push(15, 40):
        #     log(f"ticker pushed power={15} tick={40} to {PUSH_TARGET}")


def setup(server: TcpTestServer) -> None:
    """serve() 在监听成功后调用一次，适合起定时线程/加载配置。"""
    threading.Thread(target=ticker, args=(server,),
                     name="business-ticker", daemon=True).start()



# ---------------------------------------------------------------------------
# 常见写法（把上面的 return 注释掉，再启用其中一段）
#
# 1) 按 name 区分设备
#     if request.name == "lamp":
#         return 15, 20
#     return 0, 20
#
# 2) 按 name 找到那条连接，主动发一条（不等客户端请求）
#     connection = ctx.server.connection("lamp")
#     if connection is not None:
#         connection.push(15, 40)
#
# 3) 按 name 主动断开连接
#     connection = ctx.server.connection("lamp")
#     if connection is not None:
#         connection.disconnect()
#     # 或者一次踢掉所有同名连接：ctx.server.kick("lamp")
#
# 4) 遍历所有在线连接，自己决定发什么
#     for connection in ctx.server.connections():
#         connection.push(15, 40)
#
# 5) 服务端共享状态（跨连接，必须加锁）
#     with ctx.server.business_lock:
#         count = int(ctx.server.business_state.get(request.name, 0)) + 1
#         ctx.server.business_state[request.name] = count
#     return (15 if count % 2 else 0), 20
#
# 6) 不回复
#     return None
#
# 7) 耗时任务别卡住 reader 线程
#     threading.Thread(target=slow_work, args=(request,), daemon=True).start()
#     return None
# ---------------------------------------------------------------------------


if __name__ == "__main__":
    raise SystemExit(serve(handle, setup=setup))