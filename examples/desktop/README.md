# Desktop sandbox examples

Run an alpine desktop inside a jolt-microvm and look at it over VNC, or run a
single X11 app on the host display over ssh -X.

## What you need

- a jolt binary at 0.8.28 or newer
- libkrun + libkrunfw and a writable /dev/kvm (see the repo README)
- a VNC client for the desktop examples (vncviewer and remmina both work)
- a running X server and xauth on the host (for x11-app.clj only)

## Run

From the jolt-microvm repo root, with kvm available as a supplementary group:

```sh
sudo -u $USER --preserve-env env JOLT_BIN=/path/to/jolt \
  /path/to/jolt run examples/desktop/x11.clj
```

Replace x11.clj with wayland.clj for the Wayland desktop, or x11-app.clj for a
single X11 app on the host display.

### Run with Nix

The repo flake provides a nix-built jolt and libkrun (2.0 with the virtio-net
backend) plus the desktop apps:

```sh
nix run .#desktop-x11        # X11 desktop over VNC
nix run .#desktop-wayland    # Wayland desktop over VNC
nix run .#desktop-x11-app    # one X11 app over ssh -X
```

or `nix develop` for the full dev toolchain (jolt and libkrun, plus
ip/dnsmasq/nftables and a VNC client). sudo stays on the host; jolt-microvm
uses it for tap and apk seeding. The host needs a writable /dev/kvm.

The first run apk-installs the desktop profile into the cached alpine base, so
it downloads roughly a hundred packages. Later runs reuse that base.

## Connect

The desktop scripts print a map containing :url, for example
`10.44.1.11:5900`. Point a VNC client at that address. The guest listens on
its per-VM TAP IP, which is only reachable from the host, not from the wider
network.

The VNC server has no password (`-nopw` for x11vnc, `-d` view-only for
wayvnc). If you need input or a password, edit the scripts in
src/jolt/microvm/desktop.clj; the connection stays host-only unless you add
egress forwarding on the TAP.

## Stop

Press Enter in the example to stop the VM. To leave the VM booted and only
kill the desktop, call `(desktop/stop-desktop! v)`.
