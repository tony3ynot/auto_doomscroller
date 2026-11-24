# Battery cost of a session
- Phone: SM-S921N, Android 17. One run is one stretch unplugged with a reel swiped
  past every 4 s, read from the phone's battery statistics (`dumpsys batterystats`) after a reset
- Discharge: what the fuel gauge counted, in whole mAh. Mean power: that over the time, at the
  mean voltage. Battery per hour: the same as a share of the battery's capacity
- App CPU: processor time this app used per minute of the run. App, estimated: the system's
  estimate of this app's draw, which counts its processor and camera time but not the work the
  system does on its behalf, such as taking a screenshot. Feed apps: the same for Instagram and YouTube
- Skin temperature: at the start and at the end of the run

| Run | Setting | Minutes | Discharge | Mean power | Battery per hour | App CPU | App, estimated | Feed apps, estimated | Skin temperature |
|---|---|---|---|---|---|---|---|---|---|
| off | Off | 10 | 66 mAh | 1595 mW | 9.8% | 0.2 s/min | 0.2 mA | 352 mA | 34.0 → 31.1 °C |
| learning | Learning | 10 | 73 mAh | 1767 mW | 10.9% | 6.6 s/min | 8.7 mA | 354 mA | 30.9 → 31.2 °C |
| handsfree | Learning + hands-free | 10 | 214 mAh | 5010 mW | 31.6% | 69.3 s/min | 168.9 mA | 356 mA | 31.7 → 41.7 °C |
| off2 | Off | 10 | 85 mAh | 1996 mW | 12.6% | 0.1 s/min | 0.2 mA | 350 mA | 41.1 → 33.5 °C |
