;; Run one X11 app from the sandbox on the host display via ssh -X.
;; Needs a running X server on the host (DISPLAY set) and xauth installed.
;; Run from the jolt-microvm repo root:
;;   sudo -u $USER --preserve-env env JOLT_BIN=/path/to/jolt \
;;     jolt run examples/desktop/x11-app.clj
(require '[jolt.microvm :as vm])

(def v (vm/start! {:image "alpine:3.20"
                   :project-dir (System/getProperty "user.dir")
                   :packages ["xterm" "xauth" "font-noto" "font-misc-misc"]
                   :x11? true
                   :memory-mib 512}))

(println "Starting xterm on your display; close it to finish.")
(vm/x11-exec v "xterm")
(vm/stop! v)
