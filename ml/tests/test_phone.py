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


BATTERY_DUMP = """Battery History [Format: 2] (1% used, 20KB used of 4096KB, 30 strings using 2KB):
  11-19 23:50:00.000 RESET:TIME: 2026-11-19-23-50-00-000
  11-19 23:50:00.000 080 status=charging health=good plug=usb temp=300 volt=4100 current=400 skin_temp=30.0 charge=3120
  11-19 23:51:00.000 080 status=discharging plug=none current=-500 current_event=0x4 +running wake_lock=u0a1:"x"
  11-19 23:56:00.000 079 volt=4000 current=-700 skin_temp=33.5 charge=3080 top=u0a2:"com.example"
  11-20 00:01:00.000 078 temp=320 charge=3020
  11-20 00:11:00.000 077 status=charging plug=usb current=450 charge=2920

Per-PID Stats:
  PID 1 wake time: +1s

Statistics since last charge:
  System starts: 0, currently on battery: false
  Estimated battery capacity: 3900 mAh
  Time on battery: 20m 0s 0ms (95.2%) realtime, 20m 0s 0ms (95.2%) uptime
  Time on battery screen off: 1m 30s 0ms (7.5%) realtime, 0ms (0.0%) uptime
  Discharge: 201 mAh
  Screen off discharge: 2 mAh

  Estimated power use (mAh):
    Capacity: 3900, Rated: 3880, Typical: 4000, Computed drain: 200, actual drain: 201
    Global
    screen: 60.0 apps: 60.0
  UID u0a2: 120 fg: 120 (19m 0s 0ms)
      screen=58.0 cpu=40.5 cpu:fg=40.0 video=21.5 (18m 0s 0ms)
  UID u0a1: 9.50 fgs: 9.50 (20m 0s 0ms)
      cpu=7.25 cpu:fgs=7.25 camera=2.25 (10m 0s 0ms)

  u0a1:
    Wake lock x realtime
    Total cpu time: u=1m 2s 500ms s=30s 0ms
    Proc com.example.one:
      CPU: 1m 2s 500ms usr + 30s 0ms krn ; 0ms fg
  u0a2:
    Total cpu time: u=5m 0s 0ms s=1m 0s 0ms
"""


def test_battery_history_carries_readings_forward_across_midnight():
    from autodoom.phone import battery_history

    history = battery_history(BATTERY_DUMP)
    assert [row["t"] for row in history] == [0, 60, 360, 660, 1260]
    assert history[3] == {"t": 660, "level": 78, "status": "discharging", "plug": "none", "temp": 32.0,
                          "volt": 4000, "current": -700, "skin_temp": 33.5, "charge": 3020}


def test_on_battery_counts_only_the_time_unplugged():
    from autodoom.phone import battery_history, on_battery

    used = on_battery(battery_history(BATTERY_DUMP))
    assert used["minutes"] == pytest.approx(20)
    assert used["discharge_mah"] == 100  # 3120 at the unplug, 3020 at the last reading before the plug
    assert used["mean_current_ma"] == pytest.approx(300)
    assert used["mean_power_mw"] == pytest.approx(300 * (4100 * 5 + 4000 * 15) / 20 / 1000)
    assert used["sampled_current_ma"] == pytest.approx((500 * 5 + 700 * 15) / 20)
    assert used["level_drop"] == 2
    assert used["battery_temp_c"] == {"start": 30.0, "end": 32.0, "max": 32.0}
    assert used["skin_temp_c"] == {"start": 30.0, "end": 33.5, "max": 33.5}


def test_battery_statistics_reads_the_summary_and_each_app():
    from autodoom.phone import battery_statistics

    stats = battery_statistics(BATTERY_DUMP, {"app": "u0a1", "feed": "u0a2", "gone": "u0a9"})
    assert stats["minutes_on_battery"] == pytest.approx(20)
    assert stats["discharge_mah"] == 201 and stats["capacity_mah"] == 3900
    assert stats["apps"]["app"] == {"estimated_mah": 9.5, "estimated_mah_by": {"cpu": 7.25, "camera": 2.25},
                                    "cpu_seconds": pytest.approx(92.5)}
    assert stats["apps"]["feed"]["estimated_mah_by"] == {"screen": 58.0, "cpu": 40.5, "video": 21.5}
    assert stats["apps"]["feed"]["cpu_seconds"] == pytest.approx(360)
    assert stats["apps"]["gone"] == {}
