;; Subprocess entry point: boot a microVM via libkrun 2.0 and block until exit.
;; Mirrors dirge's dirge-microvm-runner binary, on the new builder/device API.
;;
;; The runner is a separate process from jolt-microvm (spawned by spawn-runner!),
;; so the virtio-gpu display sink — a Clojure fn in this process — cannot cross
;; the JSON config boundary. The :display map travels as data (width/height/
;; virgl-flags/path) and this process builds the sink: decode each presented
;; frame to RGBA and write it as a P6 PPM at :path, atomically, so a reader
;; (the bench canvas) never observes a half-written frame.
(ns jolt.microvm.runner
  (:require [jolt.libkrun :as krun]
            [jolt.framebuffer :as fb]
            [clojure.data.json :as json]))

(defn- ppm-sink
  "Return a display sink that writes each frame to `path` as a P6 binary PPM.
  Write a sibling tmp file then atomically rename it over the target, so a
  concurrent reader sees either the previous complete frame or the new one."
  [path]
  (let [p   (java.nio.file.Paths/get path (into-array String []))
        tmp (java.nio.file.Paths/get (str path ".tmp") (into-array String []))]
    (fn [frame]
      (let [bytes (fb/->ppm-bytes (fb/rgba frame))]
        (java.nio.file.Files/write tmp bytes (into-array java.nio.file.OpenOption []))
        (java.nio.file.Files/move tmp p
          (into-array java.nio.file.CopyOption
                      [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))

(defn -main [& args]
  (let [cfg (json/read-str (first args))
        rootfs (get cfg "rootfs_path")
        cpus (int (get cfg "cpus" 2))
        memory-mib (int (get cfg "memory_mib" 512))
        mounts (mapv (fn [m] [(get m "tag") (get m "host")]) (get cfg "mounts"))
        net-taps (mapv (fn [t] {:id (get t "id") :tap (get t "tap")
                                :mac (when-let [m (get t "mac")] (byte-array m))})
                       (get cfg "net_taps"))
        exec (vec (get cfg "exec"))
        dhcp (boolean (get cfg "dhcp"))
        display-cfg (get cfg "display")
        display (when display-cfg
                  {:width (int (get display-cfg "width" 1280))
                   :height (int (get display-cfg "height" 720))
                   :virgl-flags (int (get display-cfg "virgl-flags" 1))
                   :sink (ppm-sink (get display-cfg "path"))})]
    (krun/boot! {:rootfs rootfs :cpus cpus :memory-mib memory-mib
                 :mounts mounts :net-taps net-taps :exec exec :dhcp dhcp
                 :init-binary (get cfg "init_binary")
                 :display display})))
