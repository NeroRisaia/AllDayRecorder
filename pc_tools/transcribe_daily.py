"""AllDayRecorder の録音 (.m4a) を faster-whisper (GPU) で日本語文字起こしし、
OneDrive「日々のコンテキスト\\YYYY-MM-DD\\」に日付別で貯める。

- 入力: OneDrive と同期されたフォルダ（既定: 日々のコンテキスト\\_録音受信）配下の .m4a（サブフォルダも見る）
- 出力: 日々のコンテキスト\\YYYY-MM-DD\\<セグメント名>.txt と、その日の全文を時刻順に結合した context.txt
- 日付・時刻はファイル名 seg_NNNN_YYYYMMDDTHHMMSS.m4a から判定（更新日時は使わない）
- 処理記録: 日々のコンテキスト\\.transcribe_state.json（中身の SHA-256 で管理。改名・重複コピーも二重処理しない）
- 失敗したファイルは飛ばして続行し、報告（画面と _報告\\ 内のログ）に残す。次回実行時に再試行（最大3回）

使い方:
  python transcribe_daily.py                 # 既定フォルダを処理
  python transcribe_daily.py --input D:\\x    # 入力フォルダを指定（複数可）
  python transcribe_daily.py --dry-run       # 何が処理対象かだけ表示
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import time
import traceback
from pathlib import Path

NAME_RE = re.compile(r"seg_(\d+)_(\d{8})T(\d{6})")
STATE_NAME = ".transcribe_state.json"
MAX_ATTEMPTS = 3
SETTLE_SECONDS = 60  # 同期中の書きかけを避けるため、更新から60秒未満のファイルは次回へ


def onedrive_root() -> Path:
    for key in ("OneDrive", "OneDriveConsumer"):
        v = os.environ.get(key)
        if v and Path(v).is_dir():
            return Path(v)
    return Path.home() / "OneDrive"


def add_cuda_dll_dirs() -> None:
    """pip の nvidia-cublas-cu12 / nvidia-cudnn-cu12 の DLL を見えるようにする（Windows）。"""
    if os.name != "nt":
        return
    try:
        import nvidia  # type: ignore
    except ImportError:
        return
    for base in list(getattr(nvidia, "__path__", [])):
        for sub in ("cublas", "cudnn", "cuda_nvrtc", "cuda_runtime"):
            d = Path(base) / sub / "bin"
            if d.is_dir():
                os.add_dll_directory(str(d))
                os.environ["PATH"] = str(d) + os.pathsep + os.environ.get("PATH", "")


def parse_start(path: Path) -> dt.datetime | None:
    m = NAME_RE.search(path.stem)
    if not m:
        return None
    try:
        return dt.datetime.strptime(m.group(2) + m.group(3), "%Y%m%d%H%M%S")
    except ValueError:
        return None


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_state(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"version": 1, "files": {}}


def save_state(path: Path, state: dict) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(path)


def segment_txt_name(path: Path, start: dt.datetime) -> str:
    m = NAME_RE.search(path.stem)
    return f"seg_{m.group(1)}_{start:%Y%m%dT%H%M%S}.txt" if m else path.stem + ".txt"


def write_segment_txt(out: Path, src: Path, start: dt.datetime, pieces: list[tuple[float, str]]) -> None:
    lines = [f"# {src.name}  開始 {start:%Y-%m-%d %H:%M:%S}"]
    for offset, text in pieces:
        t = start + dt.timedelta(seconds=offset)
        lines.append(f"[{t:%H:%M:%S}] {text}")
    if not pieces:
        lines.append("（発話なし）")
    tmp = out.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    tmp.replace(out)


def rebuild_context(day_dir: Path) -> int:
    """その日のセグメント .txt を開始時刻順に結合して context.txt を作り直す。"""
    segs = []
    for p in day_dir.glob("seg_*.txt"):
        st = parse_start(p)
        if st:
            segs.append((st, p))
    segs.sort()
    parts = [f"# 日々のコンテキスト {day_dir.name}（{len(segs)} セグメント、時刻順）", ""]
    for _, p in segs:
        parts.append(p.read_text(encoding="utf-8").rstrip())
        parts.append("")
    tmp = day_dir / "context.tmp"
    tmp.write_text("\n".join(parts), encoding="utf-8")
    tmp.replace(day_dir / "context.txt")
    return len(segs)


def main() -> int:
    od = onedrive_root()
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", type=Path, default=od / "日々のコンテキスト")
    ap.add_argument("--input", type=Path, action="append",
                    help="監視する .m4a フォルダ（既定: <output>\\_録音受信）")
    ap.add_argument("--model", default="large-v3")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--compute-type", default="float16")
    ap.add_argument("--settle", type=int, default=SETTLE_SECONDS)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    out_root: Path = args.output
    inputs: list[Path] = args.input or [out_root / "_録音受信"]
    if not args.dry_run:
        out_root.mkdir(parents=True, exist_ok=True)
        for d in inputs:
            d.mkdir(parents=True, exist_ok=True)
    state_path = out_root / STATE_NAME
    state = load_state(state_path)
    files: dict = state["files"]

    now = time.time()
    todo: list[tuple[Path, str, dt.datetime]] = []
    report = {"done": [], "skipped_done": 0, "failed": [], "held": [], "not_ready": [], "no_date": []}
    seen: set[str] = set()
    for d in inputs:
        if not d.is_dir():
            print(f"（入力フォルダがまだありません: {d}）")
            continue
        for p in sorted(d.rglob("*.m4a")):
            start = parse_start(p)
            if start is None:
                report["no_date"].append(str(p))
                continue
            try:
                if now - p.stat().st_mtime < args.settle:
                    report["not_ready"].append(str(p))
                    continue
                key = sha256(p)
            except OSError as e:
                report["failed"].append(f"{p}: 読み込み不可 {e}")
                continue
            if key in seen:
                report["skipped_done"] += 1
                continue
            seen.add(key)
            rec = files.get(key)
            if rec and rec.get("status") == "done":
                report["skipped_done"] += 1
                continue
            if rec and rec.get("status") == "failed" and rec.get("attempts", 0) >= MAX_ATTEMPTS:
                report["held"].append(f"{p}: {MAX_ATTEMPTS}回失敗のため保留（{rec.get('error')}）")
                continue
            todo.append((p, key, start))

    print(f"入力: {', '.join(map(str, inputs))}")
    print(f"出力: {out_root}")
    print(f"対象 {len(todo)} 件 / 処理済みスキップ {report['skipped_done']} 件")
    if args.dry_run:
        for p, _, s in todo:
            print(f"  - {p.name} ({s:%Y-%m-%d %H:%M:%S})")
        return 0

    touched_days: set[Path] = set()
    model = None
    device_used = None
    if todo:
        add_cuda_dll_dirs()
        from faster_whisper import WhisperModel  # 遅延 import（dry-run を軽く）
        t0 = time.time()
        model = WhisperModel(args.model, device=args.device, compute_type=args.compute_type)
        device_used = f"{args.device}/{args.compute_type}"
        print(f"モデル {args.model} 読込 {time.time() - t0:.1f}s（{device_used}）")

    for p, key, start in todo:
        day_dir = out_root / f"{start:%Y-%m-%d}"
        t0 = time.time()
        try:
            segments, info = model.transcribe(
                str(p), language="ja", beam_size=5, vad_filter=True,
                vad_parameters={"min_silence_duration_ms": 1000},
            )
            pieces = [(s.start, s.text.strip()) for s in segments if s.text.strip()]
            day_dir.mkdir(parents=True, exist_ok=True)
            txt = day_dir / segment_txt_name(p, start)
            owned = {r.get("txt") for k, r in files.items() if k != key and r.get("status") == "done"}
            n = 2
            while str(txt) in owned:
                txt = day_dir / f"{Path(segment_txt_name(p, start)).stem}_{n}.txt"
                n += 1
            write_segment_txt(txt, p, start, pieces)
            elapsed = time.time() - t0
            files[key] = {"status": "done", "source": str(p), "txt": str(txt),
                          "audio_sec": round(info.duration, 1), "elapsed_sec": round(elapsed, 1),
                          "device": device_used, "done_at": dt.datetime.now().isoformat(timespec="seconds")}
            touched_days.add(day_dir)
            report["done"].append(f"{p.name} → {day_dir.name}\\{txt.name}（音声 {info.duration:.0f}s / 処理 {elapsed:.1f}s / {len(pieces)} 発話）")
            print("  OK  " + report["done"][-1])
        except Exception as e:  # 失敗は飛ばして続行
            prev = files.get(key, {})
            files[key] = {"status": "failed", "source": str(p), "error": f"{type(e).__name__}: {e}",
                          "attempts": prev.get("attempts", 0) + 1,
                          "last_try": dt.datetime.now().isoformat(timespec="seconds")}
            report["failed"].append(f"{p}: {type(e).__name__}: {e}")
            print("  NG  " + report["failed"][-1])
            traceback.print_exc(limit=1)
        save_state(state_path, state)  # 1件ごとに保存（途中で落ちても二重処理しない）

    for day_dir in sorted(touched_days):
        n = rebuild_context(day_dir)
        print(f"  context.txt 更新: {day_dir.name}（{n} セグメント）")

    lines = [
        f"実行 {dt.datetime.now():%Y-%m-%d %H:%M:%S}  デバイス {device_used or '-'}",
        f"処理 {len(report['done'])} 件 / 処理済みスキップ {report['skipped_done']} 件 / "
        f"失敗 {len(report['failed'])} 件 / 保留 {len(report['held'])} 件 / 同期待ち {len(report['not_ready'])} 件 / 日付不明 {len(report['no_date'])} 件",
    ]
    for k, label in (("done", "処理"), ("failed", "失敗"), ("held", "保留(自動再試行しない。直すか消すと次回対象)"), ("not_ready", "同期待ち(次回)"), ("no_date", "日付不明(ファイル名が seg_NNNN_YYYYMMDDTHHMMSS でない)")):
        for item in report[k]:
            lines.append(f"[{label}] {item}")
    text = "\n".join(lines)
    print("\n" + text)
    log_dir = out_root / "_報告"
    log_dir.mkdir(exist_ok=True)
    with (log_dir / "transcribe_log.txt").open("a", encoding="utf-8") as f:
        f.write(text + "\n\n")
    return 1 if report["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
