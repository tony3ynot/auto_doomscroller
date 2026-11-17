import pytest

from autodoom.phone import latency_ms, status_records

OUTPUT = "\r\n".join([
    "INSTRUMENTATION_STATUS: class=com.autodoomscroller.benchmark.EncoderLatencyTest",
    "INSTRUMENTATION_STATUS_CODE: 1",
    'INSTRUMENTATION_STATUS: encoder_latency={"kind":"device","soc":"A = B"}',
    "INSTRUMENTATION_STATUS_CODE: 0",
    'INSTRUMENTATION_STATUS: encoder_latency={"kind":"latency","paced_us":[4000,6000]}',
    "INSTRUMENTATION_STATUS_CODE: 0",
    "INSTRUMENTATION_CODE: -1",
])


def test_status_records_keeps_only_the_key_in_order():
    records = status_records(OUTPUT, "encoder_latency")
    assert [r["kind"] for r in records] == ["device", "latency"]
    assert records[0]["soc"] == "A = B"
    assert status_records(OUTPUT, "other") == []


def test_latency_ms_converts_from_microseconds():
    stats = latency_ms([4000, 6000, 5000])
    assert stats["n"] == 3
    assert stats["median_ms"] == pytest.approx(5.0)
    assert stats["min_ms"] == pytest.approx(4.0)
    assert stats["max_ms"] == pytest.approx(6.0)
    assert 5.0 < stats["p95_ms"] <= 6.0
