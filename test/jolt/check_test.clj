;; Pre-flight smoke: loads jolt.microvm and runs check!. On CI (no KVM /
;; libkrun) it must still load and print a report; missing deps are
;; environmental, not a code failure.
(ns jolt.test.check
  (:require [jolt.microvm :as vm]))

(let [{:keys [ok? results]} (vm/check!)]
  (doseq [r results]
    (println (name (:name r)) "->" (name (:status r)) "|" (:message r)))
  (println "check! ok?" ok? "(environmental: CI runners have no KVM/libkrun)"))
