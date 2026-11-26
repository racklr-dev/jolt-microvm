(ns jolt.examples.unikernel-boot
  (:require [jolt.libkrun :as krun]))

(krun/boot-external! {:kernel (System/getenv "KERNEL")
                      :cpus 1 :memory-mib 64})
