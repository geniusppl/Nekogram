#!/usr/bin/env python3
"""Check that an APK's v2 signing certificate matches CERT_SIZE / CERT_HASH in colorado.h.

Mirrors TMessagesProj/jni/colorado/cert.cpp, so a build whose native signature
check would kill the app at launch fails in CI instead of on the phone.
Usage: verify_cert.py <apk> <colorado.h>
"""
import re
import struct
import sys
import zlib

V2_ID = 0x7109871A


def v2_cert(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        sys.exit("no EOCD")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    if data[cd_off - 16:cd_off] != b"APK Sig Block 42":
        sys.exit("no APK signing block")
    size = struct.unpack_from("<Q", data, cd_off - 24)[0]
    start = cd_off - size - 8
    pos = start + 8
    end = cd_off - 24
    cert = None
    while pos < end:
        ln = struct.unpack_from("<Q", data, pos)[0]
        pid = struct.unpack_from("<I", data, pos + 8)[0]
        if pid == V2_ID:
            if cert is not None:
                sys.exit("multiple v2 signatures")
            p = pos + 12 + 12
            dlen = struct.unpack_from("<I", data, p)[0]
            p += 4 + dlen + 4
            clen = struct.unpack_from("<I", data, p)[0]
            cert = data[p + 4:p + 4 + clen]
        pos += 8 + ln
    if cert is None:
        sys.exit("no v2 certificate")
    return cert


def main():
    apk, header = sys.argv[1], sys.argv[2]
    h = open(header).read()
    want_hash = int(re.search(r"CERT_HASH\s+(0x[0-9a-fA-F]+)", h).group(1), 16)
    want_size = int(re.search(r"CERT_SIZE\s+(0x[0-9a-fA-F]+)", h).group(1), 16)
    cert = v2_cert(apk)
    got_size, got_hash = len(cert), zlib.crc32(cert)
    print(f"apk cert size={got_size:#x} crc={got_hash:#x}; expected size={want_size:#x} crc={want_hash:#x}")
    if (got_size, got_hash) != (want_size, want_hash):
        sys.exit("signature constants do not match the signing key")


if __name__ == "__main__":
    main()
