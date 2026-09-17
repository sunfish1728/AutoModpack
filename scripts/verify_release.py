"""Verify the distributable loader JAR, not only the inner game mod."""
import io
import json
import sys
from pathlib import Path
from zipfile import ZipFile

artifact = Path(sys.argv[1])
required = [
    "pl/skidam/automodpack_core/utils/CertificateInput.class",
    "pl/skidam/automodpack_core/protocol/DownloadRoute.class",
    "pl/skidam/automodpack_core/protocol/DownloadRoutes.class",
    "pl/skidam/automodpack_core/protocol/DownloadTransport.class",
    "pl/skidam/automodpack_core/protocol/ZstdSocket.class",
    "pl/skidam/automodpack_core/protocol/ZstdSocket$1.class",
    "pl/skidam/automodpack_core/protocol/ZstdSocket$2.class",
    "pl/skidam/automodpack_core/protocol/netty/handler/ProtocolMessageDecoder.class",
    "pl/skidam/automodpack_loader_core/client/ModpackUtils.class",
    "META-INF/jarjar/automodpack-mod.jar",
    "META-INF/jarjar/zstd-jni.jar",
]
with ZipFile(artifact) as outer:
    missing = [name for name in required if name not in outer.namelist()]
    if missing:
        raise SystemExit("FAIL: missing from loader JAR: " + ", ".join(missing))
    assert outer.testzip() is None
    with ZipFile(io.BytesIO(outer.read("META-INF/jarjar/automodpack-mod.jar"))) as inner:
        assert inner.testzip() is None
        packet = inner.read("pl/skidam/automodpack/networking/packet/DataC2SPacket.class")
        assert b"DownloadRoutes" in packet and b"supplyAsync" in packet
        language = json.loads(inner.read("assets/automodpack/lang/zh_tw.json"))
        assert language["automodpack.validation.skip.required_text"] == "我接受風險"
    with ZipFile(io.BytesIO(outer.read("META-INF/jarjar/zstd-jni.jar"))) as native:
        assert "com/github/luben/zstd/Zstd.class" in native.namelist()
print("PASS: complete loader, current login route code, confirmation helper, and embedded native library")
