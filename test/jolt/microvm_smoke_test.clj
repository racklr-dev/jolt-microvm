;; Integration test: fresh-cache auto-seed (start! seeds alpine sshd itself),
;; boot, exec over SSH, workspace round-trip, exec timeout, stop.
;; Run under a user with kvm group access (this session: sudo setpriv --groups ... kvm).
(ns jolt.test.microvm-smoke
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def cache "/tmp/jolt-microvm-cache-fresh")
(def workspace "/tmp/jolt-microvm-workspace-fresh")

;; wipe so the auto-seed path really runs
(when (.exists (java.io.File. cache))
  (assert (= :stopped (let [r (clojure.java.shell/sh "rm" "-rf" "--" cache)]
                        (when-not (zero? (:exit r)) (throw (ex-info "rm failed" {:e r}))) :stopped))))

;; start! must seed sshd into the base itself (no manual install-alpine-packages!)
(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir cache
                   :workspace workspace
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

(assert (= "sandbox\n" (:stdout (vm/exec v "whoami"))))
(assert (zero? (:exit (vm/exec v "echo round-trip > /workspace/from-vm.txt"))))
(assert (= "round-trip\n" (slurp (str workspace "/from-vm.txt"))))

;; exec timeout: a 60s sleep with a 3s wall-clock timeout must throw, not hang
(try
  (vm/exec v "sleep 60" :timeout-ms 3000)
  (throw (ex-info "expected timeout, but exec returned" {}))
  (catch clojure.lang.ExceptionInfo e
    (assert (str/includes? (ex-message e) "timed out")
            (str "unexpected error: " (ex-message e)))))

(vm/stop! v)
(println "jolt-microvm smoke: fresh auto-seed + boot + ssh exec + workspace + timeout + stop OK")
