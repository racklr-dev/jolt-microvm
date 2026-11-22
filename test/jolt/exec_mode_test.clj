;; Direct-exec mode test: boot an image straight into a command (no SSH/shell),
;; for alpine (shell present) and distroless (no shell/sshd), with exit-code
;; capture in both directions.
(ns jolt.test.exec-mode
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

;; alpine direct-exec: /bin/sh runs, nonzero exit code comes back
(let [r (vm/run-image! {:image "alpine:3.20"
                        :cmd ["/bin/sh" "-c" "echo exec-ok; exit 7"]
                        :cache-dir "/tmp/jolt-exec-cache"
                        :cpus 1 :memory-mib 256
                        :project-dir (System/getProperty "user.dir")})]
  (assert (= 7 (:exit r)) (str "alpine exit " (:exit r)))
  (assert (str/includes? (:stdout r) "exec-ok") (str "stdout: " (:stdout r))))

;; distroless direct-exec: python3 boots with no shell/sshd at all
(let [r (vm/run-image! {:image "gcr.io/distroless/python3-debian13:latest"
                        :cmd ["/usr/bin/python3" "-c" "print('distroless-ok')"]
                        :cache-dir "/tmp/jolt-exec-cache"
                        :cpus 1 :memory-mib 256
                        :project-dir (System/getProperty "user.dir")})]
  (assert (zero? (:exit r)) (str "distroless exit " (:exit r) " err " (:stderr r)))
  (assert (str/includes? (:stdout r) "distroless-ok") (str "stdout: " (:stdout r))))

;; distroless nonzero exit code
(let [r (vm/run-image! {:image "gcr.io/distroless/python3-debian13:latest"
                        :cmd ["/usr/bin/python3" "-c" "import sys; sys.exit(3)"]
                        :cache-dir "/tmp/jolt-exec-cache"
                        :cpus 1 :memory-mib 256
                        :project-dir (System/getProperty "user.dir")})]
  (assert (= 3 (:exit r)) (str "distroless nonzero exit " (:exit r))))

(println "jolt-microvm: direct-exec mode (alpine + distroless) OK")
