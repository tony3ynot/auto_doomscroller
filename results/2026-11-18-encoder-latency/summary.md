# Encoder latency on the phone
- Phone: SM-S921N (Samsung s5e9945, 10 cores), Android 17,
  LiteRT 1.4.2. CPU runs use XNNPACK
- One 224 px frame in, one 576-d embedding out; the time to scale the screenshot is not included
- Load: mapping the model and building the interpreter. First run: the first inference after that
- Back to back: 200 runs after 20 warm-up runs; median, 95th percentile in brackets
- Paced: 40 runs with 1 s of idle before each, as when the encoder runs once per reel

| Encoder | Runs on | Load | First run | Back to back | Paced |
|---|---|---|---|---|---|
| FP32 | CPU, 1 thread | 77 ms | 4.2 ms | 3.6 ms (3.6) | 21.8 ms (25.3) |
| FP32 | CPU, 2 threads | 38 ms | 4.4 ms | 3.6 ms (3.6) | 23.1 ms (25.3) |
| FP32 | CPU, 4 threads | 39 ms | 4.2 ms | 3.6 ms (3.6) | 23.4 ms (25.9) |
| FP32 | GPU | 1296 ms | 7.4 ms | 3.9 ms (4.9) | 15.9 ms (19.7) |
| FP16 | CPU, 1 thread | 53 ms | 4.3 ms | 3.6 ms (3.7) | 22.5 ms (25.2) |
| FP16 | CPU, 2 threads | 43 ms | 4.3 ms | 3.6 ms (3.6) | 22.2 ms (25.1) |
| FP16 | CPU, 4 threads | 37 ms | 4.1 ms | 3.6 ms (3.6) | 23.0 ms (25.6) |
| FP16 | GPU | 1811 ms | 12.8 ms | 3.7 ms (4.4) | 18.0 ms (19.7) |
| INT8 dynamic range | CPU, 1 thread | 27 ms | 7.0 ms | 2.9 ms (2.9) | 23.7 ms (26.7) |
| INT8 dynamic range | CPU, 2 threads | 41 ms | 11.9 ms | 3.0 ms (5.0) | 23.4 ms (25.5) |
| INT8 dynamic range | CPU, 4 threads | 42 ms | 10.1 ms | 2.7 ms (4.5) | 22.6 ms (30.8) |
| INT8 dynamic range | GPU | 1293 ms | 6.3 ms | 3.7 ms (4.2) | 17.1 ms (19.7) |
| INT8 full integer | CPU, 1 thread | 72 ms | 5.9 ms | 2.3 ms (2.3) | 18.0 ms (21.3) |
| INT8 full integer | CPU, 2 threads | 36 ms | 2.7 ms | 2.3 ms (2.3) | 18.5 ms (21.0) |
| INT8 full integer | CPU, 4 threads | 43 ms | 2.7 ms | 2.3 ms (2.3) | 18.0 ms (20.8) |
| INT8 full integer | GPU | 1825 ms | 7.8 ms | 3.8 ms (4.9) | 16.6 ms (20.0) |
