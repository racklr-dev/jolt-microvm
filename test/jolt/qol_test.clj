;; QoL integration test: :packages auto-install (python3), extra volume mount,
;; exec!, with-vm, alive?/info. (No file-copy surface — config only.)
;; Run under a user with kvm group access (see README).
(ns jolt.test.qol
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def cache "/tmp/jolt-microvm-qol-cache")
(def workspace "/tmp/jolt-microvm-qol-workspace")
(def extra "/tmp/jolt-microvm-qol-extra")

(doseq [d [cache workspace extra]]
  (clojure.java.shell/sh "rm" "-rf" "--" d)
  (.mkdirs (java.io.File. d)))

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir cache
                   :workspace workspace
                   :packages ["python3"]
                   :mounts {extra "/data"}
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

;; packages: python3 was auto-seeded into the base and is runnable
(let [r (vm/exec v "python3 -c 'print(42)'")]
  (assert (zero? (:exit r)))
  (assert (= "42\n" (:stdout r))))

;; extra mount: guest writes to /data land in the host dir
(assert (zero? (:exit (vm/exec v "echo mount-ok > /data/from-vm.txt"))))
(assert (= "mount-ok\n" (slurp (str extra "/from-vm.txt"))))

;; exec! throws on nonzero, returns on success
(try
  (vm/exec! v "sh -c 'echo boom >&2; exit 3'")
  (throw (ex-info "expected exec! to throw" {}))
  (catch clojure.lang.ExceptionInfo e
    (assert (= 3 (:exit (ex-data e))))))
(assert (= "ok\n" (:stdout (vm/exec! v "echo ok"))))

;; alive? + info
(assert (vm/alive? v))
(let [i (vm/info v)]
  (assert (= "alpine:3.20" (:image i)))
  (assert (= 2 (count (:mounts i))) "workspace + extra mount"))

(vm/stop! v)
(assert (not (vm/alive? v)))

;; with-vm auto-stops
(let [stopped? (vm/with-vm [w {:image "alpine:3.20"
                               :cache-dir cache
                               :workspace workspace
                               :cpus 1 :memory-mib 256
                               :project-dir (System/getProperty "user.dir")}]
                  (assert (str/includes? (:stdout (vm/exec w "uname -s")) "Linux"))
                  true)]
  (assert stopped?))

(println "jolt-microvm: packages + extra mount + exec! + with-vm OK")
