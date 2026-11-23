;; writable-home? integration test: with the opt on, the sandbox home stays
;; writable, so a virtiofs mount can be created under /home/sandbox (the
;; faraday ~/.faraday case). The default (off) keeps the home read-only.
;; Run under a user with kvm access (this session: sudo -u jm --preserve-env).
(ns jolt.test.writable-home
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def cache "/tmp/jolt-microvm-cache-wh")
(def ws "/tmp/jolt-microvm-ws-wh")
(def cfg-dir "/tmp/jolt-microvm-cfg-wh")

(when (.exists (java.io.File. cache))
  (clojure.java.shell/sh "rm" "-rf" "--" cache))
(.mkdirs (java.io.File. ws))
(.mkdirs (java.io.File. cfg-dir))
(spit (str cfg-dir "/settings.json") "{\"answer\": 42}\n")

;; writable-home? true: mount under /home/sandbox must boot and be readable
(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir cache
                   :workspace ws
                   :mounts {cfg-dir "/home/sandbox/.faraday"}
                   :writable-home? true
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

(assert (= "sandbox\n" (:stdout (vm/exec v "whoami"))))
(assert (= "{\"answer\": 42}\n"
           (:stdout (vm/exec v "cat /home/sandbox/.faraday/settings.json"))))
(vm/stop! v)
(println "writable-home? true: home writable + mount under /home/sandbox OK")

;; default (opt off): the home stays non-writable to the sandbox user
(when (.exists (java.io.File. cache))
  (clojure.java.shell/sh "rm" "-rf" "--" cache))
(def v2 (vm/start! {:image "alpine:3.20"
                    :cache-dir cache
                    :workspace ws
                    :cpus 2 :memory-mib 512
                    :project-dir (System/getProperty "user.dir")}))
;; touch in the non-writable home must fail (exit 1), not silently succeed
(let [r (vm/exec v2 "touch /home/sandbox/should-fail.txt")]
  (assert (not (zero? (:exit r)))
          (str "expected touch in read-only home to fail, got " (pr-str r))))
(vm/stop! v2)
(println "default: home read-only (touch fails) OK")
