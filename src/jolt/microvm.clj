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
        scp (which "scp")
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
         {:name :scp :status (if scp :ok :warn)
          :message (or scp "scp not found (copy-to!/copy-from! unavailable)")
          :fix (when-not scp "install openssh-client")}
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

(defn- ssh-control-path [port]
  (str (System/getProperty "java.io.tmpdir") "/jolt-ssh-ctl-" port))

(defn- ssh-mux-args [port]
  ["-o" "ControlMaster=auto"
   "-o" (str "ControlPath=" (ssh-control-path port))
   "-o" "ControlPersist=120"])

(defn ssh-exec
  "Run command in the guest over SSH, verifying the injected host key.
  Reuses a persistent SSH connection (ControlMaster) keyed by port so repeated
  execs skip the handshake. Returns {:stdout :stderr :exit}.
  opts: :timeout-ms (default 300000)."
  [port private-key-path host-public-key command & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [known (known-hosts-path! port host-public-key)]
    (try
      (let [argv (into-array String (concat ["ssh" "-i" private-key-path
                                             "-p" (str port)
                                             "-o" "StrictHostKeyChecking=yes"
                                             "-o" (str "UserKnownHostsFile=" known)
                                             "-o" "BatchMode=yes"
                                             "-o" "ConnectTimeout=10"
                                             "-o" "LogLevel=ERROR"]
                                            (ssh-mux-args port)
                                            ["sandbox@127.0.0.1" command]))
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

(defrecord VM [image cache-dir rootfs port private-key-path host-public-key process workspace opts mounts])

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

(defn- ensure-base!
  "Make the cached base contain openssh (sshd) and any requested apk packages,
  seeding with the image's own apk + musl loader when alpine-family. Idempotent
  and safe under concurrent start!. Returns the base path."
  [image cache-dir packages arch]
  (let [base (rootfs/base! image cache-dir arch)
        wanted (vec (concat ["openssh"] (map str packages)))
        sshd? (fn [] (.exists (java.io.File. (str base "/usr/sbin/sshd"))))
        missing (fn [] (remove (fn [pkg]
                                 (or (rootfs/alpine-installed? base pkg)
                                     (and (= pkg "openssh") (sshd?))))
                               wanted))]
    (when (and (seq (missing)) (not (rootfs/musl-loader base)))
      (throw (ex-info
              (str "image has no apk (not alpine-family) and is missing: "
                   (str/join ", " (missing)) ". Use an alpine image or pre-seed the base.")
              {:image image :missing (missing)})))
    (when (seq (missing))
      (with-base-lock!
        (str (rootfs/image-dir image cache-dir arch) "/.seed.lock")
        (fn []
          (let [still (remove #(rootfs/alpine-installed? base %) (missing))]
            (when (seq still)
              (apply rootfs/install-alpine-packages! base still))))))
    base))

;; -- lifecycle ----------------------------------------------------------------
(defn- normalize-mounts
  "Normalize :mounts to [{:tag :host :guest} ...], always including the
  workspace mount first (tag workspace, guest /workspace)."
  [workspace mounts]
  (let [user (cond
               (nil? mounts) []
               (map? mounts)
               (if (and (:host mounts) (:guest mounts))
                 [mounts]
                 (mapv (fn [[h g]] {:host h :guest g}) mounts))
               (sequential? mounts)
               (mapv (fn [m]
                       (cond
                         (map? m) m
                         (sequential? m) {:host (first m) :guest (second m)}
                         :else (throw (ex-info "bad mount entry" {:mount m}))))
                     mounts)
               :else (throw (ex-info ":mounts must be a map or vector" {:mounts mounts})))
        tagged (mapv (fn [i m]
                       (let [h (:host m) g (:guest m)]
                         (when-not (and h g)
                           (throw (ex-info "mount needs :host and :guest" {:mount m})))
                         {:tag (or (:tag m) (str "mnt" i)) :host h :guest g}))
                     (range) user)]
    (into [{:tag "workspace" :host workspace :guest "/workspace"}] tagged)))

(defn start!
  "Boot a microVM from an OCI image and wait for sshd. Auto-seeds openssh (and
  any :packages) into the cached alpine base on first use. Runs check! first.

  opts:
    :image       required, e.g. \"alpine:3.20\" (multi-arch resolves host arch)
    :arch        guest arch override (e.g. \"arm64\")
    :packages    apk packages to pre-install into the cached base [\"python3\"]
    :workspace   host dir mounted at /workspace (default rootfs/workspace)
    :mounts      extra volume mounts:
                   { \"/host/dir\" \"/guest/dir\" }   (host->guest map)
                   [{:host :guest :tag}]              (vector of specs)
                   [[\"/h\" \"/g\"] ...]              (vector of pairs)
    :cache-dir   (default ~/.cache/jolt-microvm)
    :cpus :memory-mib :ssh-port (0 = ephemeral)
    :project-dir dir whose deps.edn provides jolt-microvm + deps
  Returns a VM record."
  [{:keys [image workspace cache-dir cpus memory-mib ssh-port project-dir
           packages mounts arch] :as opts}]
  (check-or-throw!)
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        _ (ensure-base! image cache-dir packages arch)
        rootfs (rootfs/prepare! image cache-dir arch)
        ws (or workspace (str rootfs "/workspace"))
        _ (.mkdirs (File. ws))
        mounts (normalize-mounts ws mounts)
        ephemeral (keys/generate-ephemeral!)
        host (keys/generate-host!)]
    (rootfs/inject-sandbox-user! rootfs)
    (rootfs/inject-authorized-keys! rootfs (:public-key ephemeral))
    (keys/inject-host! rootfs host)
    (rootfs/write-krun-config! rootfs :mounts mounts)
    (let [port (or ssh-port (pick-port))
          cfg {"rootfs_path" rootfs
               "ssh_port" port
               "cpus" (or cpus 2)
               "memory_mib" (or memory-mib 512)
               "mounts" (mapv (fn [m] {"tag" (:tag m) "host" (:host m) "guest" (:guest m)}) mounts)}
          proc (spawn-runner! project-dir cfg)]
      (try
        (wait-for-ssh! port 30000)
        (catch Exception e
          (.destroyForcibly proc)
          (throw e)))
      (->VM image cache-dir rootfs port (:private-key-path ephemeral) (:public-key host) proc ws opts mounts))))

(defn exec
  "Run a command in the VM. Returns {:stdout :stderr :exit}.
  opts: :cwd (default /workspace), :timeout-ms (default 300000)."
  [vm command & {:keys [cwd timeout-ms] :or {cwd "/workspace" timeout-ms 300000}}]
  (ssh-exec (:port vm) (:private-key-path vm) (:host-public-key vm)
            (str "cd " cwd " && " command)
            :timeout-ms timeout-ms))

(defn exec!
  "Like exec but throws ex-info when the command exits non-zero (ex-data carries
  :stdout/:stderr/:exit). Returns the result on success."
  [vm command & {:keys [cwd timeout-ms] :or {cwd "/workspace" timeout-ms 300000}}]
  (let [r (exec vm command :cwd cwd :timeout-ms timeout-ms)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "command failed (" (:exit r) "): " command) r)))
    r))

(defn alive?
  "True while the VM's runner process is alive."
  [vm]
  (boolean (and (:process vm) (.isAlive (:process vm)))))

(defn info
  "Describe a VM: {:image :port :rootfs :workspace :mounts :running?}."
  [vm]
  {:image (:image vm)
   :port (:port vm)
   :rootfs (:rootfs vm)
   :workspace (:workspace vm)
   :mounts (:mounts vm)
   :running? (alive? vm)})

(defn- scp
  "Run scp against the VM with the host key pinned. Returns {:stdout :stderr :exit}."
  [vm & args]
  (let [known (known-hosts-path! (:port vm) (:host-public-key vm))]
    (try
      (let [argv (into-array String (concat ["scp" "-i" (:private-key-path vm)
                                             "-P" (str (:port vm))
                                             "-o" "StrictHostKeyChecking=yes"
                                             "-o" (str "UserKnownHostsFile=" known)
                                             "-o" "BatchMode=yes"
                                             "-o" "LogLevel=ERROR"]
                                            (ssh-mux-args (:port vm))
                                            args))
            proc (.start (ProcessBuilder. argv))
            out (future (drain! (.getInputStream proc)))
            err (future (drain! (.getErrorStream proc)))
            code (.waitFor proc)]
        {:stdout @out :stderr @err :exit code})
      (finally (.delete (java.io.File. known))))))

(defn copy-to!
  "Copy a host file or dir into the guest (recursive). Returns the scp result."
  [vm host-path guest-path]
  (scp vm "-r" host-path (str "sandbox@127.0.0.1:" guest-path)))

(defn copy-from!
  "Copy a guest file or dir out of the VM (recursive). Returns the scp result."
  [vm guest-path host-path]
  (scp vm "-r" (str "sandbox@127.0.0.1:" guest-path) host-path))

(defn install-image!
  "Install apk packages into the cached base for image; takes effect on the
  next start!. Returns :installed."
  [image packages & {:keys [cache-dir arch] :or {cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm") arch nil}}]
  (ensure-base! image cache-dir (vec packages) arch)
  :installed)

(defn install!
  "Install apk packages into the VM's cached base image; takes effect on the
  next start! (the running VM is a clone and won't see them until reboot!)."
  [vm & packages]
  (install-image! (:image vm) (vec packages)
                  :cache-dir (:cache-dir vm)
                  :arch (get-in vm [:opts :arch])))

(defn run-image!
  "One-shot: boot an image directly into a command (no SSH, no shell) and return
  {:stdout :stderr :exit}. Works for distroless and any other image whose rootfs
  contains the executable; no apk/sshd seeding happens. The guest command's
  exit code is the returned :exit.

  opts: :image (required), :cmd [exe arg...] (required), :arch, :cpus,
        :memory-mib, :cache-dir, :project-dir, :timeout-ms, :packages (apk
        packages to seed into an alpine base before boot)."
  [{:keys [image cmd arch cpus memory-mib cache-dir project-dir timeout-ms packages]}]
  (check-or-throw!)
  (when-not (seq cmd)
    (throw (ex-info "run-image! needs :cmd [exe arg...]" {:image image})))
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        _ (when (seq packages) (ensure-base! image cache-dir packages arch))
        rootfs (rootfs/prepare! image cache-dir arch)
        cfg {"mode" "exec"
             "rootfs_path" rootfs
             "cpus" (or cpus 1)
             "memory_mib" (or memory-mib 512)
             "exec" (vec cmd)}
        out-file (str (System/getProperty "java.io.tmpdir") "/jolt-vm-out-" (java.util.UUID/randomUUID))
        err-file (str (System/getProperty "java.io.tmpdir") "/jolt-vm-err-" (java.util.UUID/randomUUID))
        pb (ProcessBuilder. [(jolt-bin) "run" "-m" "jolt.microvm.runner" (json/write-str cfg)])
        _ (.directory pb (File. project-dir))
        env (.environment pb)
        _ (.put env "JOLT_SKIP_MIN_VERSION" "1")
        _ (when-let [dyld (dyld-fallback-path)]
            (.put env "DYLD_FALLBACK_LIBRARY_PATH" dyld))
        _ (.redirectOutput pb (File. out-file))
        _ (.redirectError pb (File. err-file))
        proc (.start pb)]
    (if (.waitFor proc (or timeout-ms 300000) java.util.concurrent.TimeUnit/MILLISECONDS)
      (let [exit (.exitValue proc)
            out (if (.exists (File. out-file)) (slurp out-file) "")
            err (if (.exists (File. err-file)) (slurp err-file) "")]
        (.delete (File. out-file))
        (.delete (File. err-file))
        {:stdout out :stderr err :exit exit})
      (do
        (.destroyForcibly proc)
        (.delete (File. out-file))
        (.delete (File. err-file))
        (throw (ex-info "VM command timed out" {:cmd cmd :timeout-ms timeout-ms}))))))

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
  ;; tear down the persistent SSH control socket for this port (best-effort)
  (try
    (shell/sh "ssh" "-O" "exit"
              "-o" (str "ControlPath=" (ssh-control-path (:port vm)))
              "sandbox@127.0.0.1")
    (catch Exception _ nil))
  :stopped)

(defmacro with-vm
  "Boot a VM from opts, bind it to sym, run body, and stop! it in a finally."
  [[sym opts] & body]
  `(let [~sym (start! ~opts)]
     (try
       ~@body
       (finally (stop! ~sym)))))

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
        base (rootfs/cached-base-path (:image vm) (:cache-dir vm) (get-in vm [:opts :arch]))]
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
