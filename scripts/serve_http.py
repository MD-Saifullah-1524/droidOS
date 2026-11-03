#!/usr/bin/env python3
"""
High-Speed Multi-Threaded HTTP File Server for droidOS Packages
Supports:
- HTTP 206 Partial Content (Range requests) for multi-stream downloads (aria2c, curl -C, wget -c)
- ThreadingHTTPServer for concurrent chunk requests
- Serving directly from ~/droidOS/serve on port 8080
"""

import os
import sys
import mimetypes
from http import HTTPStatus
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote

PORT = 8080
SERVE_DIR = os.path.expanduser("~/droidOS/serve")

class HighSpeedRangeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        self.send_file(send_body=False)

    def do_GET(self):
        self.send_file(send_body=True)

    def send_file(self, send_body=True):
        clean_path = unquote(self.path.split('?')[0]).lstrip('/')
        if not clean_path or clean_path == '/':
            clean_path = ''

        filepath = os.path.abspath(os.path.join(SERVE_DIR, clean_path))
        if not filepath.startswith(SERVE_DIR):
            self.send_error(HTTPStatus.FORBIDDEN, "Access Denied")
            return

        if os.path.isdir(filepath):
            self.send_directory_listing(filepath)
            return

        if not os.path.isfile(filepath):
            self.send_error(HTTPStatus.NOT_FOUND, "File Not Found")
            return

        try:
            file_size = os.path.getsize(filepath)
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "File Not Found")
            return

        content_type, _ = mimetypes.guess_type(filepath)
        if not content_type:
            content_type = "application/octet-stream"

        range_header = self.headers.get("Range")
        start = 0
        end = file_size - 1
        is_range = False

        if range_header and range_header.startswith("bytes="):
            ranges = range_header[6:].split("-")
            try:
                if ranges[0]:
                    start = int(ranges[0])
                if len(ranges) > 1 and ranges[1]:
                    end = int(ranges[1])
                if start > end or start >= file_size:
                    self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    return
                end = min(end, file_size - 1)
                is_range = True
            except ValueError:
                pass

        content_length = end - start + 1

        if is_range:
            self.send_response(HTTPStatus.PARTIAL_CONTENT)
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
        else:
            self.send_response(HTTPStatus.OK)

        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(content_length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if not send_body:
            return

        chunk_size = 256 * 1024  # 256 KB buffer for high throughput
        bytes_to_send = content_length

        try:
            with open(filepath, "rb") as f:
                f.seek(start)
                while bytes_to_send > 0:
                    read_len = min(chunk_size, bytes_to_send)
                    data = f.read(read_len)
                    if not data:
                        break
                    self.wfile.write(data)
                    bytes_to_send -= len(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_directory_listing(self, dir_path):
        try:
            entries = sorted(os.listdir(dir_path))
        except OSError:
            self.send_error(HTTPStatus.INTERNAL_SERVER_ERROR, "Cannot read directory")
            return

        html = [
            "<!DOCTYPE html><html><head><title>droidOS Serve Directory</title>",
            "<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:40px;background:#121212;color:#eee}",
            "a{color:#4daafc;text-decoration:none}a:hover{text-decoration:underline}",
            "table{border-collapse:collapse;width:100%}th,td{padding:10px;text-align:left;border-bottom:1px solid #333}",
            "th{background:#1f1f1f}</style></head><body>",
            "<h2>Index of /</h2><table><tr><th>Name</th><th>Size</th></tr>"
        ]

        for name in entries:
            full = os.path.join(dir_path, name)
            size = os.path.getsize(full) if os.path.isfile(full) else "-"
            if isinstance(size, int):
                if size > 1024*1024:
                    size = f"{size / (1024*1024):.1f} MB"
                elif size > 1024:
                    size = f"{size / 1024:.1f} KB"
                else:
                    size = f"{size} B"
            html.append(f"<tr><td><a href=\"/{name}\">{name}</a></td><td>{size}</td></tr>")

        html.append("</table></body></html>")
        content = "\n".join(html).encode("utf-8")

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, format, *args):
        sys.stderr.write(f"[{self.log_date_time_string()}] {self.address_string()} - {format % args}\n")
        sys.stderr.flush()

def main():
    os.makedirs(SERVE_DIR, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), HighSpeedRangeHandler)
    print(f"[+] High-Speed HTTP Server listening on 0.0.0.0:{PORT}")
    print(f"[+] Serving directory: {SERVE_DIR}")
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()

if __name__ == "__main__":
    main()
