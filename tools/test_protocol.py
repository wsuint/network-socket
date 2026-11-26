#!/usr/bin/env python3
"""Tests for the Python reference protocol/server, including sticky/split frames."""
from __future__ import annotations

import contextlib
import io
import os
import socket
import sys
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tcp_protocol as proto  # noqa: E402
from tcp_test_server import (  # noqa: E402
    BusinessContext,
    ClientConnection,
    TcpTestServer,
    serve,
)


class TestClient:
    def __init__(self, host: str, port: int, token: str, name: str = "test") -> None:
        self.name = name
        self.key = proto.key_from_token(token)
        self.sock = socket.create_connection((host, port), timeout=2.0)
        self.sock.settimeout(2.0)
        self._buffer = bytearray()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def send_text(self, text: str, split: bool = False) -> None:
        frame = proto.encode_frame(text, self.key)
        if split:
            self.sock.sendall(frame[:128])
            time.sleep(0.01)
            self.sock.sendall(frame[128:])
        else:
            self.sock.sendall(frame)

    def send_request(self, power: int, split: bool = False,
                     request_id: str | None = None,
                     timestamp: int | None = None) -> str:
        text, request_id = proto.build_request(self.name, power, request_id, timestamp)
        self.send_text(text, split)
        return request_id

    def send_keepalive(self, split: bool = False,
                       request_id: str | None = None) -> str:
        text, request_id = proto.build_keepalive(self.name, request_id)
        self.send_text(text, split)
        return request_id

    def read_frame(self, timeout: float | None = None) -> bytes | None:
        old_timeout = self.sock.gettimeout()
        if timeout is not None:
            self.sock.settimeout(timeout)
        try:
            while len(self._buffer) < proto.FRAME_SIZE:
                chunk = self.sock.recv(4096)
                if not chunk:
                    raise EOFError("connection closed")
                self._buffer.extend(chunk)
            frame = bytes(self._buffer[:proto.FRAME_SIZE])
            del self._buffer[:proto.FRAME_SIZE]
            return frame
        except socket.timeout:
            return None
        finally:
            if timeout is not None:
                self.sock.settimeout(old_timeout)

    def read_message(self, timeout: float | None = None) -> proto.Message | None:
        frame = self.read_frame(timeout)
        if frame is None:
            return None
        return proto.parse_message(proto.decode_frame(frame, self.key))

    def wait_eof(self, timeout: float = 2.0) -> bool:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                chunk = self.sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                return True
            if chunk == b"":
                return True
        return False


class ProtocolUnitTests(unittest.TestCase):
    def test_frame_roundtrip_and_padding(self) -> None:
        key = proto.key_from_token("secret")
        text = "type=response,power=9,tick=2,requestid=ABC_123-x,timestamp=1700000000"
        frame = proto.encode_frame(text, key)
        self.assertEqual(len(frame), 256)
        self.assertEqual(proto.decode_frame(frame, key), text)

    def test_frame_max_plaintext_roundtrip(self) -> None:
        key = proto.key_from_token("secret")
        text = "x" * proto.PADDED_PLAINTEXT_SIZE
        frame = proto.encode_frame(text, key)
        self.assertEqual(len(frame), 256)
        self.assertEqual(frame[-1:], b"\n")
        self.assertEqual(proto.decode_frame(frame, key), text)

    def test_frame_too_large_is_blocked(self) -> None:
        key = proto.key_from_token("secret")
        with self.assertRaises(proto.ProtocolError):
            proto.encode_frame("x" * (proto.PADDED_PLAINTEXT_SIZE + 1), key)

    def test_name_utf8_byte_limit(self) -> None:
        key = proto.key_from_token("secret")
        exactly_80 = "a" * 80
        text, _ = proto.build_keepalive(exactly_80, "a")
        self.assertEqual(len(proto.encode_frame(text, key)), 256)
        # 27 Chinese characters are 81 UTF-8 bytes, even though there are only 27 chars.
        too_long = "中" * 27
        with self.assertRaises(proto.ProtocolError):
            proto.build_keepalive(too_long, "a")

    def test_wrong_key_fails(self) -> None:
        frame = proto.encode_frame("type=keepaliveok,requestid=abc,timestamp=1700000000",
                                   proto.key_from_token("a"))
        with self.assertRaises(proto.ProtocolError):
            proto.decode_frame(frame, proto.key_from_token("b"))

    def test_garbage_frame_fails(self) -> None:
        with self.assertRaises(proto.ProtocolError):
            proto.decode_frame(b"!" * 256, proto.key_from_token("a"))

    def test_parse_request_and_order(self) -> None:
        text = "type=request,name=n,power=15,requestid=a-1,timestamp=1700000000"
        message = proto.parse_message(text)
        self.assertEqual((message.type, message.name, message.power,
                          message.requestid, message.timestamp),
                         ("request", "n", 15, "a-1", 1700000000))
        with self.assertRaises(proto.ProtocolError):
            proto.parse_message("type=request,power=15,name=n,requestid=a,timestamp=1")
        with self.assertRaises(proto.ProtocolError):
            proto.parse_message("type=response,power=16,tick=2,requestid=a,timestamp=1")
        with self.assertRaises(proto.ProtocolError):
            proto.parse_message("type=request,name=a=b,power=1,requestid=a,timestamp=1")

    def test_replay_guard_reset(self) -> None:
        guard = proto.ReplayGuard()
        self.assertEqual(guard.increment("a"), 1)
        self.assertEqual(guard.record_success("a"), 2)
        self.assertTrue(guard.contains("a"))
        guard.reset()
        self.assertFalse(guard.contains("a"))
        self.assertEqual(guard.size, 0)


class ServerIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # Protocol tests must be deterministic even if the user is editing
        # ClientConnection.handle_request() for their own business.
        cls._original_business = ClientConnection.handle_request
        ClientConnection.handle_request = (
            lambda connection, message: (message.power, connection.server.default_tick)
        )
        cls.server = TcpTestServer(port=0, token="test-token", keepalive=5,
                                   default_tick=2, verbose=False)
        cls.server.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.stop()
        ClientConnection.handle_request = cls._original_business

    def setUp(self) -> None:
        self.client = TestClient("127.0.0.1", self.server.port, "test-token")
        self.addCleanup(self.client.close)

    def test_request_response(self) -> None:
        request_id = self.client.send_request(13, split=True)
        message = self.client.read_message(timeout=2.0)
        self.assertIsNotNone(message)
        self.assertEqual(message.type, "response")
        self.assertEqual(message.power, 13)
        self.assertEqual(message.tick, 2)
        self.assertEqual(message.requestid, request_id)
        self.assertTrue(proto.timestamp_is_valid(message.timestamp))

    def test_sticky_frames_and_split_responses(self) -> None:
        self.server.split_responses = True
        try:
            first = proto.build_request("test", 1)[0]
            second = proto.build_request("test", 2)[0]
            frame1 = proto.encode_frame(first, self.client.key)
            frame2 = proto.encode_frame(second, self.client.key)
            self.client.sock.sendall(frame1 + frame2)
            message1 = self.client.read_message(timeout=2.0)
            message2 = self.client.read_message(timeout=2.0)
            self.assertEqual(message1.power, 1)
            self.assertEqual(message2.power, 2)
        finally:
            self.server.split_responses = False

    def test_keepalive_and_matching_ok(self) -> None:
        request_id = self.client.send_keepalive(split=True)
        message = self.client.read_message(timeout=2.0)
        self.assertIsNotNone(message)
        self.assertEqual(message.type, "keepaliveok")
        self.assertEqual(message.requestid, request_id)

    def test_server_push_has_new_requestid(self) -> None:
        request_id = self.client.send_request(4)
        first = self.client.read_message(timeout=2.0)
        self.assertEqual(first.requestid, request_id)

        sent = self.server.push(7, 5, "test")
        self.assertEqual(sent, 1)
        message = self.client.read_message(timeout=2.0)
        self.assertIsNotNone(message)
        self.assertEqual(message.type, "response")
        self.assertEqual(message.power, 7)
        self.assertEqual(message.tick, 5)
        self.assertTrue(proto.timestamp_is_valid(message.timestamp))

    def test_duplicate_requestid_is_blocked_but_connection_survives(self) -> None:
        request_id = self.client.send_request(4)
        first = self.client.read_message(timeout=2.0)
        self.assertEqual(first.requestid, request_id)

        self.client.send_request(4, request_id=request_id)
        self.assertIsNone(self.client.read_message(timeout=0.5))

        # A different requestid still works.
        self.client.send_request(5)
        message = self.client.read_message(timeout=2.0)
        self.assertEqual(message.power, 5)

    def test_invalid_timestamp_is_blocked(self) -> None:
        self.client.send_request(3, timestamp=int(time.time()) - 11)
        self.assertIsNone(self.client.read_message(timeout=0.5))
        self.client.send_request(3)
        self.assertEqual(self.client.read_message(timeout=2.0).power, 3)

    def test_replay_limit_closes_connection(self) -> None:
        request_id = self.client.send_request(1)
        self.client.read_message(timeout=2.0)
        for _ in range(10):
            self.client.send_request(1, request_id=request_id)
        self.assertTrue(self.client.wait_eof(timeout=2.0))

    def test_business_hook_can_rewrite_power_and_tick(self) -> None:
        original = ClientConnection.handle_request

        def custom_business(connection: ClientConnection,
                            message: proto.Message):
            # Example of changing business code: invert power and hold 7 ticks.
            return 15 - message.power, 7

        ClientConnection.handle_request = custom_business
        try:
            self.client.send_request(3)
            message = self.client.read_message(timeout=2.0)
            self.assertIsNotNone(message)
            self.assertEqual(message.type, "response")
            self.assertEqual(message.power, 12)
            self.assertEqual(message.tick, 7)
        finally:
            ClientConnection.handle_request = original

    def test_idle_timeout(self) -> None:
        server = TcpTestServer(port=0, token="idle-token", keepalive=1,
                               default_tick=1, verbose=False)
        server.start()
        client = TestClient("127.0.0.1", server.port, "idle-token")
        try:
            self.assertTrue(client.wait_eof(timeout=3.0))
        finally:
            client.close()
            server.stop()


class BusinessCallbackTests(unittest.TestCase):
    """business= 回调路径：独立于 ServerIntegrationTests 的 handle_request 替换。"""

    def _start(self, business, token: str) -> tuple[TcpTestServer, TestClient]:
        server = TcpTestServer(port=0, token=token, business=business, verbose=False)
        server.start()
        self.addCleanup(server.stop)
        client = TestClient("127.0.0.1", server.port, token)
        self.addCleanup(client.close)
        return server, client

    def test_business_callback_replaces_inline_logic(self) -> None:
        seen: list[tuple[str, str | None, str]] = []

        def business(message: proto.Message, ctx: BusinessContext):
            seen.append((message.name, ctx.name, ctx.address[0]))
            return 15 - message.power, 9

        server, client = self._start(business, "biz-token")
        request_id = client.send_request(3)
        message = client.read_message(timeout=2.0)
        self.assertIsNotNone(message)
        self.assertEqual(message.type, "response")
        self.assertEqual(message.power, 12)
        self.assertEqual(message.tick, 9)
        self.assertEqual(message.requestid, request_id)
        self.assertEqual(seen, [("test", "test", "127.0.0.1")])

    def test_business_context_push_without_reply(self) -> None:
        def business(message: proto.Message, ctx: BusinessContext):
            ctx.push(11, 7)
            return None  # 不回复，客户端只应收到主动下发的那一条

        server, client = self._start(business, "push-token")
        request_id = client.send_request(1)
        pushed = client.read_message(timeout=2.0)
        self.assertIsNotNone(pushed)
        self.assertEqual((pushed.type, pushed.power, pushed.tick),
                         ("response", 11, 7))
        self.assertNotEqual(pushed.requestid, request_id)

    def test_ticker_thread_pushes_periodically(self) -> None:
        server, client = self._start(None, "tick-token")
        client.send_request(2)
        self.assertIsNotNone(client.read_message(timeout=2.0))  # name 已上报

        stop = threading.Event()

        def ticker() -> None:
            while not stop.wait(0.1):
                server.push(9, 11, name="test")

        thread = threading.Thread(target=ticker, daemon=True)
        thread.start()
        try:
            first = client.read_message(timeout=2.0)
            second = client.read_message(timeout=2.0)
        finally:
            stop.set()
            thread.join(2.0)
        self.assertIsNotNone(first)
        self.assertIsNotNone(second)
        self.assertEqual((first.power, first.tick), (9, 11))
        self.assertEqual((second.power, second.tick), (9, 11))
        self.assertNotEqual(first.requestid, second.requestid)

    def test_business_can_target_and_disconnect_by_name(self) -> None:
        events: list[str] = []

        def business(message: proto.Message, ctx: BusinessContext):
            if message.name == "door":
                connection = ctx.server.connection("lamp")
                if connection is not None:
                    connection.push(3, 4)
                    events.append("pushed")
                    connection.disconnect()
                    events.append("disconnected")
            return 1, 2

        server = TcpTestServer(port=0, token="relay", business=business,
                               verbose=False)
        server.start()
        self.addCleanup(server.stop)
        lamp = TestClient("127.0.0.1", server.port, "relay", name="lamp")
        self.addCleanup(lamp.close)
        door = TestClient("127.0.0.1", server.port, "relay", name="door")
        self.addCleanup(door.close)
        lamp.send_request(5)
        self.assertIsNotNone(lamp.read_message(timeout=2.0))
        door.send_request(5)
        self.assertIsNotNone(door.read_message(timeout=2.0))

        pushed = lamp.read_message(timeout=2.0)
        self.assertIsNotNone(pushed)
        self.assertEqual((pushed.power, pushed.tick), (3, 4))
        self.assertTrue(lamp.wait_eof(timeout=2.0))
        self.assertEqual(events, ["pushed", "disconnected"])

    def test_stop_event_set_after_stop(self) -> None:
        server, _ = self._start(None, "stop-token")
        self.assertFalse(server.stop_event.is_set())
        server.stop()
        self.assertTrue(server.stop_event.is_set())


class ConnectionTableTests(unittest.TestCase):
    """name -> 连接对象 的表：连接建立/断开自动反映，可按名字发送与断开。"""

    def setUp(self) -> None:
        self.server = TcpTestServer(port=0, token="tbl", verbose=False)
        self.server.start()
        self.addCleanup(self.server.stop)

    def _connect(self, name: str) -> TestClient:
        client = TestClient("127.0.0.1", self.server.port, "tbl", name=name)
        self.addCleanup(client.close)
        client.send_request(1)
        self.assertIsNotNone(client.read_message(timeout=2.0))
        return client

    def test_table_tracks_connect_and_disconnect(self) -> None:
        self.assertEqual(self.server.connections_by_name, {})
        self.assertIsNone(self.server.connection("lamp"))

        self._connect("lamp")
        door = self._connect("door")

        table = self.server.connections_by_name
        self.assertEqual(sorted(table), ["door", "lamp"])
        self.assertIs(table["lamp"], self.server.connection("lamp"))
        self.assertEqual(table["door"].name, "door")

        # 业务侧通过连接对象发消息
        self.assertTrue(table["door"].push(9, 7))
        message = door.read_message(timeout=2.0)
        self.assertIsNotNone(message)
        self.assertEqual((message.power, message.tick), (9, 7))

        # 业务侧通过连接对象断开，表里随之消失
        table["lamp"].disconnect()
        self.assertTrue(self.server.connection("lamp") is None)
        self.assertEqual(sorted(self.server.connections_by_name), ["door"])


class ServeSetupHookTests(unittest.TestCase):
    """serve(setup=...) 是业务启动后台线程的唯一入口。"""

    def test_setup_runs_once_and_failure_exits_cleanly(self) -> None:
        seen: list[int] = []

        def setup(server: TcpTestServer) -> None:
            seen.append(server.port)
            raise RuntimeError("boom")

        with contextlib.redirect_stdout(io.StringIO()):
            code = serve(None, ["--port", "0", "--no-log-file", "--no-console"],
                         setup=setup)
        self.assertEqual(code, 1)
        self.assertEqual(len(seen), 1)
        self.assertGreater(seen[0], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)