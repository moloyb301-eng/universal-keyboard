#!/usr/bin/env python3
"""Serve a terminal session (Claude Code by default) to the Universal Keyboard app.

Runs `claude` (or a shell) in a pseudo-terminal and serves it over a WebSocket on
127.0.0.1, so the app's Claude tab can show it and type into it. Python 3
standard library only: the environments this runs in have no pip packages, so
the WebSocket handshake and framing (RFC 6455) are implemented here.

Security model
  * Binds to 127.0.0.1 only. Other apps on the same phone can still reach
    loopback, so every connection must also present a random pairing code
    (60 bits) as its first message. Failed attempts are slowed down.
  * The code is created on first run and stored with mode 600. It is printed
    only when created or when asked for with --show-code, and never logged.

Protocol (one session at a time; a new client takes over from the old one)
  client -> bridge  text  {"type": "auth", "token": "<pairing code>"}   first message
                    text  {"type": "input", "data": "<keys as text>"}
                    text  {"type": "resize", "cols": 80, "rows": 24}
  bridge -> client  text  {"type": "ready", "replay": true|false}
                    binary  raw terminal output
                    text  {"type": "exit", "code": 0}   then close 4002
                    text  {"type": "error", "message": "..."}   then close 4003
  Close codes: 4000 replaced by a newer client, 4001 bad pairing code,
  4002 session ended (reconnect to start a new one), 4003 couldn't start.

The session survives disconnects: the program keeps running and its recent
output is replayed to the next client, so switching apps doesn't lose work.
"""

import argparse
import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import shlex
import shutil
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time

import fcntl
import termios

DEFAULT_PORT = 8765
HOST = "127.0.0.1"  # never 0.0.0.0: the session is a full shell on this phone
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I: easy to type
CODE_LENGTH = 12  # 32^12 = 60 bits
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC11B85"
MAX_MESSAGE = 1 << 20
REPLAY_BYTES = 256 * 1024
AUTH_TIMEOUT_S = 10
AUTH_FAIL_DELAY_S = 1.5

CLOSE_REPLACED = 4000
CLOSE_BAD_TOKEN = 4001
CLOSE_SESSION_ENDED = 4002
CLOSE_START_FAILED = 4003

log = logging.getLogger("claude-bridge")


# ---------------------------------------------------------------------- pairing code

def token_path():
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "universal-keyboard", "bridge-token")


def normalize_code(code):
    """Users may type the code with dashes, spaces or in lower case."""
    return "".join(ch for ch in str(code).upper() if ch.isalnum())


def format_code(token):
    return "-".join(token[i:i + 4] for i in range(0, len(token), 4))


def load_or_create_token(path, regenerate=False):
    """Returns (token, created). The file is created with mode 600, never wider."""
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    if regenerate and os.path.exists(path):
        os.remove(path)
    try:
        with open(path) as f:
            token = normalize_code(f.read())
        if len(token) >= CODE_LENGTH:
            if os.stat(path).st_mode & 0o077:
                os.chmod(path, 0o600)
                log.warning("Pairing code file was readable by others; fixed its permissions.")
            return token, False
        os.remove(path)  # empty or damaged: start over
    except FileNotFoundError:
        pass
    token = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token + "\n")
    return token, True


# ---------------------------------------------------------------------- terminal session

# Runs in the child between fork and exec of the real program. Popen's
# start_new_session already made it a session leader; this makes the PTY its
# controlling terminal so Ctrl+C, job control and SIGWINCH behave as in a real
# terminal. A separate interpreter avoids preexec_fn, which isn't thread-safe.
_CHILD_LAUNCHER = r"""
import fcntl, os, sys, termios
try:
    fcntl.ioctl(0, termios.TIOCSCTTY, 0)
except OSError:
    pass
try:
    os.execvp(sys.argv[1], sys.argv[1:])
except OSError as e:
    sys.stderr.write("claude_bridge: cannot start %s: %s\r\n" % (sys.argv[1], e.strerror))
    sys.exit(127)
"""


class Session:
    """One program running in a PTY, plus the recent output for replays."""

    def __init__(self, argv, cwd, cols, rows, on_output, on_exit):
        self._on_output = on_output
        self._on_exit = on_exit
        self._replay = bytearray()
        self._lock = threading.Lock()
        self.alive = True

        master, slave = os.openpty()
        self._master = master
        self._set_size(slave, cols, rows)
        env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor")
        try:
            self._proc = subprocess.Popen(
                [sys.executable, "-I", "-c", _CHILD_LAUNCHER, *argv],
                stdin=slave, stdout=slave, stderr=slave,
                cwd=cwd, env=env, start_new_session=True, close_fds=True,
            )
        finally:
            os.close(slave)
        log.info("Started %s (pid %d)", argv[0], self._proc.pid)
        threading.Thread(target=self._pump, name="pty-reader", daemon=True).start()

    @staticmethod
    def _set_size(fd, cols, rows):
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    def replay(self):
        with self._lock:
            return bytes(self._replay)

    def write(self, data):
        if not self.alive:
            return
        view = memoryview(data)
        while view:
            try:
                view = view[os.write(self._master, view):]
            except BlockingIOError:
                time.sleep(0.01)
            except OSError:
                return

    def resize(self, cols, rows):
        if not self.alive:
            return
        try:
            self._set_size(self._master, cols, rows)
            # Signal even when the size didn't change, so full-screen programs
            # redraw for a client that just reconnected.
            os.killpg(self._proc.pid, signal.SIGWINCH)
        except OSError:
            pass

    def terminate(self):
        if self.alive:
            try:
                os.killpg(self._proc.pid, signal.SIGHUP)
            except OSError:
                pass

    def _pump(self):
        while True:
            try:
                data = os.read(self._master, 65536)
            except OSError:  # EIO once the program has exited
                data = b""
            if not data:
                break
            with self._lock:
                self._replay += data
                if len(self._replay) > REPLAY_BYTES:
                    del self._replay[:len(self._replay) - REPLAY_BYTES]
            self._on_output(data)
        code = self._proc.wait()
        self.alive = False
        os.close(self._master)
        log.info("Program exited with code %s", code)
        self._on_exit(code)


# ---------------------------------------------------------------------- WebSocket

class WebSocket:
    """Server side of one RFC 6455 connection. Sends are thread-safe."""

    def __init__(self, sock):
        self._sock = sock
        self._send_lock = threading.Lock()
        self.closed = False

    def _recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("peer closed")
            buf += chunk
        return bytes(buf)

    def _send_frame(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(n)
        elif n < 1 << 16:
            header += struct.pack("!BH", 126, n)
        else:
            header += struct.pack("!BQ", 127, n)
        with self._send_lock:
            if self.closed:
                raise ConnectionError("closed")
            self._sock.sendall(bytes(header) + payload)

    def send_text(self, obj):
        self._send_frame(0x1, json.dumps(obj).encode())

    def send_binary(self, data):
        self._send_frame(0x2, data)

    def close(self, code=1000, reason=""):
        try:
            self._send_frame(0x8, struct.pack("!H", code) + reason.encode()[:120])
        except OSError:
            pass
        self.closed = True
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def receive(self):
        """Returns the next text (str) or binary (bytes) message; None once closed."""
        message, kind = bytearray(), None
        while True:
            b0, b1 = self._recv_exact(2)
            fin, opcode = b0 & 0x80, b0 & 0x0F
            if not b1 & 0x80:
                raise ConnectionError("client frames must be masked")
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._recv_exact(8))[0]
            if n > MAX_MESSAGE or len(message) + n > MAX_MESSAGE:
                raise ConnectionError("message too large")
            mask = self._recv_exact(4)
            payload = unmask(self._recv_exact(n), mask)
            if opcode == 0x8:
                self.close()
                return None
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x1, 0x2):
                kind = opcode
            elif opcode != 0x0 or kind is None:
                raise ConnectionError("bad opcode")
            message += payload
            if fin:
                return message.decode() if kind == 0x1 else bytes(message)


def unmask(data, mask):
    # XOR as one big integer: far faster than a per-byte loop in pure Python.
    n = len(data)
    key = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "big") ^ int.from_bytes(key, "big")).to_bytes(n, "big")


def read_http_request(sock):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk or len(data) > 16384:
            return None, {}
        data += chunk
    head = data.split(b"\r\n\r\n", 1)[0].decode("latin-1")
    lines = head.split("\r\n")
    headers = {}
    for line in lines[1:]:
        name, _, value = line.partition(":")
        headers[name.strip().lower()] = value.strip()
    return lines[0], headers


# ---------------------------------------------------------------------- server

class Bridge:
    """Owns the single session and the client currently attached to it."""

    def __init__(self, argv, cwd, token, port):
        self.argv = argv
        self.cwd = cwd
        self.port = port
        self._token = token
        self._lock = threading.Lock()
        self._session = None
        self._client = None

    def check_token(self, candidate):
        return hmac.compare_digest(normalize_code(candidate).encode(), self._token.encode())

    def attach(self, ws, cols, rows):
        """Makes [ws] the active client, starting a session if none is running."""
        with self._lock:
            old, self._client = self._client, ws
            replay = self._session is not None and self._session.alive
            if not replay:
                try:
                    self._session = Session(self.argv, self.cwd, cols, rows, self._output, self._exited)
                except OSError as e:
                    self._client = None
                    log.error("Could not start the session: %s", e)
                    ws.send_text({"type": "error", "message": f"Bridge could not start {self.argv[0]}: {e}"})
                    ws.close(CLOSE_START_FAILED, "start failed")
                    return None
            session = self._session
            # Inside the lock so no live output can overtake the replay.
            ws.send_text({"type": "ready", "replay": replay})
            if replay:
                data = session.replay()
                if data:
                    ws.send_binary(data)
        if old is not None and old is not ws:
            log.info("A new client took over the session")
            old.close(CLOSE_REPLACED, "replaced")
        session.resize(cols, rows)
        return session

    def detach(self, ws):
        with self._lock:
            if self._client is ws:
                self._client = None

    def _output(self, data):
        with self._lock:
            client = self._client
            try:
                if client is not None:
                    client.send_binary(data)
            except OSError:
                self._client = None

    def _exited(self, code):
        with self._lock:
            client, self._client = self._client, None
        if client is not None:
            try:
                client.send_text({"type": "exit", "code": code})
            except OSError:
                pass
            client.close(CLOSE_SESSION_ENDED, "session ended")

    def shutdown(self):
        with self._lock:
            if self._session is not None:
                self._session.terminate()


class Handler(socketserver.BaseRequestHandler):
    bridge = None  # set in main()

    def handle(self):
        sock = self.request
        sock.settimeout(AUTH_TIMEOUT_S)
        try:
            ws = self._handshake(sock)
            if ws is None:
                return
            cols, rows = self._authenticate(ws)
            if cols is None:
                return
            sock.settimeout(None)
            self._serve(ws, cols, rows)
        except (OSError, ConnectionError, ValueError) as e:
            log.debug("Connection dropped: %s", e)
        finally:
            try:
                sock.close()
            except OSError:
                pass

    def _handshake(self, sock):
        request_line, headers = read_http_request(sock)
        if request_line is None:
            return None
        host = headers.get("host", "").rsplit(":", 1)[0]
        is_upgrade = (
            request_line.startswith("GET ")
            and headers.get("upgrade", "").lower() == "websocket"
            and "upgrade" in headers.get("connection", "").lower()
            and headers.get("sec-websocket-version") == "13"
            and headers.get("sec-websocket-key")
        )
        # Rejecting other Host names stops DNS-rebinding pages from even trying.
        if not is_upgrade or host not in ("127.0.0.1", "localhost"):
            sock.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return None
        accept = base64.b64encode(
            hashlib.sha1((headers["sec-websocket-key"] + WS_GUID).encode()).digest()
        ).decode()
        sock.sendall((
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode())
        return WebSocket(sock)

    def _authenticate(self, ws):
        msg = ws.receive()
        try:
            hello = json.loads(msg) if isinstance(msg, str) else {}
        except ValueError:
            hello = {}
        if hello.get("type") != "auth" or not self.bridge.check_token(hello.get("token", "")):
            log.warning("Rejected a connection with a wrong pairing code")
            time.sleep(AUTH_FAIL_DELAY_S)  # slows down guessing
            ws.close(CLOSE_BAD_TOKEN, "bad pairing code")
            return None, None
        log.info("Client paired and connected")
        return clamp(hello.get("cols"), 80), clamp(hello.get("rows"), 24)

    def _serve(self, ws, cols, rows):
        session = self.bridge.attach(ws, cols, rows)
        if session is None:
            return
        try:
            while True:
                msg = ws.receive()
                if msg is None:
                    break
                if isinstance(msg, bytes):
                    session.write(msg)
                    continue
                cmd = json.loads(msg)
                kind = cmd.get("type")
                if kind == "input":
                    session.write(str(cmd.get("data", "")).encode())
                elif kind == "resize":
                    session.resize(clamp(cmd.get("cols"), 80), clamp(cmd.get("rows"), 24))
                # Unknown types are ignored, so newer apps keep working with
                # older bridges (and the protocol can grow, e.g. phase-B tools).
        finally:
            self.bridge.detach(ws)
            log.info("Client disconnected; the session keeps running")


def clamp(value, default):
    try:
        return max(2, min(int(value), 1000))
    except (TypeError, ValueError):
        return default


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


def check_pty_support():
    """Fails early with a useful message instead of on the first connection."""
    try:
        master, slave = os.openpty()
    except OSError as e:
        sys.exit(f"This environment can't create a terminal (pseudo-terminal: {e.strerror}). "
                 "Start the bridge from an interactive terminal session, where /dev/ptmx is available.")
    os.close(master)
    os.close(slave)


def main():
    parser = argparse.ArgumentParser(
        description="Serve Claude Code (or a shell) to the Universal Keyboard app on 127.0.0.1.",
        epilog="Anything after -- is the command to run, e.g.: claude_bridge.py -- claude --continue",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"port on 127.0.0.1 (default {DEFAULT_PORT})")
    parser.add_argument("--shell", action="store_true", help="run your login shell instead of claude")
    parser.add_argument("--cwd", default=None, help="working directory for the session (default: current)")
    parser.add_argument("--show-code", action="store_true", help="print the pairing code again")
    parser.add_argument("--new-code", action="store_true", help="replace the pairing code (re-pair the app)")
    parser.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    parser.add_argument("command", nargs=argparse.REMAINDER, help=argparse.SUPPRESS)
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(message)s", datefmt="%H:%M:%S",
    )

    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        command = [os.environ.get("SHELL") or "/bin/sh", "-l"] if args.shell else ["claude"]
    if shutil.which(command[0]) is None:
        log.warning("'%s' was not found on PATH; the session will fail to start.", command[0])
    cwd = os.path.abspath(os.path.expanduser(args.cwd)) if args.cwd else os.getcwd()
    check_pty_support()

    token, created = load_or_create_token(token_path(), regenerate=args.new_code)
    # The code goes to the terminal only (print, not the logger), and only when
    # it is new or explicitly asked for.
    if created or args.show_code:
        print(f"\n  Pairing code: {format_code(token)}\n"
              "  Enter it once in Universal Keyboard (Claude tab). Keep it private.\n", flush=True)
    else:
        print("Pairing code unchanged (show it with --show-code).", flush=True)

    Handler.bridge = bridge = Bridge(command, cwd, token, args.port)
    try:
        server = Server((HOST, args.port), Handler)
    except OSError as e:
        sys.exit(f"Cannot listen on {HOST}:{args.port}: {e.strerror}. Is the bridge already running?")

    def stop(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    log.info("Serving %s on ws://%s:%d (Ctrl+C to stop)", shlex.join(command), HOST, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        bridge.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
