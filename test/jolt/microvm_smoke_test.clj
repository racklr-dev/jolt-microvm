;; Integration test: boot alpine+openssh, exec over SSH, workspace round-trip, stop.
;; Requires: libkrun, /dev/kvm (user in kvm group), ssh-keygen + ssh, sudo.
(ns jolt.test.microvm-smoke
  (:require [jolt.microvm :as vm]
            [jolt.microvm.rootfs :as rootfs]))

(def cache "/tmp/jolt-microvm-cache")
(def workspace "/tmp/jolt-microvm-workspace")

;; one-time: seed the cached base with openssh (no buildah/docker needed)
(rootfs/install-alpine-packages! (rootfs/base! "alpine:3.20" cache) "openssh")

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir cache
                   :workspace workspace
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

(assert (= "sandbox\n" (:stdout (vm/exec v "whoami"))))
(assert (zero? (:exit (vm/exec v "echo round-trip > /workspace/from-vm.txt"))))
(assert (= "round-trip\n" (slurp (str workspace "/from-vm.txt"))))

(vm/stop! v)
(println "jolt-microvm smoke test: boot + ssh exec + workspace round-trip + stop OK")
