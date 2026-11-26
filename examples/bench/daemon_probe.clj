;; jolt daemon-exec throughput probe: K warm in-guest exec daemons (no SSH),
;; N concurrent daemon-run calls round-robin, real KVM. Boots once, then sweeps
;; host concurrency over the SAME warm VMs to find the ceiling in one boot.
;; argv = /usr/bin/getconf ARG_MAX (distroless-safe, near-zero guest work).
;; Each wave is also written to /tmp/jolt-bench-results.edn for the dashboard.
;;
;; Run (from the jolt-microvm checkout):
;;   jolt build -m jolt.exec.daemon -o /tmp/exec-daemon
;;   MP_N=2000 MP_K=10 MP_CONCS=16,64,128,256 MP_DAEMON_BIN=/tmp/exec-daemon \
;;     jolt run examples/bench/daemon_probe.clj
(ns jolt.bench.daemon-probe
  (:require [jolt.microvm :as microvm]
            [clojure.string :as str])
  (:import [java.util.concurrent Executors TimeUnit Callable]))

(def N (if-let [v (System/getenv "MP_N")] (Long/parseLong v) 2000))
(def K (if-let [v (System/getenv "MP_K")] (Long/parseLong v) 10))
(def CONCS (if-let [v (System/getenv "MP_CONCS")]
             (mapv #(Long/parseLong %) (clojure.string/split v #","))
             [16 64 128 256]))
(def IMAGE (or (System/getenv "MP_IMAGE") "gcr.io/distroless/python3-debian13:latest"))
(def CACHE (or (System/getenv "MP_CACHE") "/tmp/jolt-exec-cache"))
(def DAEMON-BIN (or (System/getenv "MP_DAEMON_BIN") "/tmp/exec-daemon"))
(def PD (System/getProperty "user.dir"))
(def BENCH-FILE "/tmp/jolt-bench-results.edn")

(defn write-bench! [m]
  (try
    (let [tmp (str BENCH-FILE ".tmp-" (System/nanoTime))]
      (spit tmp (pr-str m))
      (java.nio.file.Files/move
        (java.nio.file.Paths/get tmp (into-array String []))
        (java.nio.file.Paths/get BENCH-FILE (into-array String []))
        (into-array java.nio.file.CopyOption
                    [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
    (catch Exception _ nil)))

(defn boot-vms [k]
  (mapv (fn [i]
          (println "boot" i "of" k)
          (microvm/start-daemon!
            {:image IMAGE :daemon-binary DAEMON-BIN :cache-dir CACHE
             :project-dir PD :cpus 1 :memory-mib 1024}))
        (range k)))

(defn exec-one [d]
  (try
    (microvm/daemon-run d ["/usr/bin/getconf" "ARG_MAX"] :cwd "/" :timeout-ms 30000)
    (catch Throwable t
      {:exit -1 :stdout "" :stderr (str t)})))

(defn warm-wave [vms n conc]
  (let [nvms (count vms)
        tp (Executors/newFixedThreadPool (int conc))
        submit-one (fn [i]
                  (let [d (nth vms (mod i nvms))]
                    (exec-one d)))]
    (try
      (let [t0 (System/nanoTime)
            futs (mapv (fn [i] (.submit tp (reify Callable (call [_] (submit-one i))))) (range n))
            results (mapv (fn [f] (try (.get f 60 TimeUnit/SECONDS)
                                       (catch Throwable t {:exit -1 :stdout "" :stderr (str t)})))
                          futs)
            t1 (System/nanoTime)
            warm-ms (long (/ (- t1 t0) 1000000.0))
            oks (count (filter #(and (= 0 (:exit %))
                                     (not (clojure.string/blank? (.trim (:stdout %)))))
                               results))]
        {:warm-ms warm-ms :oks oks :n n})
      (finally (.shutdownNow tp)))))

(let [bench (atom {:source "daemon-probe" :updated-ms (System/currentTimeMillis)
                   :k K :image IMAGE :boot-ms 0 :done false :waves []})
      _ (write-bench! @bench)
      t0 (System/nanoTime)
      vms (boot-vms K)
      t1 (System/nanoTime)
      boot-ms (long (/ (- t1 t0) 1000000.0))
      _ (println "booted" (count vms) "in" boot-ms "ms")
      _ (println "RESULT K" K "boot-ms" boot-ms)
      _ (swap! bench assoc :boot-ms boot-ms :updated-ms (System/currentTimeMillis))
      _ (write-bench! @bench)]
  (doseq [conc CONCS]
    (let [r (warm-wave vms N conc)
          w (assoc r :conc conc
                   :execs-per-sec (double (/ (:oks r) (/ (:warm-ms r) 1000.0)))
                   :per-vm-per-sec (double (/ (:oks r) (/ (:warm-ms r) 1000.0) K))
                   :avg-latency-ms (double (/ (:warm-ms r) (max 1 (:oks r)))))]
      (swap! bench (fn [b] (-> b
                               (assoc :updated-ms (System/currentTimeMillis))
                               (update :waves conj w))))
      (write-bench! @bench)
      (println (str "WAVE conc=" conc " n=" N " warm-ms=" (:warm-ms r)
                    " oks=" (:oks r)
                    " execs-per-sec=" (:execs-per-sec w)
                    " per-vm-per-sec=" (:per-vm-per-sec w)
                    " avg-latency-ms=" (:avg-latency-ms w)))))
  (swap! bench assoc :updated-ms (System/currentTimeMillis) :done true)
  (write-bench! @bench)
  (let [rt (Runtime/getRuntime)
        used-mb (long (/ (- (.totalMemory rt) (.freeMemory rt)) 1048576.0))]
    (println "heap-used-mb" used-mb))
  (doseq [d vms] (try (microvm/stop-daemon! d) (catch Throwable _ nil))))

(println "DAEMON PROBE DONE")
(System/exit 0)
