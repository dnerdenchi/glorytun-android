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
