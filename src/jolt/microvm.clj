;; jolt.microvm — run commands inside an OCI-image-backed libkrun microVM
;; over SSH (port of dirge's sandbox/microvm: runner.rs, rootfs.rs, ssh.rs).
(ns jolt.microvm
  (:require [jolt.microvm.keys :as keys]
            [jolt.microvm.rootfs :as rootfs]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json])
  (:import [java.net Socket InetSocketAddress]
           [java.io File]))

(defn wait-for-ssh!
  "Connect and read the SSH banner to confirm sshd is serving.
  Returns :ok or throws after timeout-ms."
  [port timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [result (try
                     (let [s (Socket.)]
                       (try
                         (.connect s (InetSocketAddress. "127.0.0.1" port) 500)
                         (.setSoTimeout s 1000)
                         (let [buf (byte-array 64)
                               n (.read (.getInputStream s) buf)]
                           (.close s)
                           (if (and (pos? n)
                                    (clojure.string/starts-with? (String. buf 0 n) "SSH-"))
                             :banner
                             :no-banner))
                         (catch Exception _ (.close s) :fail)))
                     (catch Exception _ :fail))]
        (cond
          (= :banner result) :ok
          (> (- (System/currentTimeMillis) start) timeout-ms)
          (throw (ex-info "timed out waiting for SSH" {:port port}))
          :else (do (Thread/sleep 100) (recur)))))))

(defn ssh-exec
  "Run command in the guest over SSH, verifying the injected host key.
  Returns {:stdout :stderr :exit}."
  [port private-key-path host-public-key command]
  (let [known (str (System/getProperty "java.io.tmpdir") "/jolt-known-" (java.util.UUID/randomUUID))]
    (spit known (str "[127.0.0.1]:" port " " host-public-key "\n"))
    (try
      (let [r (shell/sh "ssh" "-i" private-key-path
                        "-p" (str port)
                        "-o" "StrictHostKeyChecking=yes"
                        "-o" (str "UserKnownHostsFile=" known)
                        "-o" "BatchMode=yes"
                        "-o" "ConnectTimeout=10"
                        "-o" "LogLevel=ERROR"
                        "sandbox@127.0.0.1" command)]
        {:stdout (:out r) :stderr (:err r) :exit (:exit r)})
      (finally (.delete (File. known))))))

(defn- pick-port []
  (let [s (java.net.ServerSocket. 0)]
    (try (.getLocalPort s) (finally (.close s)))))

(defn- jolt-bin []
  (or (System/getenv "JOLT_BIN")
      (str (System/getProperty "user.home") "/.local/bin/jolt")))

(declare dyld-fallback-path)

(defn- spawn-runner! [project-dir config]
  (let [pb (ProcessBuilder. [(jolt-bin) "run" "-m" "jolt.microvm.runner" (json/write-str config)])
        _ (.directory pb (File. project-dir))
        env (.environment pb)
        _ (.put env "JOLT_SKIP_MIN_VERSION" "1")
        ;; macOS: libkrun.dylib dlopens libkrunfw by bare leaf name; give the
        ;; subprocess the brew lib dirs so it always resolves.
        _ (when-let [dyld (dyld-fallback-path)]
            (.put env "DYLD_FALLBACK_LIBRARY_PATH" dyld))
        p (.start pb)]
    p))

(defrecord VM [image cache-dir rootfs port private-key-path host-public-key process workspace opts])

(defn start!
  "Boot a microVM from an OCI image and wait for sshd.
  opts: :image (required), :workspace, :cache-dir, :cpus, :memory-mib,
        :ssh-port (0 = ephemeral), :project-dir (dir of deps.edn with the deps).
  Returns a VM record."
  [{:keys [image workspace cache-dir cpus memory-mib ssh-port project-dir] :as opts}]
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        rootfs (rootfs/prepare! image cache-dir)
        ws (or workspace (str rootfs "/workspace"))
        _ (.mkdirs (File. ws))
        ephemeral (keys/generate-ephemeral!)
        host (keys/generate-host!)]
    (rootfs/inject-sandbox-user! rootfs)
    (rootfs/inject-authorized-keys! rootfs (:public-key ephemeral))
    (keys/inject-host! rootfs host)
    (rootfs/write-krun-config! rootfs)
    (let [port (or ssh-port (pick-port))
          cfg {"rootfs_path" rootfs
               "workspace_path" ws
               "ssh_port" port
               "cpus" (or cpus 2)
               "memory_mib" (or memory-mib 512)}
          proc (spawn-runner! project-dir cfg)]
      (try
        (wait-for-ssh! port 30000)
        (catch Exception e
          (.destroyForcibly proc)
          (throw e)))
      (->VM image cache-dir rootfs port (:private-key-path ephemeral) (:public-key host) proc ws opts))))

(defn exec
  "Run a command in the VM. Returns {:stdout :stderr :exit}."
  [vm command & {:keys [cwd] :or {cwd "/workspace"}}]
  (ssh-exec (:port vm) (:private-key-path vm) (:host-public-key vm)
            (str "cd " cwd " && " command)))

(defn stop!
  "Shut down the VM: graceful SSH poweroff, then destroy the runner process."
  [vm]
  (try
    (ssh-exec (:port vm) (:private-key-path vm) (:host-public-key vm) "poweroff -f")
    (catch Exception _ nil))
  (Thread/sleep 1000)
  (when (.isAlive (:process vm))
    (.destroy (:process vm)))
  (Thread/sleep 200)
  (when (.isAlive (:process vm))
    (.destroyForcibly (:process vm)))
  :stopped)

;; -- snapshots ---------------------------------------------------------------
(defn snapshots-dir [vm] (str (:cache-dir vm) "/snapshots"))

(defn- validate-snapshot-name! [name]
  (when-not (and name (re-matches #"[A-Za-z0-9._-]+" name))
    (throw (ex-info "invalid snapshot name" {:name name})))
  name)

(defn save-snapshot!
  "Save a copy of the running VM's rootfs as a named snapshot."
  [vm name]
  (validate-snapshot-name! name)
  (let [snap (str (snapshots-dir vm) "/" name)]
    (when (.exists (java.io.File. snap))
      (throw (ex-info "snapshot already exists" {:name name})))
    (.mkdirs (java.io.File. (snapshots-dir vm)))
    (rootfs/cp-r! (:rootfs vm) snap)
    :saved))

(defn list-snapshots
  "List saved snapshot names (sorted)."
  [vm]
  (let [d (java.io.File. (snapshots-dir vm))]
    (if-not (.isDirectory d)
      []
      (sort (for [f (.listFiles d) :when (.isDirectory f)] (.getName f))))))

(defn restore-snapshot!
  "Restore a snapshot over the cached base; takes effect on the next start!.
  The VM must be stopped."
  [vm name]
  (validate-snapshot-name! name)
  (when (and (:process vm) (.isAlive (:process vm)))
    (throw (ex-info "VM is running — stop it before restoring a snapshot" {})))
  (let [snap (str (snapshots-dir vm) "/" name)
        base (rootfs/cached-base-path (:image vm) (:cache-dir vm))]
    (when-not (.isDirectory (java.io.File. snap))
      (throw (ex-info "snapshot does not exist" {:name name})))
    (rootfs/cleanup! base)
    (.mkdirs (.getParentFile (java.io.File. base)))
    (rootfs/cp-r! snap base)
    :restored))

(defn delete-snapshot!
  "Delete a saved snapshot."
  [vm name]
  (validate-snapshot-name! name)
  (let [snap (str (snapshots-dir vm) "/" name)]
    (when-not (.isDirectory (java.io.File. snap))
      (throw (ex-info "snapshot does not exist" {:name name})))
    (rootfs/cleanup! snap)
    :deleted))

(defn reboot!
  "Stop the VM and start a fresh one; in-VM changes are discarded."
  [vm]
  (stop! vm)
  (start! (:opts vm)))

;; -- macOS helpers (untested on Linux; ported from mod.rs) -------------------
(def ^:private macos?
  (clojure.string/starts-with?
    (clojure.string/lower-case (or (System/getProperty "os.name") ""))
    "mac"))

(defn- brew-prefix
  ([package]
   (let [args (if package ["brew" "--prefix" package] ["brew" "--prefix"])
         r (apply shell/sh args)]
     (when (zero? (:exit r)) (clojure.string/trim (:out r)))))
  ([] (brew-prefix nil)))

(defn dyld-fallback-path
  "DYLD_FALLBACK_LIBRARY_PATH for libkrunfw (macOS only). Resolves brew lib
  dirs (libkrunfw-specific first, then generic), and appends the existing value
  or sane defaults. Returns nil on Linux."
  []
  (when macos?
    (let [prefixes (distinct (remove clojure.string/blank?
                                     [(brew-prefix "libkrunfw") (brew-prefix nil)]))
          dirs (map #(str % "/lib") prefixes)
          existing (System/getenv "DYLD_FALLBACK_LIBRARY_PATH")]
      (str (clojure.string/join ":" dirs)
           (when (and existing (not (clojure.string/blank? existing)))
             (str ":" existing))
           ":/usr/local/lib:/usr/lib"))))

(defn ensure-hypervisor-entitlement!
  "On macOS, codesign the jolt binary with com.apple.security.hypervisor if it
  lacks it (Hypervisor.framework requires the entitlement). No-op on Linux.
  Deliberately does NOT use --options runtime (hardened runtime would strip
  DYLD_FALLBACK_LIBRARY_PATH and break the libkrunfw lookup)."
  []
  (when macos?
    (let [bin (jolt-bin)]
      (let [check (shell/sh "codesign" "-d" "--entitlements" "-" bin)]
        (if (clojure.string/includes? (str (:out check) (:err check))
                                     "com.apple.security.hypervisor")
          :already-signed
          (let [tmp (str (System/getProperty "java.io.tmpdir") "/jolt-runner-entitlements.plist")]
            (spit tmp
                  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<dict>\n  <key>com.apple.security.hypervisor</key>\n  <true/>\n</dict>\n</plist>\n")
            (let [r (shell/sh "codesign" "--force" "--sign" "-" "--entitlements" tmp bin)]
              (.delete (java.io.File. tmp))
              (when-not (zero? (:exit r))
                (throw (ex-info "codesign failed" {:stderr (:err r)}))))
            :signed))))))
