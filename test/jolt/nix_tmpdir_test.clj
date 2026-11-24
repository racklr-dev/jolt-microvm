;; Unit test: sane-tmpdir* rejects a stale nix-shell TMPDIR (the failure that
;; breaks `nix print-dev-env` with ENOENT) and keeps a real, writable dir.
(ns jolt.test.nix-tmpdir
  (:require [jolt.microvm.nix :as nix]))

(def sane* #'jolt.microvm.nix/sane-tmpdir*)

(assert (= "/tmp" (sane* "/tmp/nix-shell.0gCuxA"))) ; stale, nonexistent
(assert (= "/tmp" (sane* nil)))                       ; nil -> /tmp
(assert (= "/tmp" (sane* "/tmp")))                   ; real + writable
(assert (= "/tmp" (sane* "/root")))                  ; exists, not writable
(let [d "/tmp/jm-nix-tmpdir-ok"]
  (.mkdirs (java.io.File. d))
  (assert (= d (sane* d))))

(println "jolt-microvm nix-tmpdir: stale TMPDIR falls back to /tmp OK")
