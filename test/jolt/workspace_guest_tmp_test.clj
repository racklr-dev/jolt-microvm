;; workspace-guest-under-/tmp regression test: faraday mounts the host
;; cwd at its own absolute path, which is often under /tmp. The /tmp tmpfs
;; must mount BEFORE the virtiofs mounts, else it shadows the workspace mount
;; and `cd <guest>` fails (exit 2, empty stdout).
(ns jolt.test.workspace-guest-tmp
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def workspace "/tmp/jolt-microvm-ws-wsg-tmp")
(.mkdirs (java.io.File. workspace))

(def v (vm/start! {:image "alpine:3.20"
                   :workspace workspace
                   :workspace-guest workspace
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

(assert (= (str workspace "\n") (:stdout (vm/exec v "pwd"))))
(assert (= "hi\n" (:stdout (vm/exec v (str "echo hi > " workspace "/x.txt && cat " workspace "/x.txt")))))
(assert (zero? (:exit (vm/exec v "echo ok"))))
(vm/stop! v)
(println "workspace-guest under /tmp: pwd + round-trip + exec OK")
