;; Probe: boot alpine with a TAP attached (krun_add_net_tap) and dump the
;; guest's network state — does libkrun's init DHCP on eth0, or leave it bare?
(ns jolt.test.nettap-probe
  (:require [jolt.microvm.rootfs :as rootfs]
            [clojure.data.json :as json]))

(def rootfs (rootfs/base! "alpine:3.20" "/tmp/nettap-cache"))
(def cfg {"mode" "exec"
           "rootfs_path" rootfs
           "cpus" 1 "memory_mib" 256
           "exec" ["/bin/sh" "-c" "ip link 2>&1; ip addr 2>&1; ip route 2>&1; sleep 2"]
           "net_taps" [{"name" "jolt0"}]})

(def pb (ProcessBuilder. ["/home/jm/.local/bin/jolt" "run" "-m" "jolt.microvm.runner" (json/write-str cfg)]))
(.directory pb (java.io.File. "/tmp/jolt-microvm"))
(.put (.environment pb) "JOLT_SKIP_MIN_VERSION" "1")
(.redirectErrorStream pb true)
(def proc (.start pb))
(def in (.getInputStream proc))
(def baos (java.io.ByteArrayOutputStream.))
(def buf (byte-array 8192))
(loop []
  (let [n (.read in buf)]
    (when (pos? n)
      (.write baos buf 0 n)
      (recur))))
(println (String. (.toByteArray baos) "UTF-8"))
