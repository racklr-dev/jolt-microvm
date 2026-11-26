;; jolt.microvm.desktop — tiny alpine X11/Wayland desktops served over VNC.
;;
;; The guest renders with software (Xvfb for X11, wlroots headless for
;; Wayland) and serves the framebuffer with x11vnc/wayvnc on port 5900. The
;; host reaches that port directly at the VM's TAP lease IP, so no libkrun
;; GPU/display device and no host port forwarding are needed.
(ns jolt.microvm.desktop
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def profiles
  "apk packages that make up each desktop profile (Alpine 3.20)."
  {:x11     ["xvfb" "x11vnc" "openbox" "xterm" "font-noto" "font-misc-misc" "xeyes"]
   :wayland ["sway" "swaybg" "xwayland" "wayvnc" "foot" "font-dejavu"]})

(defn profile
  "Return the apk package list for a desktop profile keyword."
  [k]
  (or (get profiles k)
      (throw (ex-info "unknown desktop profile" {:profile k :known (keys profiles)}))))

(def ^:private x11-script
  "mkdir -p /tmp/.X11-unix
setsid Xvfb :0 -screen 0 1280x720x24 -ac -nolisten tcp >/tmp/xvfb.log 2>&1 &
i=0
while [ $i -lt 30 ]; do [ -S /tmp/.X11-unix/X0 ] && break; sleep 1; i=$((i+1)); done
setsid sh -c 'DISPLAY=:0 openbox' >/tmp/openbox.log 2>&1 &
setsid sh -c 'DISPLAY=:0 xterm' >/tmp/xterm.log 2>&1 &
setsid x11vnc -display :0 -forever -shared -nopw -rfbport 5900 >/tmp/x11vnc.log 2>&1 &
i=0
while [ $i -lt 60 ]; do
  if nc -z 127.0.0.1 5900 2>/dev/null; then echo vnc-ready; exit 0; fi
  sleep 1; i=$((i+1))
done
cat /tmp/xvfb.log >&2 || true
cat /tmp/x11vnc.log >&2 || true
exit 1")

(def ^:private wayland-script
  "mkdir -p /tmp/xdg
chmod 700 /tmp/xdg
cat >/tmp/sway.conf <<'EOF'
output HEADLESS-1 {
  resolution 1280x720
  bg #000000 solid_color
}
exec foot
EOF
XDG_RUNTIME_DIR=/tmp/xdg WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 WLR_RENDERER=pixman setsid sway -c /tmp/sway.conf >/tmp/sway.log 2>&1 &
i=0
while [ $i -lt 30 ]; do
  if ls /tmp/xdg/wayland-* >/dev/null 2>&1; then break; fi
  sleep 1; i=$((i+1))
done
WAYLAND_SOCK=$(ls /tmp/xdg/wayland-* 2>/dev/null | head -1)
XDG_RUNTIME_DIR=/tmp/xdg WAYLAND_DISPLAY=$(basename $WAYLAND_SOCK) setsid wayvnc -d 0.0.0.0 5900 >/tmp/wayvnc.log 2>&1 &
i=0
while [ $i -lt 60 ]; do
  if nc -z 127.0.0.1 5900 2>/dev/null; then echo vnc-ready; exit 0; fi
  sleep 1; i=$((i+1))
done
cat /tmp/sway.log >&2 || true
cat /tmp/wayvnc.log >&2 || true
exit 1")

(defn- wait-for-vnc!
  "Run the desktop start script and return {:ip :port :url} once the guest
  VNC server accepts connections."
  [vm script]
  (let [r (vm/exec! vm script :cwd "/tmp" :timeout-ms 120000)]
    (when-not (str/includes? (:stdout r) "vnc-ready")
      (throw (ex-info "desktop did not come up" r)))
    {:ip (:ip vm) :port 5900 :url (str (:ip vm) ":5900")}))

(defn start-x11!
  "Start the X11 desktop (Xvfb + openbox + xterm + x11vnc) in a running VM.
  Returns {:ip :port :url}; open :url in any VNC client."
  [vm]
  (wait-for-vnc! vm x11-script))

(defn start-wayland!
  "Start the Wayland desktop (sway + foot + wayvnc) in a running VM.
  Returns {:ip :port :url}; open :url in any VNC client."
  [vm]
  (wait-for-vnc! vm wayland-script))

(defn stop-desktop!
  "Stop desktop processes in a running VM (the VM stays booted)."
  [vm]
  (vm/exec vm "pkill -f 'Xvfb|x11vnc|sway|wayvnc' || true"))
