;; Boot an alpine microVM with a Wayland desktop and serve it over VNC.
;; Run from the jolt-microvm repo root:
;;   sudo -u $USER --preserve-env env JOLT_BIN=/path/to/jolt \
;;     jolt run examples/desktop/wayland.clj
(require '[jolt.microvm :as vm]
         '[jolt.microvm.desktop :as desktop])

(def v (vm/start! {:image "alpine:3.20"
                   :project-dir (System/getProperty "user.dir")
                   :packages (desktop/profile :wayland)
                   :memory-mib 1024
                   :cpus 2}))

(println (desktop/start-wayland! v))
(println "Open the url above in a VNC client, then press Enter to stop the VM.")
(read-line)
(vm/stop! v)
