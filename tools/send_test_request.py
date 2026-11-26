#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立测试客户端：不启动 Minecraft，直接验证测试服务端。

用途：
- 判断“服务端是否真的收到了 request / 是否能返回 response”；
- 如果这个脚本能 PASS，但 Minecraft 里服务端没有 request 日志，
  问题就在模组侧（连接状态、方块 name、红石检测面/信号变化）。

典型用法：
    # 先启动服务端
    python tools/tcp_test_server.py --port 9000 --token change-me

    # 再另开一个终端
    python tools/send_test_request.py --port 9000 --token change-me --name t2:2 --power 15

输出会显示：
    连接 -> 发送的 256 字节帧 -> 收到的 256 字节帧 -> 解密后的 response 字段
"""
from __future__ import annotations

import argparse
import socket
import sys
import time
from typing import Optional

import tcp_protocol as proto


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def recv_exact(sock: socket.socket, size: int, timeout: float) -> Optional[bytes]:
    """读满 size 字节；超时返回 None，连接关闭抛 EOFError。"""
    buf = bytearray()
    deadline = time.monotonic() + timeout
    while len(buf) < size:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        sock.settimeout(remaining)
        try:
            chunk = sock.recv(size - len(buf))
        except socket.timeout:
            return None
        if not chunk:
            raise EOFError("server closed the connection")
        buf.extend(chunk)
    return bytes(buf)


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Send one TCP interface request without Minecraft")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--token", default="change-me",
                        help="必须和测试服务端、模组 UI1 的 token 一致")
    parser.add_argument("--name", default="test",
                        help="方块配置的 name，会作为 request.name 发送")
    parser.add_argument("--power", type=int, default=15,
                        help="红石强度 0..15，默认 15")
    parser.add_argument("--timeout", type=float, default=5.0,
                        help="等待响应的秒数，默认 5（业务里 sleep 2 也能等到）")
    parser.add_argument("--keepalive", action="store_true",
                        help="发完 request 后再发一条 keepalive 并验证 keepaliveok")
    parser.add_argument("--split", action="store_true",
                        help="把 256 字节帧拆成两次发送，测试服务端拆包")
    args = parser.parse_args(argv)

    if not 0 <= args.power <= 15:
        log("power must be 0..15")
        return 2

    key = proto.key_from_token(args.token)
    log(f"connecting to {args.host}:{args.port} ...")
    try:
        sock = socket.create_connection((args.host, args.port), timeout=args.timeout)
    except OSError as exc:
        log(f"CONNECT FAILED: {exc!r}")
        log("check: server running? --host/--port correct? firewall?")
        return 1

    try:
        # 构造 request。requestid/timestamp 都由协议层生成。
        text, request_id = proto.build_request(args.name, args.power)
        frame = proto.encode_frame(text, key)
        log(f"request fields : name={args.name} power={args.power} requestid={request_id}")
        log(f"request frame  : {len(frame)} bytes, head={frame[:12].hex()}")
        if len(frame) != proto.FRAME_SIZE:
            log(f"BUG: frame length {len(frame)} != {proto.FRAME_SIZE}")
            return 1

        if args.split:
            sock.sendall(frame[:100])
            time.sleep(0.01)
            sock.sendall(frame[100:])
        else:
            sock.sendall(frame)
        log("request sent, waiting for response ...")

        try:
            response_frame = recv_exact(sock, proto.FRAME_SIZE, args.timeout)
        except EOFError:
            log("server closed the connection while waiting for response")
            return 1
        if response_frame is None:
            log(f"NO RESPONSE within {args.timeout}s")
            log("check server log: tools/tcp_test_server.log")
            log("  - 'message received' means request reached server")
            log("  - 'invalid business result' means handle_request returned power outside 0..15")
            log("  - no 'message received' means token/port/server/network issue")
            return 1

        log(f"response frame : {len(response_frame)} bytes, head={response_frame[:12].hex()}")
        try:
            response_text = proto.decode_frame(response_frame, key)
            response = proto.parse_message(response_text)
        except proto.ProtocolError as exc:
            log(f"DECODE/PARSE FAILED: {exc!r}")
            log("check: token matches? response frame not 256 bytes?")
            return 1

        log(f"response fields: type={response.type} power={response.power} "
            f"tick={response.tick} requestid={response.requestid} "
            f"timestamp={response.timestamp}")
        if response.type != "response":
            log(f"FAIL: expected response, got {response.type}")
            return 1
        if response.requestid != request_id:
            log(f"FAIL: requestid mismatch (sent {request_id}, got {response.requestid})")
            return 1

        if args.keepalive:
            keep_text, keep_id = proto.build_keepalive(args.name)
            keep_frame = proto.encode_frame(keep_text, key)
            sock.sendall(keep_frame)
            log(f"keepalive sent : requestid={keep_id}")
            try:
                keep_response_frame = recv_exact(sock, proto.FRAME_SIZE, args.timeout)
            except EOFError:
                log("server closed the connection while waiting for keepaliveok")
                return 1
            if keep_response_frame is None:
                log(f"NO KEEPALIVEOK within {args.timeout}s")
                return 1
            keep_response = proto.parse_message(
                proto.decode_frame(keep_response_frame, key))
            log(f"keepaliveok    : type={keep_response.type} requestid={keep_response.requestid}")
            if keep_response.type != "keepaliveok" or keep_response.requestid != keep_id:
                log("FAIL: keepaliveok mismatch")
                return 1

        log("PASS")
        return 0
    finally:
        try:
            sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    raise SystemExit(main())