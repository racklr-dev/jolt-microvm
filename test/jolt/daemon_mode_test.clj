;; In-VM exec daemon test: bake the compiled jolt-exec daemon into a distroless
;; (glibc) rootfs, boot it as the entrypoint, forward a host port, and exec
;; python3 over the JSON line protocol.
(ns jolt.test.daemon-mode
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def d (vm/start-daemon! {:image "gcr.io/distroless/python3-debian13:latest"
                          :daemon-binary "/tmp/exec-daemon"
                          :cache-dir "/tmp/jolt-daemon-cache"
                          :cpus 2 :memory-mib 512
                          :project-dir "/tmp/jolt-microvm"}))

;; direct argv (distroless has no shell) — python3 lives in the image
(let [r (vm/daemon-run d ["/usr/bin/python3" "-c" "print('in-vm-daemon-ok')"])]
  (assert (zero? (:exit r)) (str "exit " (:exit r) " err " (:stderr r)))
  (assert (str/includes? (:stdout r) "in-vm-daemon-ok"))
  (println :argv-ok (str/trim (:stdout r))))

;; nonzero exit code through the daemon
(let [r (vm/daemon-run d ["/usr/bin/python3" "-c" "import sys; sys.exit(5)"])]
  (assert (= 5 (:exit r)) (str "exit " (:exit r))))

;; latency
(let [n 20
      t (System/nanoTime)]
  (dotimes [_ n] (vm/daemon-run d ["/usr/bin/python3" "-c" "pass"]))
  (let [ms (/ (/ (- (System/nanoTime) t) 1e6) n)]
    (println :daemon-run-ms-per-call (Math/round (double ms)))))

(vm/stop-daemon! d)
(println "jolt-microvm: in-VM exec daemon (distroless) OK")
