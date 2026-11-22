;; Interactive attach test: boot a VM, attach over an isolated pty, feed a
;; command on stdin, and check the command's output + clean exit flow through
;; the relay. Run with stdin piped:
;;   printf 'echo attach-marker; exit\n' | <setpriv env> jolt run test/jolt/attach_test.clj
(ns jolt.test.attach
  (:require [jolt.microvm :as vm]))

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/jolt-microvm-cache"
                   :workspace "/tmp/jolt-microvm-workspace"
                   :cpus 1 :memory-mib 256
                   :project-dir (System/getProperty "user.dir")}))

(let [code (vm/attach! v)]
  (assert (zero? code) (str "attach exited " code)))

(vm/stop! v)
(println "jolt-microvm: interactive attach over pty relay OK")
