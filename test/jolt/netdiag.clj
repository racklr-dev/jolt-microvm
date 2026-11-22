;; Network diagnostic: boot alpine and inspect DNS, interfaces, routes, and
;; outbound reachability.
(ns jolt.test.netdiag
  (:require [jolt.microvm :as vm]))

(def v (vm/start! {:image "alpine:3.20"
                   :cache-dir "/tmp/netdiag-cache"
                   :workspace "/tmp/netdiag-ws"
                   :cpus 2 :memory-mib 512
                   :project-dir "/tmp/jolt-microvm"}))

(doseq [c ["cat /etc/resolv.conf"
           "ip addr show eth0 2>&1"
           "ip route 2>&1"
           "nslookup example.com 2>&1 | head -5"
           "wget -qO- -T 5 http://example.com 2>&1 | head -2"
           "wget -qO- -T 5 https://1.1.1.1 2>&1 | head -2"]]
  (let [r (vm/exec v c)]
    (println "====" c)
    (println "  stdout:" (clojure.string/trim (:stdout r)))
    (println "  stderr:" (clojure.string/trim (:stderr r)) "exit" (:exit r))))

(vm/stop! v)
(println "netdiag done")
