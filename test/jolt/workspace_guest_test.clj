;; workspace-guest integration test: mount the workspace at a custom guest path
;; so guest pwd shows the host cwd's path (the faraday pwd fix).
(ns jolt.test.workspace-guest
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def v (vm/start! {:image "alpine:3.20"
                   :workspace "/tmp/jolt-microvm-ws-wsg"
                   :workspace-guest "/hostcwd"
                   :cpus 2 :memory-mib 512
                   :project-dir (System/getProperty "user.dir")}))

(assert (= "/hostcwd\n" (:stdout (vm/exec v "pwd"))))
(assert (= "hi\n" (:stdout (vm/exec v "echo hi > /hostcwd/x.txt && cat /hostcwd/x.txt"))))
(vm/stop! v)
(println "workspace-guest: pwd shows the custom guest path OK")
