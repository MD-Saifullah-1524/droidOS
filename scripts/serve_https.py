import http.server
import ssl
import os
import sys

PORT = 8443
DIRECTORY = os.path.dirname(os.path.abspath(__file__))

class DroidOSHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def log_message(self, format, *args):
        sys.stderr.write(f"[{self.log_date_time_string()}] {self.address_string()} - {format % args}\n")
        sys.stderr.flush()

def main():
    cert_path = os.path.join(DIRECTORY, "cert.pem")
    key_path = os.path.join(DIRECTORY, "key.pem")

    if not os.path.exists(cert_path) or not os.path.exists(key_path):
        print(f"Error: Certificate or key not found in {DIRECTORY}", file=sys.stderr)
        sys.exit(1)

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), DroidOSHandler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=cert_path, keyfile=key_path)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)

    print(f"[*] DroidOS HTTPS Server started successfully on port {PORT}")
    print(f"[*] Serving files from: {DIRECTORY}")
    print(f"[*] Accessible URLs:")
    print(f"    - https://192.168.1.5:{PORT}/")
    print(f"    - https://localhost:{PORT}/")
    sys.stdout.flush()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Server shutting down...")
        httpd.server_close()

if __name__ == "__main__":
    main()
