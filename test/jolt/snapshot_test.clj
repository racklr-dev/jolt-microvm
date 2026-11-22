;; Snapshot + reboot integration test (port of dirge's snapshot/reboot tests).
;; start! auto-seeds sshd; no manual apk step.
(ns jolt.test.snapshot
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def cache "/tmp/jolt-microvm-cache")
(def workspace "/tmp/jolt-microvm-workspace")
(def opts {:image "alpine:3.20"
           :cache-dir cache
           :workspace workspace
           :cpus 1 :memory-mib 256
           :project-dir (System/getProperty "user.dir")})

;; Boot 1: write a marker inside the VM, snapshot the rootfs.
(def v (vm/start! opts))
(assert (str/includes? (:stdout (vm/exec v "echo snapshot-marker > /tmp/marker && cat /tmp/marker"))
                       "snapshot-marker"))
(vm/save-snapshot! v "test-snap")
(assert (contains? (set (vm/list-snapshots v)) "test-snap"))
(vm/stop! v)

;; Restore the snapshot over the cached base, boot 2: marker must survive.
(vm/restore-snapshot! v "test-snap")
(def v2 (vm/start! opts))
(assert (str/includes? (:stdout (vm/exec v2 "cat /tmp/marker")) "snapshot-marker"))

;; Reboot discards in-VM changes: write an ephemeral marker, reboot, it is gone.
(vm/exec v2 "echo ephemeral > /tmp/ephemeral")
(def v3 (vm/reboot! v2))
(assert (not (zero? (:exit (vm/exec v3 "cat /tmp/ephemeral")))))

;; Cleanup: stop, delete the snapshot, list is empty.
(vm/stop! v3)
(vm/delete-snapshot! v3 "test-snap")
(assert (not (contains? (set (vm/list-snapshots v3)) "test-snap")))

(println "jolt-microvm: snapshot save/list/restore + reboot-discards + delete OK")
