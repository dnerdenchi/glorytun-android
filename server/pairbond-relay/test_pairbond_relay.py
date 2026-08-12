from __future__ import annotations

import asyncio
import hmac
import importlib.util
import struct
import sys
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("pairbond_relay.py")
SPEC = importlib.util.spec_from_file_location("pairbond_relay", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
relay = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = relay
SPEC.loader.exec_module(relay)


class PairBondRelayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.key = b"unit-test-auth-key"
        self.relay = relay.PairBondRelay(self.key)
        self.server = await asyncio.start_server(self.relay.handle_client, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self) -> None:
        self.server.close()
        await self.server.wait_closed()
        await self.relay.close()

    async def test_authenticated_path_round_trips_ping(self) -> None:
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        session_id = b"A" * relay.SESSION_ID_BYTES
        path_id = "sim-a"
        client_nonce = b"B" * relay.NONCE_BYTES
        proof = relay.key_hmac(
            self.key,
            relay.HELLO_LABEL,
            session_id,
            path_id.encode(),
            client_nonce,
        )
        writer.write(
            relay.MAGIC
            + bytes((relay.VERSION,))
            + session_id
            + relay.pack_string(path_id, relay.MAX_PATH_ID_BYTES)
            + client_nonce
            + proof
        )
        await writer.drain()

        self.assertEqual(await reader.readexactly(1), b"\x01")
        server_nonce = await reader.readexactly(relay.NONCE_BYTES)
        accepted = await reader.readexactly(relay.PROOF_BYTES)
        self.assertTrue(
            hmac.compare_digest(
                accepted,
                relay.key_hmac(
                    self.key,
                    relay.ACCEPTED_LABEL,
                    session_id,
                    path_id.encode(),
                    client_nonce,
                    server_nonce,
                ),
            )
        )
        session_key = relay.key_hmac(self.key, relay.SESSION_LABEL, session_id, client_nonce, server_nonce)
        codec = relay.RecordCodec(
            reader,
            writer,
            session_key,
            relay.SERVER_TO_CLIENT,
            relay.CLIENT_TO_SERVER,
        )
        quality = struct.pack("!BIIQ", relay.PRIORITY_ACTIVE, 50, 0, 5_000_000)
        await codec.send(relay.Frame(relay.PATH_QUALITY, 0, 0, quality))
        await codec.send(relay.Frame(relay.PING, 0, 12345))
        response = await asyncio.wait_for(codec.read(), timeout=2)
        self.assertEqual(response.frame_type, relay.PONG)
        self.assertEqual(response.sequence, 12345)

        writer.close()
        await writer.wait_closed()

    async def test_slow_client_applies_backpressure_without_closing_large_download(self) -> None:
        chunk = b"x" * (16 * 1024)
        window_chunks = relay.MAX_TCP_OUTBOUND_BYTES // len(chunk)
        total_chunks = window_chunks + 8

        class FastReader:
            def __init__(self) -> None:
                self.remaining = total_chunks

            async def read(self, _size: int) -> bytes:
                if self.remaining == 0:
                    return b""
                self.remaining -= 1
                return chunk

        class NullWriter:
            def write(self, _data: bytes) -> None:
                pass

            async def drain(self) -> None:
                pass

            def close(self) -> None:
                pass

            async def wait_closed(self) -> None:
                pass

        class SlowSession:
            def __init__(self) -> None:
                self.sent: list[relay.PendingChunk] = []
                self.closed = asyncio.Event()

            async def send_downstream(self, _flow: relay.TcpFlow, pending: relay.PendingChunk, duplicate: bool) -> None:
                self.sent.append(pending)

            async def close_tcp(self, _flow_id: int, notify_client: bool) -> None:
                self.closed.set()

        session = SlowSession()
        flow = relay.TcpFlow(session, 7, ("example.com", 443), FastReader(), NullWriter())
        try:
            deadline = asyncio.get_running_loop().time() + 2.0
            while len(session.sent) < window_chunks and asyncio.get_running_loop().time() < deadline:
                await asyncio.sleep(0.01)

            self.assertLess(relay.MAX_TCP_OUTBOUND_BYTES, relay.MAX_REASSEMBLY_BYTES)
            self.assertEqual(len(session.sent), window_chunks)
            self.assertFalse(session.closed.is_set())

            await flow.acknowledge(16 * len(chunk))
            await asyncio.wait_for(session.closed.wait(), timeout=2)
            self.assertEqual(len(session.sent), total_chunks)
        finally:
            await flow.close()

    async def test_invalid_handshake_is_rejected(self) -> None:
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(
            relay.MAGIC
            + bytes((relay.VERSION,))
            + (b"C" * relay.SESSION_ID_BYTES)
            + relay.pack_string("sim-b", relay.MAX_PATH_ID_BYTES)
            + (b"D" * relay.NONCE_BYTES)
            + (b"\x00" * relay.PROOF_BYTES)
        )
        await writer.drain()
        self.assertEqual(await reader.readexactly(1), b"\x00")
        reason_size = struct.unpack("!I", await reader.readexactly(4))[0]
        self.assertGreater(reason_size, 0)
        self.assertIn(b"authentication", await reader.readexactly(reason_size))
        writer.close()
        await writer.wait_closed()


class PairBondProtocolUnitTest(unittest.TestCase):
    def test_reassembler_orders_and_deduplicates_ranges(self) -> None:
        reassembler = relay.OrderedReassembler()
        self.assertEqual(reassembler.offer(3, b"def"), [])
        self.assertEqual(reassembler.offer(0, b"abc"), [b"abc", b"def"])
        self.assertEqual(reassembler.next_offset, 6)
        self.assertEqual(reassembler.offer(0, b"abc"), [])

    def test_mqvpn_auth_parser_uses_only_auth_section(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "server.conf"
            config.write_text(
                "[TLS]\nKey = ignored\n\n[Auth]\nKey = actual-auth-key\n",
                encoding="utf-8",
            )
            self.assertEqual(relay.read_mqvpn_auth_key(config), b"actual-auth-key")


if __name__ == "__main__":
    unittest.main()
