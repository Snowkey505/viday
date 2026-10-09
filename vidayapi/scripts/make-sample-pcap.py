#!/usr/bin/env python3
"""Генерация минимального валидного .pcap для демонстрации Задания 5 ЛР2.

Создаёт libpcap-файл с глобальным заголовком (магия 0xa1b2c3d4, Ethernet) и
одной парой пакетов «HTTP GET /api/videos -> HTTP/1.1 200 OK» поверх
Ethernet/IPv4/TCP (заголовки корректны, контрольные суммы заполнены).
Wireshark открывает файл и декодирует HTTP-обмен без реального сетевого трафика.

Использование:
    python3 scripts/make-sample-pcap.py [-o traffic/examples/viday-e2e-sample.pcap]
"""
import argparse
import struct
import time


def ip_checksum(data: bytes) -> int:
    """Классическая контрольная сумма IPv4 (RFC 1071)."""
    if len(data) % 2:
        data += b"\x00"
    s = sum(struct.unpack("!%dH" % (len(data) // 2), data))
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return (~s) & 0xFFFF


def build_packet(src_mac, dst_mac, src_ip, dst_ip, src_port, dst_port, payload, seq, ack, flags):
    # --- Ethernet ---
    eth = bytes.fromhex(dst_mac.replace(":", "")) + bytes.fromhex(src_mac.replace(":", ""))
    eth += struct.pack("!H", 0x0800)  # IPv4

    # --- IPv4 (20 байт) ---
    ihl_version = 0x45
    total_len = 20 + 20 + len(payload)
    ident = 0x1234
    ttl = 64
    proto = 6  # TCP
    src_bytes = bytes(map(int, src_ip.split(".")))
    dst_bytes = bytes(map(int, dst_ip.split(".")))
    ip_header = struct.pack("!BBHHHBBH4s4s", ihl_version, 0, total_len, ident, 0x4000, ttl, proto, 0, src_bytes, dst_bytes)
    ip_header = ip_header[:10] + struct.pack("!H", ip_checksum(ip_header)) + ip_header[12:]

    # --- TCP (20 байт) ---
    data_offset = 5 << 4
    tcp_header = struct.pack("!HHIIBBHHH", src_port, dst_port, seq, ack, data_offset, flags, 65535, 0, 0)
    pseudo = src_bytes + dst_bytes + struct.pack("!BBH", 0, proto, len(tcp_header) + len(payload))
    tcp_checksum = ip_checksum(pseudo + tcp_header + payload)
    tcp_header = tcp_header[:16] + struct.pack("!H", tcp_checksum)

    return eth + ip_header + tcp_header + payload


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate a minimal demo .pcap with one HTTP exchange")
    parser.add_argument("-o", "--output", default="traffic/examples/viday-e2e-sample.pcap")
    args = parser.parse_args()

    req = (
        b"GET /api/videos?page=0&size=50 HTTP/1.1\r\n"
        b"Host: localhost:8080\r\n"
        b"Accept: application/json\r\n"
        b"Connection: close\r\n\r\n"
    )
    resp = (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: application/json\r\n"
        b"Content-Length: 28\r\n"
        b"Connection: close\r\n"
        b"\r\n"
        b'{"content":[],"totalElements":0}'
    )

    now = int(time.time())
    packets = [
        # SYN
        build_packet("02:42:ac:11:00:02", "02:42:ac:11:00:01", "127.0.0.1", "127.0.0.1", 49152, 8080, b"", now, 0, 0x02),
        # SYN+ACK
        build_packet("02:42:ac:11:00:01", "02:42:ac:11:00:02", "127.0.0.1", "127.0.0.1", 8080, 49152, b"", now, now + 1, 0x12),
        # ACK + HTTP request
        build_packet("02:42:ac:11:00:02", "02:42:ac:11:00:01", "127.0.0.1", "127.0.0.1", 49152, 8080, req, now + 1, now + 1, 0x18),
        # HTTP response (PSH+ACK)
        build_packet("02:42:ac:11:00:01", "02:42:ac:11:00:02", "127.0.0.1", "127.0.0.1", 8080, 49152, resp, now + 1, now + 1 + len(req), 0x18),
    ]

    import os
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)

    with open(args.output, "wb") as f:
        f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1))  # linktype Ethernet=1
        for i, pkt in enumerate(packets):
            f.write(struct.pack("<IIII", now + i, 0, len(pkt), len(pkt)))
            f.write(pkt)

    print(f"sample pcap written: {args.output} ({len(packets)} packets)")


if __name__ == "__main__":
    main()