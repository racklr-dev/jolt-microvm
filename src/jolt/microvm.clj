;; jolt.microvm — run commands inside an OCI-image-backed libkrun microVM
;; over SSH (port of dirge's sandbox/microvm: runner.rs, rootfs.rs, ssh.rs).
;;
;; Plug-and-play: start! auto-seeds sshd into the cached alpine base (file-locked),
;; runs a pre-flight dependency check, and throws a readable error listing
;; what is missing. exec has a hard wall-clock timeout so a hung guest command
;; cannot wedge the host library.
(ns jolt.microvm
  (:require [jolt.microvm.keys :as keys]
            [jolt.microvm.rootfs :as rootfs]
            [jolt.libkrun.probe :as probe]
            [jolt.pty :as pty]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [java.net Socket InetSocketAddress]
           [java.io File ByteArrayOutputStream]))

;; -- pre-flight --------------------------------------------------------------
(defn- which
  "Absolute path of a command on PATH, or nil."
  [cmd]
  (let [r (shell/sh "sh" "-c" (str "command -v " cmd " 2>/dev/null || true"))]
    (let [p (str/trim (:out r))]
      (when (seq p) p))))

(defn check!
  "Pre-flight dependency check for jolt-microvm. Returns
  {:ok? boolean :results [{:name :status :message :fix}]} with
  :status in :ok/:warn/:error. Call it directly for a report, or let start!
  do it and throw on :error."
  []
  (let [lk (probe/find-libkrun)
        lkfw (probe/find-libkrunfw)
        kvm-present (probe/kvm-present?)
        kvm-writable (probe/kvm-available?)
        ssh (which "ssh")
        keygen (which "ssh-keygen")
        sudo (which "sudo")
        jolt (or (System/getenv "JOLT_BIN")
                 (str (System/getProperty "user.home") "/.local/bin/jolt"))
        results
        [{:name :libkrun :status (if lk :ok :error)
          :message (or lk "libkrun shared library not found")
          :fix (when-not lk "install libkrun (e.g. brew install libkrun, or dnf/apt install libkrun)")}
         {:name :libkrunfw :status (if lkfw :ok :error)
          :message (or lkfw "libkrunfw shared library not found")
          :fix (when-not lkfw "install libkrunfw (same package as libkrun on most distros)")}
         {:name :kvm
          :status (cond (not kvm-present) :error kvm-writable :ok :else :error)
          :message (if (not kvm-present)
                     "/dev/kvm not present"
                     (if kvm-writable "/dev/kvm read+write ok" "/dev/kvm exists but is not writable"))
          :fix (if (not kvm-present)
                 "enable KVM in BIOS/host and load the kvm module"
                 "add your user to the kvm group: sudo usermod -aG kvm $USER, then log out and back in")}
         {:name :ssh :status (if ssh :ok :error)
          :message (or ssh "openssh client not found")
          :fix (when-not ssh "install openssh-client")}
         {:name :ssh-keygen :status (if keygen :ok :error)
          :message (or keygen "ssh-keygen not found")
          :fix (when-not keygen "install openssh-client")}
         {:name :sudo :status (if sudo :ok :warn)
          :message (or sudo "sudo not found (needed only to auto-seed alpine sshd)")
          :fix (when-not sudo "install sudo, or pre-seed sshd into the cached base yourself")}
         {:name :jolt-bin :status (if (.exists (java.io.File. jolt)) :ok :error)
          :message (if (.exists (java.io.File. jolt)) jolt (str "jolt binary not at " jolt))
          :fix (if-not (.exists (java.io.File. jolt))
                 "set JOLT_BIN to the jolt binary (the runner is spawned as a jolt subprocess)" nil)}]
        ok? (every? #(not= :error (:status %)) results)]
    {:ok? ok? :results results}))

(defn- check-or-throw!
  []
  (let [{:keys [ok? results]} (check!)]
    (when-not ok?
      (let [errors (filter #(= :error (:status %)) results)]
        (throw (ex-info
                (str "jolt-microvm pre-flight failed: "
                     (str/join " | "
                               (map #(str (name (:name %)) " — " (:message %)
                                          (when (:fix %) (str " [fix: " (:fix %) "]")))
                                    errors)))
                {:check-results results}))))))

;; -- ssh ---------------------------------------------------------------------
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
                                    (str/starts-with? (String. buf 0 n) "SSH-"))
                             :banner
                             :no-banner))
                         (catch Exception _ (.close s) :fail)))
                     (catch Exception _ :fail))]
        (cond
          (= :banner result) :ok
          (> (- (System/currentTimeMillis) start) timeout-ms)
          (throw (ex-info "timed out waiting for SSH" {:port port}))
          :else (do (Thread/sleep 100) (recur)))))))

(defn- drain!
  "Read an InputStream to EOF and return its bytes as a UTF-8 string."
  [in]
  (let [baos (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n)
          (.write baos buf 0 n)
          (recur))))
    (String. (.toByteArray baos) "UTF-8")))

(defn- known-hosts-path!
  "Write a one-off known_hosts pinning the guest host key to 127.0.0.1:port.
  Returns the file path; caller deletes it when done."
  [port host-public-key]
  (let [known (str (System/getProperty "java.io.tmpdir") "/jolt-known-" (java.util.UUID/randomUUID))]
    (spit known (str "[127.0.0.1]:" port " " host-public-key "\n"))
    known))

(defn ssh-exec
  "Run command in the guest over SSH, verifying the injected host key.
  Returns {:stdout :stderr :exit}. opts: :timeout-ms (default 300000)."
  [port private-key-path host-public-key command & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [known (known-hosts-path! port host-public-key)]
    (try
      (let [argv (into-array String ["ssh" "-i" private-key-path
                                     "-p" (str port)
                                     "-o" "StrictHostKeyChecking=yes"
                                     "-o" (str "UserKnownHostsFile=" known)
                                     "-o" "BatchMode=yes"
                                     "-o" "ConnectTimeout=10"
                                     "-o" "LogLevel=ERROR"
                                     "sandbox@127.0.0.1" command])
            proc (.start (ProcessBuilder. argv))
            out (future (drain! (.getInputStream proc)))
            err (future (drain! (.getErrorStream proc)))]
        (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          {:stdout @out :stderr @err :exit (.exitValue proc)}
          (do (.destroyForcibly proc)
              (future-cancel out)
              (future-cancel err)
              (throw (ex-info "ssh command timed out" {:command command :timeout-ms timeout-ms})))))
      (finally (.delete (java.io.File. known))))))

;; -- runner ------------------------------------------------------------------
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
        _ (when-let [dyld (dyld-fallback-path)]
            (.put env "DYLD_FALLBACK_LIBRARY_PATH" dyld))
        p (.start pb)]
    p))

(defrecord VM [image cache-dir rootfs port private-key-path host-public-key process workspace opts])

;; -- auto-seed ----------------------------------------------------------------
(defn- with-base-lock! [path f]
  "Run f while holding an atomic mkdir lock at path. Blocks if another process
  holds it; breaks stale locks (from a crashed seeder) after 10 minutes."
  (let [lock-dir (java.io.File. path)]
    (.mkdirs (.getParentFile lock-dir))
    (let [stale-ms 600000]
      (loop []
        (let [created? (try
                         (java.nio.file.Files/createDirectory (.toPath lock-dir))
                         true
                         (catch Exception _ false))]
          (cond
            created?
            (try (f)
                 (finally (java.nio.file.Files/deleteIfExists (.toPath lock-dir))))

            (> (- (System/currentTimeMillis) (.lastModified lock-dir)) stale-ms)
            (do (java.nio.file.Files/deleteIfExists (.toPath lock-dir))
                (recur))

            :else
            (do (Thread/sleep 100) (recur))))))))

(defn- ensure-sshd!
  "Make the cached base for image contain sshd, seeding it with apk if it is an
  alpine-family image. Idempotent and safe under concurrent start!. Returns the
  base path."
  [image cache-dir]
  (let [base (rootfs/base! image cache-dir)
        sshd (str base "/usr/sbin/sshd")]
    (when-not (.exists (java.io.File. sshd))
      (with-base-lock!
        (str cache-dir "/" (rootfs/image-safe image) "/.seed.lock")
        (fn []
          (when-not (.exists (java.io.File. sshd))
            (if (rootfs/musl-loader base)
              (rootfs/install-alpine-packages! base "openssh")
              (throw (ex-info
                      (str "image has no sshd and is not alpine-family (no musl loader to seed with). "
                           "Use an alpine image, or an image with sshd already installed, or pre-seed the base.")
                      {:image image :base base})))))))
    base))

;; -- lifecycle ----------------------------------------------------------------
(defn start!
  "Boot a microVM from an OCI image and wait for sshd. Auto-seeds sshd into the
  cached alpine base on first use. Runs check! first and throws a readable
  error if any hard dependency is missing.

  opts: :image (required), :workspace, :cache-dir, :cpus, :memory-mib,
        :ssh-port (0 = ephemeral), :project-dir (dir whose deps.edn provides
        jolt-microvm + its deps). Returns a VM record."
  [{:keys [image workspace cache-dir cpus memory-mib ssh-port project-dir] :as opts}]
  (check-or-throw!)
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        _ (ensure-sshd! image cache-dir)
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
  "Run a command in the VM. Returns {:stdout :stderr :exit}.
  opts: :cwd (default /workspace), :timeout-ms (default 300000)."
  [vm command & {:keys [cwd timeout-ms] :or {cwd "/workspace" timeout-ms 300000}}]
  (ssh-exec (:port vm) (:private-key-path vm) (:host-public-key vm)
            (str "cd " cwd " && " command)
            :timeout-ms timeout-ms))

(defn attach!
  "Attach an interactive SSH session to the running VM over an isolated pty
  (console relay). Blocks until the session exits; returns its exit status.
  The guest host key is pinned, and the host terminal size is applied to the
  pty."
  [vm]
  (let [known (known-hosts-path! (:port vm) (:host-public-key vm))
        p (pty/open-pty!)]
    (try
      ;; Raw secondary: no local echo/canonical, so ssh requests ECHO=0 remotely
      ;; and the host terminal's own echo is the single echo.
      (pty/make-raw! (:secondary-fd p))
      (let [child (pty/spawn-secondary!
                   p ["ssh" "-i" (:private-key-path vm)
                      "-p" (str (:port vm))
                      "-o" "StrictHostKeyChecking=yes"
                      "-o" (str "UserKnownHostsFile=" known)
                      "-o" "BatchMode=yes"
                      "-o" "LogLevel=ERROR"
                      "-t"
                      "sandbox@127.0.0.1"])]
        (pty/relay! p child))
      (finally
        (pty/close-pty! p)
        (.delete (java.io.File. known))))))

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
  (str/starts-with?
    (str/lower-case (or (System/getProperty "os.name") ""))
    "mac"))

(defn- brew-prefix
  ([package]
   (let [args (if package ["brew" "--prefix" package] ["brew" "--prefix"])
         r (apply shell/sh args)]
     (when (zero? (:exit r)) (str/trim (:out r)))))
  ([] (brew-prefix nil)))

(defn dyld-fallback-path
  "DYLD_FALLBACK_LIBRARY_PATH for libkrunfw (macOS only). Resolves brew lib
  dirs (libkrunfw-specific first, then generic), and appends the existing value
  or sane defaults. Returns nil on Linux."
  []
  (when macos?
    (let [prefixes (distinct (remove str/blank?
                                     [(brew-prefix "libkrunfw") (brew-prefix)]))
          dirs (map #(str % "/lib") prefixes)
          existing (System/getenv "DYLD_FALLBACK_LIBRARY_PATH")]
      (str (str/join ":" dirs)
           (when (and existing (not (str/blank? existing)))
             (str ":" existing))
           ":/usr/local/lib:/usr/lib"))))

(defn ensure-hypervisor-entitlement!
  "On macOS, codesign the jolt binary with com.apple.security.hypervisor if it
  lacks it (Hypervisor.framework requires the entitlement). No-op on Linux."
  []
  (when macos?
    (let [bin (jolt-bin)]
      (let [check (shell/sh "codesign" "-d" "--entitlements" "-" bin)]
        (if (str/includes? (str (:out check) (:err check))
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
