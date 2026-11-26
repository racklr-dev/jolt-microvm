#!/usr/bin/env python3
"""Boot jolt-bare.elf under libkrun and drive the COM1 nREPL endpoint.

Full-duplex over the booter's stdin/stdout (serial console). Sends bencode
nREPL dicts, parses bencode responses, asserts stdout capture + eval +
sessions (clone/describe/close, per-session ns).
"""
import subprocess, sys, time, signal, os

HERE = os.path.dirname(os.path.abspath(__file__))

# --- bencode codec ---------------------------------------------------------
def benc(x):
    if isinstance(x, int):
        return b"i%de" % x
    if isinstance(x, str):
        x = x.encode()
    if isinstance(x, bytes):
        return b"%d:%s" % (len(x), x)
    if isinstance(x, list):
        return b"l" + b"".join(benc(v) for v in x) + b"e"
    if isinstance(x, dict):
        out = b"d"
        for k in sorted(x):
            out += benc(k) + benc(x[k])
        return out + b"e"
    raise TypeError(x)

def bdec(data, i=0):
    c = data[i:i+1]
    if c == b"i":
        j = data.index(b"e", i)
        return int(data[i+1:j]), j+1
    if c == b"l":
        i += 1; out = []
        while data[i:i+1] != b"e":
            v, i = bdec(data, i); out.append(v)
        return out, i+1
    if c == b"d":
        i += 1; out = {}
        while data[i:i+1] != b"e":
            k, i = bdec(data, i); v, i = bdec(data, i)
            out[k.decode()] = v
        return out, i+1
    if c and c.isdigit():
        j = data.index(b":", i); n = int(data[i:j]); i = j+1
        return data[i:i+n], i+n
    raise ValueError("bad bencode at %d: %r" % (i, data[i:i+6]))

def read_msg(f):
    buf = b""
    while True:
        b = f.read(1)
        if not b:
            raise EOFError("guest exited")
        buf += b
        try:
            m, i = bdec(buf, 0)
            if i == len(buf):
                return m
        except (ValueError, IndexError):
            pass

def wait_ready(f):
    line = b""
    while True:
        c = f.read(1)
        if not c:
            raise EOFError("no READY before exit")
        line += c
        if c == b"\n":
            sys.stderr.write("  | " + line.decode(errors="replace"))
            if line.strip() == b"READY":
                return
            line = b""

def s(x):
    return x.decode() if isinstance(x, bytes) else x

# --- run -------------------------------------------------------------------
proc = subprocess.Popen(
    ["sg", "kvm", "-c", "./booter ./jolt-bare.elf"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    cwd=HERE)

try:
    wait_ready(proc.stdout)
    t0 = time.time()
    print("READY after ~%.0fs" % (time.time() - t0), file=sys.stderr)

    def send(msg):
        proc.stdin.write(benc(msg)); proc.stdin.flush()
        return read_msg(proc.stdout)

    def evl(code, id, session=None):
        msg = {"op": "eval", "code": code, "id": id}
        if session: msg["session"] = session
        return send(msg)

    cases = [
        ("(+ 1 2)", "3"),
        ("{:a 1 :b 2}", "{:a 1, :b 2}"),
        ("(map inc [1 2 3])", "(2 3 4)"),
        ("(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))", "#'user/fib"),
        ("(fib 20)", "6765"),
    ]
    for code, want in cases:
        r = evl(code, "1")
        got = s(r.get("value", b""))
        ok = got == want
        print(("OK  " if ok else "FAIL") + " %s => %s (want %s)" % (code, got, want))
        if not ok:
            sys.exit(1)

    # stdout capture: println output must arrive in "out", not lost
    r = evl('(println "hello from bare metal")', "2")
    print("out=%r value=%r status=%r" % (s(r.get("out", b"")), s(r.get("value", b"")), [s(x) for x in r.get("status", [])]))
    assert s(r.get("out", b"")).strip() == "hello from bare metal", r
    assert [s(x) for x in r.get("status", [])] == ["done"], r

    # error path: err key + eval-error status
    r = evl("(undefined-fn 1)", "3")
    print("err=%r status=%r" % (s(r.get("err", b"")), [s(x) for x in r.get("status", [])]))
    assert r.get("err"), r
    assert "eval-error" in [s(x) for x in r.get("status", [])], r

    # --- sessions -------------------------------------------------------
    # describe advertises the ops map + versions
    r = send({"op": "describe", "id": "4"})
    ops = r.get("ops", {})
    print("describe ops=%r versions=%r" % (list(ops.keys()), r.get("versions", {})))
    assert set(ops.keys()) >= {"eval", "clone", "close", "describe"}, r
    assert "nrepl" in r.get("versions", {}), r

    # clone gives a fresh session id
    r = send({"op": "clone", "id": "5"})
    new = s(r.get("new-session", b""))
    print("clone new-session=%r status=%r" % (new, [s(x) for x in r.get("status", [])]))
    assert new and new != "0", r
    assert [s(x) for x in r.get("status", [])] == ["done"], r

    # session-local ns: def in the cloned session, then read it back there
    r = evl("(def foo 42)", "6", session=new)
    assert s(r.get("value", b"")) == "#'user/foo", r
    r = evl("foo", "7", session=new)
    print("session-local foo=%r" % (s(r.get("value", b""))))
    assert s(r.get("value", b"")) == "42", r

    # close drops the session (unknown-session evals still land in "user")
    r = send({"op": "close", "id": "8", "session": new})
    print("close status=%r" % ([s(x) for x in r.get("status", [])]))
    assert "session-closed" in [s(x) for x in r.get("status", [])], r

    print("ALL PASS")
finally:
    proc.kill()
    subprocess.run(["pkill", "-f", "booter ./jolt-bare.elf"], capture_output=True)
    proc.wait()
