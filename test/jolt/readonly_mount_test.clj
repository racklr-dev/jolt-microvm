;; readonly mount integration test: a :readonly? mount is mounted -o ro in
;; the guest (writes fail), a default mount stays read-write. Run under kvm:
;; sudo -u jm --preserve-env /home/jm/.local/bin/jolt run test/jolt/readonly_mount_test.clj
(ns jolt.test.readonly-mount
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def cache "/tmp/jolt-microvm-cache-rom")
(def ws "/tmp/jolt-microvm-ws-rom")
(def ro-dir "/tmp/jolt-microvm-ro-rom")
(def rw-dir "/tmp/jolt-microvm-rw-rom")

(when (.exists (java.io.File. cache))
  (clojure.java.shell/sh "rm" "-rf" "--" cache))
(.mkdirs (java.io.File. ws))
(.mkdirs (java.io.File. ro-dir))
(.mkdirs (java.io.File. rw-dir))
(spit (str ro-dir "/readme.txt") "read me\n")

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir cache
                   :workspace ws
                   :writable-home? true
                   :mounts [{:host ro-dir :guest "/home/sandbox/ro-mnt" :readonly? true}
                            {:host rw-dir :guest "/home/sandbox/rw-mnt"}]
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

;; read-only mount: readable, not writable
(assert (= "read me\n" (:stdout (vm/exec v "cat /home/sandbox/ro-mnt/readme.txt"))))
(let [r (vm/exec v "touch /home/sandbox/ro-mnt/nope.txt")]
  (assert (not (zero? (:exit r)))
          (str "expected write to ro mount to fail, got " (pr-str r))))

;; default mount: read-write
(let [r (vm/exec v "touch /home/sandbox/rw-mnt/ok.txt && echo wrote")]
  (assert (zero? (:exit r)) (str "expected rw mount write to succeed, got " (pr-str r))))

(vm/stop! v)
(println "readonly mount: ro rejects writes, rw accepts them OK")
