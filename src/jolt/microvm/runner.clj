;; Subprocess entry point: boot a microVM via libkrun 2.0 and block until exit.
;; Mirrors dirge's dirge-microvm-runner binary, on the new builder/device API.
(ns jolt.microvm.runner
  (:require [jolt.libkrun :as krun]
            [clojure.data.json :as json]))

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
        dhcp (boolean (get cfg "dhcp"))]
    (krun/boot! {:rootfs rootfs :cpus cpus :memory-mib memory-mib
                 :mounts mounts :net-taps net-taps :exec exec :dhcp dhcp
                 :init-binary (get cfg "init_binary")})))
