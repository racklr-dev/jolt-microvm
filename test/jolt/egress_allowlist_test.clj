;; Egress allow-list e2e: boot an SSH-mode VM with :egress and prove dnsmasq
;; resolves allow-listed domains while sinkholing everything else.
;; Run (privileged, on a KVM host):
;;   sudo -u jm --preserve-env ~/.local/bin/jolt run test/jolt/egress_allowlist_test.clj
(ns jolt.test.egress-allowlist
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def workspace "/tmp/jolt-egress-allowlist-ws")
(.mkdirs (java.io.File. workspace))

(def v (vm/start! {:image "alpine:3.20"
                   :workspace workspace
                   :egress {:allow-domains ["example.com" "iana.org"]
                            :allow-ports [80 443]
                            :upstream "1.1.1.1"}
                   :cpus 2 :memory-mib 768
                   :project-dir (System/getProperty "user.dir")}))

(try
  (let [out (:stdout (vm/exec v "nslookup example.com 2>&1"))]
    (assert (str/includes? out "Name:") (str "example.com should resolve, got: " out))
    (assert (not (str/includes? out "0.0.0.0")) (str "example.com must not sinkhole: " out)))

  (let [out (:stdout (vm/exec v "nslookup google.com 2>&1"))]
    (assert (str/includes? out "0.0.0.0") (str "google.com should be sinkholed, got: " out)))

  (println "jolt-microvm egress-allowlist: allow-listed DNS resolves, others sinkholed OK")
  (finally (vm/stop! v)))
