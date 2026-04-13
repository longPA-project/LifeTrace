from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json


ROOT = Path(__file__).resolve().parent.parent / "local_uploads"
IMAGES_DIR = ROOT / "images"
METADATA_DIR = ROOT / "metadata"


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
        (IMAGES_DIR / image_name).write_bytes(image_bytes)
        (METADATA_DIR / metadata_name).write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )

        body = json.dumps(
            {
                "ok": True,
                "savedImage": str(IMAGES_DIR / image_name),
                "savedMetadata": str(METADATA_DIR / metadata_name),
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


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", 8000), UploadHandler)
    print("Listening on http://0.0.0.0:8000/upload")
    print(f"Saving files under: {ROOT}")
    server.serve_forever()
