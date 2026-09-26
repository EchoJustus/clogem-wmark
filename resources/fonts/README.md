Drop an OFL-licensed font here as `wmark.ttf` (e.g. Noto Sans, or Noto Sans CJK
if warning texts may be Chinese/Japanese/Korean) and add its license file next
to it. It is embedded in the binary and extracted to the cache directory on
first use, because FFmpeg's drawtext needs a real file path. Without it, wmark
falls back to common system fonts (Segoe UI/Arial on Windows, DejaVu on Linux).
