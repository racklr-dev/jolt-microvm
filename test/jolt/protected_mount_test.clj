;; protected mount guard test: a mount that would clobber a boot-critical guest
;; path (/home/sandbox, /home/sandbox/.ssh, /workspace, /run, /var/empty, or an
;; ancestor of one) must throw a clear error, not boot into a broken sshd.
(ns jolt.test.protected-mount
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(defn check-throws-protected [guest]
  (try
    (vm/start! {:image "alpine:3.20"
                :cache-dir "/tmp/jolt-microvm-cache-prot"
                :workspace "/tmp/jolt-microvm-ws-prot"
                :mounts [{:host "/tmp/jolt-microvm-ws-prot" :guest guest}]
                :project-dir (System/getProperty "user.dir")})
    (throw (ex-info (str "expected protected-mount throw for " guest) {}))
    (catch clojure.lang.ExceptionInfo e
      (if (str/includes? (ex-message e) "protected sandbox path")
        (println "PASS protected:" guest)
        (do (println "FAIL protected:" guest "->" (ex-message e))
            (System/exit 1))))))

(check-throws-protected "/home/sandbox")
(check-throws-protected "/home/sandbox/.ssh")
(check-throws-protected "/home")
(check-throws-protected "/workspace")
(check-throws-protected "/")
(println "protected mount guard OK")
