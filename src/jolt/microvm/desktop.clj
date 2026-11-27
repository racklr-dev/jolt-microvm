;; jolt.microvm.desktop — tiny alpine X11/Wayland desktops on virtio-gpu.
;;
;; The guest renders to libkrun's virtio-gpu scanout through the DRM KMS
;; device (/dev/dri/card0) instead of headless Xvfb/wayvnc-over-VNC. The host
;; side of the scanout is a libkrun display backend that writes each presented
;; frame to a PPM file (see jolt.microvm/start!'s :display opt), so the bench
;; reads frames natively — no x11vnc/wayvnc, no gvncviewer, no RFB decode.
;;
;; The X server (and sway's DRM backend) run as root inside the disposable
;; guest: rootless Xorg still wants /dev/tty0 for its VT, and a virtio-gpu
;; scanout guest has no VT. exec's :root? ssh's in as root via a key injected
;; into /root/.ssh/authorized_keys (StrictModes=no already set on the guest
;; sshd). Apps then render onto the scanout as root too — fine for a warp VM.
;; X11 uses the modesetting driver (bundled in Alpine's xorg-server package).
(ns jolt.microvm.desktop
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def profiles
  "apk packages that make up each desktop profile (Alpine 3.20)."
  ;; xorg-server lives in the community repo (already enabled in the alpine base)
  ;; and bundles the modesetting driver (p:xf86-video-modesetting).
  {:x11     ["xorg-server" "openbox" "xterm" "font-noto" "font-misc-misc" "xeyes"]
   :wayland ["seatd" "sway" "swaybg" "xwayland" "foot" "font-dejavu"]})

(defn profile
  "Return the apk package list for a desktop profile keyword."
  [k]
  (or (get profiles k)
      (throw (ex-info "unknown desktop profile" {:profile k :known (keys profiles)}))))

(def ^:private x11-script
  "mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix
setsid /usr/libexec/Xorg :0 -noreset -novtswitch -nolisten tcp -logfile /tmp/Xorg.0.log >/tmp/xorg.log 2>&1 &
i=0
while [ $i -lt 30 ]; do [ -S /tmp/.X11-unix/X0 ] && break; sleep 1; i=$((i+1)); done
if [ ! -S /tmp/.X11-unix/X0 ]; then cat /tmp/xorg.log >&2; exit 1; fi
setsid sh -c 'DISPLAY=:0 openbox' >/tmp/openbox.log 2>&1 &
setsid sh -c 'DISPLAY=:0 xterm' >/tmp/xterm.log 2>&1 &
echo x11-ready")

(def ^:private wayland-script
  "mkdir -p /tmp/xdg
chmod 700 /tmp/xdg
setsid seatd >/tmp/seatd.log 2>&1 &
i=0
while [ $i -lt 10 ]; do [ -S /run/seatd.sock ] && break; sleep 1; i=$((i+1)); done
cat >/tmp/sway.conf <<'EOF'
output * {
  bg #000000 solid_color
}
exec foot
EOF
export XDG_RUNTIME_DIR=/tmp/xdg
export WLR_BACKENDS=drm
export WLR_RENDERER=pixman
export WLR_LIBINPUT_NO_DEVICES=1
export XDG_SESSION_TYPE=wayland
export LIBSEAT_BACKEND=seatd
setsid sway -c /tmp/sway.conf >/tmp/sway.log 2>&1 &
i=0
while [ $i -lt 30 ]; do
  if ls /tmp/xdg/wayland-* >/dev/null 2>&1; then break; fi
  sleep 1; i=$((i+1))
done
if ! ls /tmp/xdg/wayland-* >/dev/null 2>&1; then cat /tmp/seatd.log /tmp/sway.log >&2; exit 1; fi
echo wayland-ready")

(defn- display-info
  "The frame source for the bench: the PPM path the runner writes presented
  frames to, plus the scanout size. The path lives on the VM record's :display
  (start! resolves it from :display opts)."
  [vm]
  (let [d (:display vm)]
    (merge {:ip (:ip vm)}
           (when d {:display-path (:path d)
                    :width (:width d)
                    :height (:height d)}))))

(defn- wait-for-ready!
  "Run a desktop start script as root and return display-info once it reports
  ready."
  [vm script marker]
  (let [r (vm/exec! vm script :cwd "/tmp" :timeout-ms 120000 :root? true)]
    (when-not (str/includes? (:stdout r) marker)
      (throw (ex-info "desktop did not come up" r)))
    (display-info vm)))

(defn start-x11!
  "Start the X11 desktop (Xorg + modesetting on /dev/dri/card0 + openbox +
  xterm) in a running VM booted with :display. Returns {:ip :display-path
  :width :height}; the bench reads the PPM at :display-path."
  [vm]
  (wait-for-ready! vm x11-script "x11-ready"))

(defn start-wayland!
  "Start the Wayland desktop (sway + wlroots DRM backend on /dev/dri/card0 +
  foot) in a running VM booted with :display. Returns {:ip :display-path
  :width :height}; the bench reads the PPM at :display-path."
  [vm]
  (wait-for-ready! vm wayland-script "wayland-ready"))

(defn stop-desktop!
  "Stop desktop processes in a running VM (the VM stays booted)."
  [vm]
  (vm/exec vm "pkill -f 'Xorg|Xvfb|x11vnc|sway|wayvnc' || true" :root? true))
