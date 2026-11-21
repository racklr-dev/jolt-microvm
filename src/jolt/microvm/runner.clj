;; Subprocess entry point: boots a microVM via libkrun and blocks until exit.
;; Mirrors dirge's dirge-microvm-runner binary.
(ns jolt.microvm.runner
  (:require [jolt.libkrun :as krun]
            [clojure.data.json :as json]))

(defn -main [& args]
  (let [cfg (json/read-str (first args))
        rootfs (get cfg "rootfs_path")
        workspace (get cfg "workspace_path")
        ssh-port (int (get cfg "ssh_port" 0))
        cpus (int (get cfg "cpus" 2))
        memory-mib (int (get cfg "memory_mib" 512))]
    (krun/boot! {:rootfs rootfs
                 :workdir "/"
                 :cpus cpus
                 :memory-mib memory-mib
                 :port-map (when (pos? ssh-port) (str ssh-port ":22"))
                 :virtiofs ["workspace" workspace]})))
