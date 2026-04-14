from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
from datetime import datetime
import subprocess
import sys
import threading
import os


ROOT = Path(__file__).resolve().parent.parent / "local_uploads"
IMAGES_DIR = ROOT / "images"
METADATA_DIR = ROOT / "metadata"
LAVIS_ROOT = Path(__file__).resolve().parent.parent.parent / "LAVIS"
REALTIME_ROOT = LAVIS_ROOT / "data" / "realtime_dataset"
REALTIME_IMAGES_DIR = REALTIME_ROOT / "images"
REALTIME_METADATA_DIR = REALTIME_ROOT / "metadata"
REALTIME_OUTPUT_DIR = LAVIS_ROOT / "data" / "realtime_results"
LAVIS_PYTHON = os.environ.get("LAVIS_PYTHON", sys.executable)

_processing_lock = threading.Lock()
_processing_active = False
_processing_pending = False


class UploadHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/upload":
            self.send_error(404, "Not Found")
            return

        content_type = self.headers.get("Content-Type", "")
        if not content_type.startswith("multipart/form-data"):
            self.send_error(400, "Expected multipart/form-data")
            return

        boundary_marker = "boundary="
        if boundary_marker not in content_type:
            self.send_error(400, "Missing boundary")
            return

        boundary = content_type.split(boundary_marker, 1)[1].strip().strip('"')
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length)
        form = parse_multipart(body, boundary.encode("utf-8"))

        metadata_values = form.get("metadata")
        image_values = form.get("image")
        if not metadata_values or not image_values:
            self.send_error(400, "Expected metadata and image fields")
            return

        metadata_text = metadata_values[0].decode("utf-8")
        metadata = json.loads(metadata_text)
        image_bytes = image_values[0]

        snapshot_id = metadata.get("id", "unknown")
        image_name = f"{snapshot_id}.jpg"
        metadata_name = f"{snapshot_id}.json"

        IMAGES_DIR.mkdir(parents=True, exist_ok=True)
        METADATA_DIR.mkdir(parents=True, exist_ok=True)
        REALTIME_IMAGES_DIR.mkdir(parents=True, exist_ok=True)
        REALTIME_METADATA_DIR.mkdir(parents=True, exist_ok=True)
        (IMAGES_DIR / image_name).write_bytes(image_bytes)
        (METADATA_DIR / metadata_name).write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )
        (REALTIME_IMAGES_DIR / image_name).write_bytes(image_bytes)
        (REALTIME_METADATA_DIR / metadata_name).write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )

        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        client_host, client_port = self.client_address
        print(
            f"[{timestamp}] upload from {client_host}:{client_port} -> "
            f"image={image_name}, metadata={metadata_name}",
            flush=True
        )
        trigger_realtime_processing()

        body = json.dumps(
            {
                "ok": True,
                "savedImage": str(IMAGES_DIR / image_name),
                "savedMetadata": str(METADATA_DIR / metadata_name),
                "realtimeImage": str(REALTIME_IMAGES_DIR / image_name),
                "realtimeMetadata": str(REALTIME_METADATA_DIR / metadata_name),
            },
            ensure_ascii=False
        ).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


def parse_multipart(body: bytes, boundary: bytes) -> dict[str, list[bytes]]:
    delimiter = b"--" + boundary
    result: dict[str, list[bytes]] = {}

    for part in body.split(delimiter):
        part = part.strip()
        if not part or part == b"--":
            continue

        header_blob, separator, content = part.partition(b"\r\n\r\n")
        if not separator:
            continue

        headers = header_blob.decode("utf-8", errors="ignore").split("\r\n")
        disposition = next(
            (header for header in headers if header.lower().startswith("content-disposition:")),
            ""
        )
        name_marker = 'name="'
        if name_marker not in disposition:
            continue

        name = disposition.split(name_marker, 1)[1].split('"', 1)[0]
        value = content.rstrip(b"\r\n")
        result.setdefault(name, []).append(value)

    return result


def trigger_realtime_processing():
    global _processing_active, _processing_pending
    with _processing_lock:
        if _processing_active:
            _processing_pending = True
            return
        _processing_active = True

    threading.Thread(target=_run_realtime_pipeline, daemon=True).start()


def _run_realtime_pipeline():
    global _processing_active, _processing_pending
    while True:
        started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        print(f"[{started_at}] starting realtime caption pipeline", flush=True)
        command = [
            LAVIS_PYTHON,
            str(LAVIS_ROOT / "process_sensorcollector_uploads.py"),
            "--images-dir",
            str(REALTIME_IMAGES_DIR),
            "--metadata-dir",
            str(REALTIME_METADATA_DIR),
            "--output-dir",
            str(REALTIME_OUTPUT_DIR),
        ]
        result = subprocess.run(
            command,
            cwd=LAVIS_ROOT,
            capture_output=True,
            text=True,
        )
        finished_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        if result.returncode == 0:
            print(f"[{finished_at}] realtime caption pipeline finished", flush=True)
            if result.stdout.strip():
                print(result.stdout.strip(), flush=True)
        else:
            print(
                f"[{finished_at}] realtime caption pipeline failed: "
                f"{result.stderr.strip() or result.stdout.strip()}",
                flush=True
            )

        with _processing_lock:
            if _processing_pending:
                _processing_pending = False
                continue
            _processing_active = False
            break


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    REALTIME_ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", 8000), UploadHandler)
    print("Listening on http://0.0.0.0:8000/upload")
    print(f"Saving files under: {ROOT}")
    print(f"Mirroring realtime dataset under: {REALTIME_ROOT}")
    print(f"Realtime outputs under: {REALTIME_OUTPUT_DIR}")
    print(f"LAVIS python: {LAVIS_PYTHON}")
    server.serve_forever()
