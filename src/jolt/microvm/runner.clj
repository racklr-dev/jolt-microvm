;; Subprocess entry point: boots a microVM via libkrun and blocks until exit.
;; Mirrors dirge's dirge-microvm-runner binary, including the RLIMIT_NOFILE cap.
(ns jolt.microvm.runner
  (:require [jolt.libkrun :as krun]
            [jolt.ffi :as ffi]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;; -- libc rlimit -------------------------------------------------------------
(ffi/defcfn c-getrlimit "getrlimit" [:int :pointer] :int)
(ffi/defcfn c-setrlimit "setrlimit" [:int :pointer] :int)

(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
(def ^:private rlimit-nofile 7)
(def ^:private max-nofile 1048576)
(def ^:private rlimit-size 16)  ; struct rlimit {rlim_cur ulong; rlim_max ulong}

(defn- capped-nofile!
  "Raise host RLIMIT_NOFILE to min(max, 1_048_576) (floored at 4096) and return
  the \"RLIMIT_NOFILE=cur:max\" string for krun_set_rlimits. Returns nil on
  macOS, where libkrun must NOT receive rlimits (RLIM_INFINITY overflows the
  aarch64 kernel cmdline)."
  []
  (when-not macos?
    (let [p (ffi/alloc rlimit-size)]
      (try
        (when-not (zero? (c-getrlimit rlimit-nofile p))
          (throw (ex-info "getrlimit(RLIMIT_NOFILE) failed" {:errno (ffi/errno)})))
        (let [rlim-max (ffi/read p :ulong 8)
              capped (min rlim-max max-nofile)
              cur (max 4096 capped)]
          (ffi/write p :ulong cur 0)
          (when-not (zero? (c-setrlimit rlimit-nofile p))
            (throw (ex-info "setrlimit(RLIMIT_NOFILE) failed" {:errno (ffi/errno)})))
          ;; libkrun 1.19.6's init parses the rlimit ID with strtoull, so a
          ;; NAME ("RLIMIT_NOFILE=...") parses as 0 = RLIMIT_CPU, which sets the
          ;; CPU limit to 0 and SIGKILLs the guest. Pass the NUMERIC id: 7.
          (str "7=" cur ":" capped))
        (finally (ffi/free p))))))

(defn -main [& args]
  (let [cfg (json/read-str (first args))
        rootfs (get cfg "rootfs_path")
        workspace (get cfg "workspace_path")
        ssh-port (int (get cfg "ssh_port" 0))
        cpus (int (get cfg "cpus" 2))
        memory-mib (int (get cfg "memory_mib" 512))
        rlimits (when-let [n (capped-nofile!)] [n])
        virtiofs (or (seq (mapv (fn [m] [(get m "tag") (get m "host")]) (get cfg "mounts")))
                     (when workspace [["workspace" workspace]]))]
    (krun/boot! {:rootfs rootfs
                 :workdir "/"
                 :cpus cpus
                 :memory-mib memory-mib
                 :rlimits rlimits
                 :port-map (when (pos? ssh-port) (str ssh-port ":22"))
                 :virtiofs virtiofs})))
