#!/usr/bin/env python3
"""
BondVPN PairBond relay.

This process is intentionally separate from mqvpn. It accepts TCP on the same
numeric port (normally 443; mqvpn uses UDP), joins authenticated paired-SIM
paths into one logical session, reassembles TCP ranges, and forwards SOCKS
TCP/UDP traffic to public internet destinations.
"""

from __future__ import annotations

import argparse
import asyncio
import hmac
import ipaddress
import logging
import re
import secrets
import signal
import socket
import struct
import time
from collections import OrderedDict
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


MAGIC = b"BVPB"
VERSION = 1
SESSION_ID_BYTES = 16
NONCE_BYTES = 32
PROOF_BYTES = 32
MAX_PATH_ID_BYTES = 256
MAX_HOST_BYTES = 253
MAX_PAYLOAD_BYTES = 64 * 1024
MAX_RECORD_BYTES = MAX_PAYLOAD_BYTES + 17 + 16

OPEN_TCP = 1
OPEN_TCP_OK = 2
OPEN_TCP_FAIL = 3
TCP_DATA = 4
CLOSE_TCP = 5
OPEN_UDP = 6
UDP_DATA = 7
CLOSE_UDP = 8
PING = 9
PONG = 10
ACK = 11
PATH_QUALITY = 12
OPEN_UDP_OK = 13
OPEN_UDP_FAIL = 14

PRIORITY_DISABLED = 0
PRIORITY_ACTIVE = 1
PRIORITY_BACKUP = 2

HELLO_LABEL = b"BondVPN PairBond hello v1\0"
ACCEPTED_LABEL = b"BondVPN PairBond accepted v1\0"
SESSION_LABEL = b"BondVPN PairBond session v1\0"
CLIENT_TO_SERVER = b"client-to-server"
SERVER_TO_CLIENT = b"server-to-client"

LOG = logging.getLogger("pairbond")


class ProtocolError(Exception):
    pass


def key_hmac(key: bytes, *pieces: bytes) -> bytes:
    mac = hmac.new(key, digestmod=sha256)
    for piece in pieces:
        mac.update(piece)
    return mac.digest()


def read_mqvpn_auth_key(path: Path) -> bytes:
    section = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        match = re.fullmatch(r"\[([^]]+)]", line)
        if match:
            section = match.group(1).strip().lower()
            continue
        if section != "auth":
            continue
        match = re.fullmatch(r"key\s*=\s*(.+)", line, flags=re.IGNORECASE)
        if match:
            value = match.group(1).strip()
            if value:
                return value.encode("utf-8")
    raise ValueError("mqvpn config の [Auth] Key を読み取れません")


async def read_exact(reader: asyncio.StreamReader, size: int) -> bytes:
    try:
        return await reader.readexactly(size)
    except asyncio.IncompleteReadError as error:
        raise ProtocolError("unexpected EOF") from error


async def read_string(reader: asyncio.StreamReader, maximum: int) -> str:
    size = struct.unpack("!I", await read_exact(reader, 4))[0]
    if size > maximum:
        raise ProtocolError("string too large")
    try:
        return (await read_exact(reader, size)).decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProtocolError("invalid utf-8") from error


def pack_string(value: str, maximum: int) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > maximum:
        raise ProtocolError("string too large")
    return struct.pack("!I", len(encoded)) + encoded


def parse_tcp_target(payload: bytes) -> tuple[str, int]:
    if len(payload) < 6:
        raise ProtocolError("short TCP target")
    size = struct.unpack_from("!I", payload, 0)[0]
    if size == 0 or size > MAX_HOST_BYTES or 4 + size + 2 != len(payload):
        raise ProtocolError("invalid TCP target")
    try:
        host = payload[4 : 4 + size].decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProtocolError("invalid target host") from error
    port = struct.unpack_from("!H", payload, 4 + size)[0]
    if not host or port == 0:
        raise ProtocolError("invalid TCP target")
    return host, port


def parse_udp_datagram(payload: bytes) -> tuple[str, int, bytes]:
    if len(payload) < 10:
        raise ProtocolError("short UDP datagram")
    host_size = struct.unpack_from("!I", payload, 0)[0]
    start = 4
    port_offset = start + host_size
    if host_size == 0 or host_size > MAX_HOST_BYTES or port_offset + 6 > len(payload):
        raise ProtocolError("invalid UDP target")
    try:
        host = payload[start:port_offset].decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProtocolError("invalid UDP target host") from error
    port = struct.unpack_from("!H", payload, port_offset)[0]
    data_size = struct.unpack_from("!I", payload, port_offset + 2)[0]
    data_offset = port_offset + 6
    if not host or port == 0 or data_size > MAX_PAYLOAD_BYTES or data_offset + data_size != len(payload):
        raise ProtocolError("invalid UDP datagram")
    return host, port, payload[data_offset:]


def pack_udp_datagram(host: str, port: int, data: bytes) -> bytes:
    if len(data) > MAX_PAYLOAD_BYTES - 1024:
        raise ProtocolError("UDP datagram too large")
    if port < 1 or port > 65535:
        raise ProtocolError("invalid UDP port")
    return pack_string(host, MAX_HOST_BYTES) + struct.pack("!H", port) + struct.pack("!I", len(data)) + data


def parse_quality(payload: bytes) -> tuple[int, int, int, int]:
    if len(payload) != 17:
        raise ProtocolError("invalid path quality")
    priority, rtt, loss, rate = struct.unpack("!BIIQ", payload)
    if priority not in (PRIORITY_DISABLED, PRIORITY_ACTIVE, PRIORITY_BACKUP):
        priority = PRIORITY_DISABLED
    return priority, min(rtt, 120_000), min(loss, 1_000), min(rate, 10_000_000_000)


@dataclass(slots=True)
class Frame:
    frame_type: int
    flow_id: int
    sequence: int
    payload: bytes = b""


class RecordCodec:
    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        key: bytes,
        inbound_label: bytes,
        outbound_label: bytes,
    ) -> None:
        self.reader = reader
        self.writer = writer
        self.aes = AESGCM(key)
        self.inbound_prefix = key_hmac(key, inbound_label)[:4]
        self.outbound_prefix = key_hmac(key, outbound_label)[:4]
        self.inbound_counter = 0
        self.outbound_counter = 0
        self.write_lock = asyncio.Lock()

    @staticmethod
    def _nonce(prefix: bytes, counter: int) -> bytes:
        if counter < 0 or counter >= 1 << 64:
            raise ProtocolError("record counter exhausted")
        return prefix + counter.to_bytes(8, "big")

    async def read(self) -> Frame:
        encrypted_size = struct.unpack("!I", await read_exact(self.reader, 4))[0]
        if encrypted_size < 16 or encrypted_size > MAX_RECORD_BYTES:
            raise ProtocolError("invalid encrypted record length")
        encrypted = await read_exact(self.reader, encrypted_size)
        try:
            plain = self.aes.decrypt(self._nonce(self.inbound_prefix, self.inbound_counter), encrypted, None)
        except InvalidTag as error:
            raise ProtocolError("encrypted record authentication failed") from error
        self.inbound_counter += 1
        if len(plain) < 17:
            raise ProtocolError("short frame")
        frame_type, flow_id, sequence, payload_size = struct.unpack_from("!BIQI", plain, 0)
        if flow_id < 0 or payload_size > MAX_PAYLOAD_BYTES or len(plain) != 17 + payload_size:
            raise ProtocolError("invalid frame")
        return Frame(frame_type, flow_id, sequence, plain[17:])

    async def send(self, frame: Frame) -> None:
        if not 0 <= frame.frame_type <= 255 or frame.flow_id < 0 or frame.sequence < 0:
            raise ProtocolError("invalid outgoing frame")
        if len(frame.payload) > MAX_PAYLOAD_BYTES:
            raise ProtocolError("outgoing frame too large")
        plain = struct.pack("!BIQI", frame.frame_type, frame.flow_id, frame.sequence, len(frame.payload)) + frame.payload
        async with self.write_lock:
            encrypted = self.aes.encrypt(self._nonce(self.outbound_prefix, self.outbound_counter), plain, None)
            self.outbound_counter += 1
            self.writer.write(struct.pack("!I", len(encrypted)) + encrypted)
            await self.writer.drain()


async def resolve_public_host(host: str, port: int, socktype: int) -> str:
    if not host or len(host) > MAX_HOST_BYTES or port < 1 or port > 65535:
        raise ProtocolError("invalid public target")
    loop = asyncio.get_running_loop()
    candidates = await loop.getaddrinfo(host, port, type=socktype, proto=0)
    for _family, _type, _protocol, _canonname, sockaddr in candidates:
        address = sockaddr[0]
        try:
            parsed = ipaddress.ip_address(address)
        except ValueError:
            continue
        if parsed.is_global:
            return address
    raise ProtocolError("target is not a public internet address")


class OrderedReassembler:
    def __init__(self, maximum_buffered: int = 4 * 1024 * 1024) -> None:
        self.next_offset = 0
        self.maximum_buffered = maximum_buffered
        self.pending: dict[int, bytes] = {}
        self.buffered = 0

    def offer(self, offset: int, data: bytes) -> list[bytes]:
        if offset < 0 or not data:
            return []
        end = offset + len(data)
        if end < offset or end <= self.next_offset:
            return []
        if offset < self.next_offset:
            consumed = self.next_offset - offset
            offset = self.next_offset
            data = data[consumed:]
        if offset in self.pending:
            return []
        if self.buffered + len(data) > self.maximum_buffered:
            raise ProtocolError("reassembly buffer limit exceeded")
        self.pending[offset] = data
        self.buffered += len(data)
        contiguous: list[bytes] = []
        while (next_data := self.pending.pop(self.next_offset, None)) is not None:
            self.buffered -= len(next_data)
            self.next_offset += len(next_data)
            contiguous.append(next_data)
        return contiguous


@dataclass(slots=True)
class PendingChunk:
    offset: int
    data: bytes
    last_path_id: str | None = None
    last_attempt: float = 0.0
    attempts: int = 0

    def sent_on(self, path_id: str) -> None:
        self.last_path_id = path_id
        self.last_attempt = time.monotonic()
        self.attempts += 1


@dataclass(slots=True)
class PathQuality:
    priority: int = PRIORITY_ACTIVE
    rtt_millis: int = 1_000
    loss_permille: int = 0
    delivery_rate_bps: int = 0


class RelayPath:
    def __init__(self, session: "RelaySession", path_id: str, codec: RecordCodec) -> None:
        self.session = session
        self.path_id = path_id
        self.codec = codec
        self.quality = PathQuality()
        self.alive = True

    async def send(self, frame: Frame) -> None:
        if not self.alive:
            raise ConnectionError("path closed")
        await self.codec.send(frame)

    def weight(self) -> float:
        rate = max(self.quality.delivery_rate_bps, 256_000)
        rtt = max(self.quality.rtt_millis, 20)
        loss = max(0.05, 1.0 - min(self.quality.loss_permille, 950) / 1000.0)
        return (rate / rtt) * loss

    async def close(self) -> None:
        self.alive = False
        self.codec.writer.close()


class TcpFlow:
    def __init__(
        self,
        session: "RelaySession",
        flow_id: int,
        target: tuple[str, int],
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        self.session = session
        self.flow_id = flow_id
        self.target = target
        self.reader = reader
        self.writer = writer
        self.inbound = OrderedReassembler()
        self.inbound_lock = asyncio.Lock()
        self.outbound_offset = 0
        self.outbound: OrderedDict[int, PendingChunk] = OrderedDict()
        self.outbound_lock = asyncio.Lock()
        self.closed = False
        self.reader_task = asyncio.create_task(self._read_remote(), name="pairbond-tcp-reader")

    async def receive(self, offset: int, data: bytes) -> int:
        async with self.inbound_lock:
            if self.closed:
                return self.inbound.next_offset
            for chunk in self.inbound.offer(offset, data):
                self.writer.write(chunk)
            await self.writer.drain()
            return self.inbound.next_offset

    async def acknowledge(self, next_offset: int) -> None:
        async with self.outbound_lock:
            while self.outbound:
                offset, chunk = next(iter(self.outbound.items()))
                if offset + len(chunk.data) > next_offset:
                    break
                self.outbound.popitem(last=False)

    async def retry_due(self, delay: float) -> list[PendingChunk]:
        now = time.monotonic()
        async with self.outbound_lock:
            return [
                chunk
                for chunk in self.outbound.values()
                if chunk.last_attempt > 0 and now - chunk.last_attempt >= delay
            ]

    async def _read_remote(self) -> None:
        error: Exception | None = None
        try:
            while not self.closed:
                data = await self.reader.read(16 * 1024)
                if not data:
                    break
                async with self.outbound_lock:
                    outstanding = sum(len(chunk.data) for chunk in self.outbound.values())
                    if outstanding + len(data) > 8 * 1024 * 1024:
                        raise ProtocolError("downlink retransmission buffer limit exceeded")
                    chunk = PendingChunk(self.outbound_offset, data)
                    self.outbound[self.outbound_offset] = chunk
                    self.outbound_offset += len(data)
                await self.session.send_downstream(self, chunk, duplicate=chunk.offset == 0)
        except Exception as caught:
            error = caught
        finally:
            if not self.closed:
                await self.session.close_tcp(self.flow_id, notify_client=True)
            if error is not None:
                LOG.debug("TCP flow %d closed: %s", self.flow_id, error)

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        if self.reader_task is not asyncio.current_task():
            self.reader_task.cancel()
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass


class PairBondUdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, flow: "UdpFlow") -> None:
        self.flow = flow

    def datagram_received(self, data: bytes, addr: tuple[str, int] | tuple[str, int, int, int]) -> None:
        asyncio.create_task(self.flow.receive_remote(data, addr), name="pairbond-udp-receive")


class UdpFlow:
    def __init__(self, session: "RelaySession", flow_id: int) -> None:
        self.session = session
        self.flow_id = flow_id
        self.transport: asyncio.DatagramTransport | None = None
        self.allowed: set[tuple[str, int]] = set()
        self.closed = False

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(lambda: PairBondUdpProtocol(self), family=socket.AF_UNSPEC)
        self.transport = transport

    async def send(self, host: str, port: int, data: bytes) -> None:
        if self.closed or self.transport is None:
            raise ProtocolError("UDP flow is closed")
        address = await resolve_public_host(host, port, socket.SOCK_DGRAM)
        self.allowed.add((address, port))
        self.transport.sendto(data, (address, port))

    async def receive_remote(
        self,
        data: bytes,
        addr: tuple[str, int] | tuple[str, int, int, int],
    ) -> None:
        if self.closed:
            return
        address, port = addr[0], addr[1]
        if (address, port) not in self.allowed:
            return
        try:
            await self.session.send_to_paths(
                Frame(UDP_DATA, self.flow_id, 0, pack_udp_datagram(address, port, data)),
                duplicate=False,
            )
        except Exception as error:
            LOG.debug("UDP response relay failed: %s", error)

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        if self.transport is not None:
            self.transport.close()
            self.transport = None


class RelaySession:
    def __init__(self, relay: "PairBondRelay", session_id: bytes) -> None:
        self.relay = relay
        self.session_id = session_id
        self.paths: dict[str, RelayPath] = {}
        self.path_weights: dict[str, float] = {}
        self.tcp_flows: dict[int, TcpFlow] = {}
        self.udp_flows: dict[int, UdpFlow] = {}
        self.path_lock = asyncio.Lock()
        self.flow_lock = asyncio.Lock()
        self.closed = False
        self.last_activity = time.monotonic()
        self.maintenance_task = asyncio.create_task(self._maintain(), name="pairbond-session-maintenance")

    async def add_path(self, path: RelayPath) -> None:
        async with self.path_lock:
            previous = self.paths.get(path.path_id)
            self.paths[path.path_id] = path
            self.last_activity = time.monotonic()
        if previous is not None and previous is not path:
            await previous.close()

    async def remove_path(self, path: RelayPath) -> None:
        await path.close()
        async with self.path_lock:
            if self.paths.get(path.path_id) is path:
                self.paths.pop(path.path_id, None)
            self.last_activity = time.monotonic()

    async def handle(self, path: RelayPath, frame: Frame) -> None:
        self.last_activity = time.monotonic()
        if frame.frame_type == OPEN_TCP:
            await self.open_tcp(path, frame.flow_id, frame.payload)
        elif frame.frame_type == TCP_DATA:
            flow = self.tcp_flows.get(frame.flow_id)
            if flow is not None:
                next_offset = await flow.receive(frame.sequence, frame.payload)
                await self.send_to_paths(Frame(ACK, frame.flow_id, next_offset), duplicate=False)
        elif frame.frame_type == ACK:
            flow = self.tcp_flows.get(frame.flow_id)
            if flow is not None:
                await flow.acknowledge(frame.sequence)
        elif frame.frame_type == CLOSE_TCP:
            await self.close_tcp(frame.flow_id, notify_client=False)
        elif frame.frame_type == OPEN_UDP:
            await self.open_udp(path, frame.flow_id)
        elif frame.frame_type == UDP_DATA:
            flow = self.udp_flows.get(frame.flow_id)
            if flow is not None:
                host, port, payload = parse_udp_datagram(frame.payload)
                await flow.send(host, port, payload)
        elif frame.frame_type == CLOSE_UDP:
            await self.close_udp(frame.flow_id, notify_client=False)
        elif frame.frame_type == PING:
            await path.send(Frame(PONG, 0, frame.sequence))
        elif frame.frame_type == PATH_QUALITY:
            priority, rtt, loss, rate = parse_quality(frame.payload)
            path.quality = PathQuality(priority, rtt, loss, rate)

    async def open_tcp(self, path: RelayPath, flow_id: int, payload: bytes) -> None:
        try:
            target = parse_tcp_target(payload)
            existing = self.tcp_flows.get(flow_id)
            if existing is not None:
                if existing.target != target:
                    raise ProtocolError("TCP flow target mismatch")
                await path.send(Frame(OPEN_TCP_OK, flow_id, 0))
                return
            async with self.flow_lock:
                existing = self.tcp_flows.get(flow_id)
                if existing is not None:
                    if existing.target != target:
                        raise ProtocolError("TCP flow target mismatch")
                    await path.send(Frame(OPEN_TCP_OK, flow_id, 0))
                    return
                address = await resolve_public_host(target[0], target[1], socket.SOCK_STREAM)
                reader, writer = await asyncio.wait_for(
                    asyncio.open_connection(address, target[1]),
                    timeout=15.0,
                )
                self.tcp_flows[flow_id] = TcpFlow(self, flow_id, target, reader, writer)
            await path.send(Frame(OPEN_TCP_OK, flow_id, 0))
        except Exception as error:
            await path.send(Frame(OPEN_TCP_FAIL, flow_id, 0, str(error).encode("utf-8")[:512]))

    async def open_udp(self, path: RelayPath, flow_id: int) -> None:
        try:
            flow = self.udp_flows.get(flow_id)
            if flow is None:
                async with self.flow_lock:
                    flow = self.udp_flows.get(flow_id)
                    if flow is None:
                        flow = UdpFlow(self, flow_id)
                        await flow.start()
                        self.udp_flows[flow_id] = flow
            await path.send(Frame(OPEN_UDP_OK, flow_id, 0))
        except Exception as error:
            await path.send(Frame(OPEN_UDP_FAIL, flow_id, 0, str(error).encode("utf-8")[:512]))

    async def close_tcp(self, flow_id: int, notify_client: bool) -> None:
        flow = self.tcp_flows.pop(flow_id, None)
        if flow is not None:
            await flow.close()
        if notify_client:
            await self.send_to_paths(Frame(CLOSE_TCP, flow_id, 0), duplicate=False)

    async def close_udp(self, flow_id: int, notify_client: bool) -> None:
        flow = self.udp_flows.pop(flow_id, None)
        if flow is not None:
            await flow.close()
        if notify_client:
            await self.send_to_paths(Frame(CLOSE_UDP, flow_id, 0), duplicate=False)

    async def choose_paths(self, duplicate: bool) -> list[RelayPath]:
        async with self.path_lock:
            ready = [path for path in self.paths.values() if path.alive and path.quality.priority != PRIORITY_DISABLED]
            active = [path for path in ready if path.quality.priority == PRIORITY_ACTIVE]
            pool = active if active else [path for path in ready if path.quality.priority == PRIORITY_BACKUP]
            if not pool:
                return []
            if duplicate:
                return sorted(pool, key=RelayPath.weight, reverse=True)[:2]
            total = sum(path.weight() for path in pool)
            selected: RelayPath | None = None
            selected_weight = float("-inf")
            for path in pool:
                current = self.path_weights.get(path.path_id, 0.0) + path.weight()
                self.path_weights[path.path_id] = current
                if current > selected_weight:
                    selected = path
                    selected_weight = current
            if selected is None:
                return []
            self.path_weights[selected.path_id] = self.path_weights.get(selected.path_id, 0.0) - max(total, 1.0)
            allowed_ids = {path.path_id for path in self.paths.values()}
            self.path_weights = {key: value for key, value in self.path_weights.items() if key in allowed_ids}
            return [selected]

    async def send_to_paths(self, frame: Frame, duplicate: bool) -> None:
        paths = await self.choose_paths(duplicate)
        for path in paths:
            try:
                await path.send(frame)
            except Exception:
                await self.remove_path(path)

    async def send_downstream(self, flow: TcpFlow, chunk: PendingChunk, duplicate: bool) -> None:
        paths = await self.choose_paths(duplicate)
        for path in paths:
            try:
                await path.send(Frame(TCP_DATA, flow.flow_id, chunk.offset, chunk.data))
                chunk.sent_on(path.path_id)
            except Exception:
                await self.remove_path(path)

    async def _maintain(self) -> None:
        try:
            while not self.closed:
                await asyncio.sleep(0.5)
                retry_delay = max(1.2, min(self.best_rtt_seconds() * 3.0, 10.0))
                for flow in list(self.tcp_flows.values()):
                    for chunk in await flow.retry_due(retry_delay):
                        await self.send_downstream(flow, chunk, duplicate=False)
                async with self.path_lock:
                    no_paths = not self.paths
                    idle = time.monotonic() - self.last_activity
                if no_paths and idle > 60.0:
                    await self.relay.remove_session(self.session_id, self)
                    return
        except asyncio.CancelledError:
            raise
        except Exception as error:
            LOG.warning("session maintenance failed: %s", error)

    def best_rtt_seconds(self) -> float:
        values = [
            path.quality.rtt_millis / 1000.0
            for path in self.paths.values()
            if path.alive and path.quality.priority == PRIORITY_ACTIVE
        ]
        return min(values) if values else 1.2

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        if self.maintenance_task is not asyncio.current_task():
            self.maintenance_task.cancel()
        for flow in list(self.tcp_flows.values()):
            await flow.close()
        for flow in list(self.udp_flows.values()):
            await flow.close()
        self.tcp_flows.clear()
        self.udp_flows.clear()
        for path in list(self.paths.values()):
            await path.close()
        self.paths.clear()


class PairBondRelay:
    def __init__(self, auth_key: bytes) -> None:
        self.auth_key = auth_key
        self.sessions: dict[bytes, RelaySession] = {}
        self.sessions_lock = asyncio.Lock()

    async def session_for(self, session_id: bytes) -> RelaySession:
        async with self.sessions_lock:
            session = self.sessions.get(session_id)
            if session is None or session.closed:
                session = RelaySession(self, session_id)
                self.sessions[session_id] = session
            return session

    async def remove_session(self, session_id: bytes, expected: RelaySession) -> None:
        async with self.sessions_lock:
            if self.sessions.get(session_id) is expected:
                self.sessions.pop(session_id, None)
        await expected.close()

    async def close(self) -> None:
        async with self.sessions_lock:
            sessions = list(self.sessions.values())
            self.sessions.clear()
        for session in sessions:
            await session.close()

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        path: RelayPath | None = None
        session: RelaySession | None = None
        try:
            header = await read_exact(reader, 5)
            if header[:4] != MAGIC or header[4] != VERSION:
                raise ProtocolError("unsupported PairBond handshake")
            session_id = await read_exact(reader, SESSION_ID_BYTES)
            path_id = await read_string(reader, MAX_PATH_ID_BYTES)
            if not path_id:
                raise ProtocolError("empty path id")
            client_nonce = await read_exact(reader, NONCE_BYTES)
            proof = await read_exact(reader, PROOF_BYTES)
            expected = key_hmac(self.auth_key, HELLO_LABEL, session_id, path_id.encode("utf-8"), client_nonce)
            if not hmac.compare_digest(proof, expected):
                await self.reject(writer, "PairBond authentication failed")
                return
            server_nonce = secrets.token_bytes(NONCE_BYTES)
            accepted = key_hmac(
                self.auth_key,
                ACCEPTED_LABEL,
                session_id,
                path_id.encode("utf-8"),
                client_nonce,
                server_nonce,
            )
            writer.write(bytes((1,)) + server_nonce + accepted)
            await writer.drain()
            session_key = key_hmac(self.auth_key, SESSION_LABEL, session_id, client_nonce, server_nonce)
            codec = RecordCodec(reader, writer, session_key, CLIENT_TO_SERVER, SERVER_TO_CLIENT)
            session = await self.session_for(session_id)
            path = RelayPath(session, path_id, codec)
            await session.add_path(path)
            LOG.info("path connected session=%s path=%s peer=%s", session_id.hex()[:12], path_id[:16], peer)
            while path.alive:
                frame = await codec.read()
                await session.handle(path, frame)
        except (ProtocolError, ConnectionError, asyncio.IncompleteReadError) as error:
            LOG.debug("path closed peer=%s reason=%s", peer, error)
        except Exception:
            LOG.exception("unhandled PairBond path error peer=%s", peer)
        finally:
            if session is not None and path is not None:
                await session.remove_path(path)
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    async def reject(self, writer: asyncio.StreamWriter, message: str) -> None:
        try:
            writer.write(bytes((0,)) + pack_string(message, 512))
            await writer.drain()
        except Exception:
            pass


async def run_server(args: argparse.Namespace) -> None:
    auth_key = read_mqvpn_auth_key(Path(args.mqvpn_config))
    relay = PairBondRelay(auth_key)
    server = await asyncio.start_server(
        relay.handle_client,
        host=args.host,
        port=args.port,
        limit=MAX_RECORD_BYTES + 4,
        reuse_address=True,
    )
    sockets = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    LOG.info("PairBond relay listening on %s", sockets)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(signum, stop.set)
        except NotImplementedError:
            # Windows' default event loop has no signal handlers. This keeps
            # local protocol tests possible; production runs on Linux.
            pass
    await stop.wait()
    server.close()
    await server.wait_closed()
    await relay.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="BondVPN PairBond multipath relay")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--mqvpn-config", default="/etc/mqvpn/server.conf")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    if args.port < 1 or args.port > 65535:
        parser.error("--port must be between 1 and 65535")
    return args


def main() -> None:
    args = parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    try:
        asyncio.run(run_server(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
