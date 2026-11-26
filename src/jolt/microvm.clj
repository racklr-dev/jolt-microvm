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
            [jolt.exec.client :as exec-client]
            [jolt.exec.bundle :as exec-bundle]
            [jolt.microvm.deps :as guest-deps]
            [jolt.microvm.nix :as nix]
            [jolt.tapmasq :as net]
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
        os (probe/host-os)
        macos? (= os :macos)
        hv-ok (probe/hypervisor-available?)
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
          :fix (when-not lk
                 (if macos?
                   "brew tap libkrun/krun && brew trust libkrun/krun && brew install libkrun libkrunfw"
                   "install libkrun (dnf/apt) or build from source"))}
         {:name :libkrunfw :status (if lkfw :ok :error)
          :message (or lkfw "libkrunfw shared library not found")
          :fix (when-not lkfw
                 (if macos?
                   "brew tap libkrun/krun && brew trust libkrun/krun && brew install libkrun libkrunfw"
                   "install libkrunfw (same package as libkrun on most distros)"))}
         {:name (if macos? :hypervisor :kvm)
          :status (cond
                    macos? (if hv-ok :ok :error)
                    (not kvm-present) :error
                    kvm-writable :ok
                    :else :error)
          :message (cond
                     macos? (if hv-ok
                              "Hypervisor.framework available"
                              "Hypervisor.framework not available (kern.hv_support=0)")
                     (not kvm-present) "/dev/kvm not present"
                     kvm-writable "/dev/kvm read+write ok"
                     :else "/dev/kvm exists but is not writable")
          :fix (cond
                 macos? (when-not hv-ok "Hypervisor.framework requires Apple Silicon hardware")
                 (not kvm-present) "enable KVM in BIOS/host and load the kvm module"
                 :else "add your user to the kvm group: sudo usermod -aG kvm $USER, then log out and back in")}
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
  "Write a one-off known_hosts pinning the guest host key to host:22."
  [host host-public-key]
  (let [known (str (System/getProperty "java.io.tmpdir") "/jolt-known-" (java.util.UUID/randomUUID))]
    (spit known (str host " " host-public-key "\n"))
    known))

(defn- ssh-control-path [host]
  (str (System/getProperty "java.io.tmpdir") "/jolt-ssh-ctl-" host))

(defn- ssh-mux-args [host]
  ["-o" "ControlMaster=auto"
   "-o" (str "ControlPath=" (ssh-control-path host))
   "-o" "ControlPersist=120"])

(defn ssh-exec
  "Run command in the guest over SSH, verifying the injected host key. host is
  the guest's IP. Reuses a persistent SSH connection (ControlMaster) keyed by
  host. Returns {:stdout :stderr :exit}. opts: :timeout-ms (default 300000),
  :interrupt (an atom polled while the command runs; when truthy the ssh
  process is killed and the result carries :interrupted? true),
  :interrupt-poll-ms (default 50)."
  [host private-key-path host-public-key command & {:keys [timeout-ms interrupt interrupt-poll-ms]
                                                    :or {timeout-ms 300000 interrupt-poll-ms 50}}]
  (let [known (known-hosts-path! host host-public-key)]
    (try
      (let [argv (into-array String (concat ["ssh" "-i" private-key-path
                                             "-o" "StrictHostKeyChecking=yes"
                                             "-o" (str "UserKnownHostsFile=" known)
                                             "-o" "BatchMode=yes"
                                             "-o" "ConnectTimeout=10"
                                             "-o" "LogLevel=ERROR"]
                                            (ssh-mux-args host)
                                            [(str "sandbox@" host) command]))
            proc (.start (ProcessBuilder. argv))
            out (future (drain! (.getInputStream proc)))
            err (future (drain! (.getErrorStream proc)))]
        (loop [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (if (.waitFor proc interrupt-poll-ms java.util.concurrent.TimeUnit/MILLISECONDS)
            (let [r {:stdout @out :stderr @err :exit (.exitValue proc)}]
              ;; ssh exit 255 = connection failure. Drop the persistent
              ;; control socket so the next exec does not reuse a dead mux.
              (when (= 255 (:exit r))
                (.delete (java.io.File. (ssh-control-path host))))
              r)
            (cond
              (and interrupt @interrupt)
              (do (.destroyForcibly proc)
                  {:stdout (deref out 1000 "") :stderr (deref err 1000 "")
                   :exit nil :interrupted? true})

              (> (System/currentTimeMillis) deadline)
              (do (.destroyForcibly proc)
                  (future-cancel out)
                  (future-cancel err)
                  (throw (ex-info "ssh command timed out" {:command command :timeout-ms timeout-ms})))

              :else (recur deadline)))))
      (finally (.delete (java.io.File. known))))))

;; -- runner ------------------------------------------------------------------
(defn- pick-port []
  (let [s (java.net.ServerSocket. 0)]
    (try (.getLocalPort s) (finally (.close s)))))

(defn- jolt-bin []
  (or (System/getenv "JOLT_BIN")
      (str (System/getProperty "user.home") "/.local/bin/jolt")))

(declare dyld-fallback-path)
(declare wait-for-tcp!)

(defn- prlimit-nofile-argv
  "Raise the runner process's host-side file descriptor soft limit on Linux.
  libkrun's virtio-fs opens host files on behalf of the guest, and a desktop's
  shared-library graph can exceed the common 1024 soft limit."
  []
  (when (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "linux")
    (when-let [p (which "prlimit")]
      [p "--nofile=65536:65536" "--"])))

(defn- spawn-runner! [project-dir config]
  (let [pb (ProcessBuilder. (vec (concat (prlimit-nofile-argv)
                                          [(jolt-bin) "run" "-m" "jolt.microvm.runner" (json/write-str config)])))
        _ (.directory pb (File. project-dir))
        env (.environment pb)
        _ (.put env "JOLT_SKIP_MIN_VERSION" "1")
        _ (when-let [dir (or (System/getenv "JOLT_LIBKRUN_DIR") "/tmp/libkrun/target/release")]
            (.put env "LD_LIBRARY_PATH"
                  (str dir (when-let [e (.get env "LD_LIBRARY_PATH")] (str ":" e)))))
        _ (when-let [dyld (dyld-fallback-path)]
            (.put env "DYLD_FALLBACK_LIBRARY_PATH" dyld))
        p (.start pb)]
    p))

(defrecord VM [image cache-dir rootfs ip private-key-path host-public-key process workspace opts mounts tap mac lease-file pid-file table subnet egress-iface nix])

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
(defn- mount-readonly?
  "Coerce a mount entry's mode to a boolean read-only flag. The entry may carry
  :readonly? (boolean), :ro (boolean), or :mode (\"ro\"/\"rw\" or :ro/:rw);
  default false (read-write)."
  [m]
  (let [v (or (:readonly? m) (:ro m) (:mode m))]
    (cond
      (nil? v) false
      (boolean? v) v
      (contains? #{"ro" :ro} v) true
      (contains? #{"rw" :rw} v) false
      :else (throw (ex-info "mount mode must be :ro or :rw" {:mount m :mode v})))))

(def ^:private protected-guest-paths
  "Guest paths the warm VM's boot/exec depends on; a mount must not equal or
  ancestor them (mounting /home/sandbox would hide .ssh/authorized_keys and
  make every ssh exec fail)."
  ["/workspace" "/home/sandbox/.ssh" "/run" "/var/empty"])

(defn- path-ancestor?
  "True when prefix is an ancestor path of p (or equal)."
  [prefix p]
  (or (= (str prefix) (str p))
      (let [prefix (if (str/ends-with? prefix "/") prefix (str prefix "/"))]
        (str/starts-with? p prefix))))

(defn- protected-mount?
  "True when mounting at guest path g would cover a protected path."
  [g]
  (let [g (str g)]
    (boolean (some #(path-ancestor? g %) protected-guest-paths))))

(defn- normalize-mounts
  "Normalize :mounts to [{:tag :host :guest :readonly?} ...], always including
  the workspace mount first (tag workspace, read-write). The workspace mounts at
  workspace-guest (default /workspace), so a host cwd can be mounted at its own
  absolute path and pwd in the guest shows the host path."
  [workspace mounts workspace-guest & [extra]]
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
                         (sequential? m)
                         (let [[h g mode] m]
                           (cond-> {:host h :guest g}
                             mode (assoc :mode mode)))
                         :else (throw (ex-info "bad mount entry" {:mount m}))))
                     mounts)
               :else (throw (ex-info ":mounts must be a map or vector" {:mounts mounts})))
        tagged (mapv (fn [i m]
                       (let [h (:host m) g (:guest m) ro (mount-readonly? m)]
                         (when-not (and h g)
                           (throw (ex-info "mount needs :host and :guest" {:mount m})))
                         (when (protected-mount? g)
                           (throw (ex-info (str "mount guest path " (pr-str g)
                                                " would clobber a protected sandbox path"
                                                " (sshd home/.ssh, /workspace, /run, /var/empty)")
                                            {:mount m})))
                         (cond-> {:tag (or (:tag m) (str "mnt" i)) :host h :guest g}
                           ro (assoc :readonly? true))))
                     (range) user)]
    (into [{:tag "workspace" :host workspace :guest (or workspace-guest "/workspace")}]
          (concat (or extra []) tagged))))

;; -- networking helpers ------------------------------------------------------
(def ^:private net-lock (Object.))

(defn- next-net-seq!
  "The next host-wide VM network sequence: tap jolt<N>, subnet 10.44.<N>.0/24,
  dnsmasq-<N>. Backed by a locked counter file in the host tmp dir so separate
  jolt processes multiplex instead of colliding on jolt1 / 10.44.1.x."
  []
  (let [f (java.io.File. (str (System/getProperty "java.io.tmpdir") "/jolt-microvm-net-seq"))
        lock-dir (java.io.File. (str f ".lock"))]
    (.mkdirs (.getParentFile lock-dir))
    (loop []
      (let [created? (try (java.nio.file.Files/createDirectory (.toPath lock-dir))
                          true
                          (catch Exception _ false))]
        (cond
          created?
          (try
            (let [cur (try (Long/parseLong (str/trim (slurp (str f))))
                           (catch Exception _ 0))
                  next (inc cur)]
              (spit (str f) (str next))
              next)
            (finally (java.nio.file.Files/deleteIfExists (.toPath lock-dir))))

          :else
          (do (Thread/sleep 50) (recur)))))))

(defn- mac-bytes [seq]
  (byte-array [2 0 0 0 0 (mod seq 255)]))

(defn- mac-str [seq]
  (format "02:00:00:00:00:%02x" (mod seq 255)))

(defn- egress-iface
  "The host interface that reaches the internet (e.g. enp1s0)."
  []
  (-> (shell/sh "sh" "-c"
                "ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'")
      :out str/trim))

(defn- net-up! [cache-dir & {:keys [egress]}]
  "Create one VM's own TAP + dnsmasq on its own /24 subnet (10.44.<n>.1/24),
  so concurrent start! calls multiplex without fighting over a shared device.
  When :egress carries :allow-domains, also wire DNS domain-gating (dnsmasq),
  default-deny nftables (:allow-ports, default [80 443]) and NAT masquerade.
  Returns {:tap :mac-str :mac-bytes :lease-file :pid-file} plus teardown keys."
  (locking net-lock
    (let [seq (next-net-seq!)
          tap (str "jolt" seq)
          host-ip (format "10.44.%d.1" seq)
          host-cidr (str host-ip "/24")
          subnet (format "10.44.%d.0/24" seq)
          config-file (str cache-dir "/dnsmasq-" seq ".conf")
          pid-file (str cache-dir "/dnsmasq-" seq ".pid")
          lease-file (str cache-dir "/dnsmasq-" seq ".leases")
          log-file (str cache-dir "/dnsmasq-" seq ".log")
          rules-file (str cache-dir "/nft-" seq ".rules")
          allow-domains (not-empty (:allow-domains egress))
          iface (when allow-domains (or (:egress-iface egress) (egress-iface)))]
      (.mkdirs (java.io.File. cache-dir))
      (net/tap-create! tap)
      (net/tap-ip! tap host-cidr)
      (spit config-file (net/dnsmasq-config {:interface tap :subnet-cidr host-cidr
                                             :range-start (format "10.44.%d.10" seq)
                                             :range-end (format "10.44.%d.100" seq)
                                             :dns host-ip
                                             :upstream (or (:upstream egress) "1.1.1.1")
                                             :allow-domains allow-domains}))
      (net/dnsmasq-start! {:config-file config-file :pid-file pid-file
                           :lease-file lease-file :log-file log-file})
      (when allow-domains
        (net/ip-forward! true)
        (net/nftables-apply! {:table (str "jolt" seq) :subnet subnet :dns host-ip
                              :allow-cidrs (:allow-cidrs egress)
                              :allow-ports (or (:allow-ports egress) [80 443])
                              :rules-file rules-file})
        (net/nat-enable! subnet iface))
      (cond-> {:tap tap :mac-str (mac-str seq) :mac-bytes (mac-bytes seq)
               :lease-file lease-file :pid-file pid-file}
        allow-domains (assoc :table (str "jolt" seq) :rules-file rules-file
                             :subnet subnet :egress-iface iface)))))

(defn- net-down! [net]
  "Tear down one VM's TAP + dnsmasq (+ nftables/NAT when egress was wired)."
  (net/dnsmasq-stop! (:pid-file net))
  (net/tap-delete! (:tap net))
  (when (:table net)
    (net/nftables-clear! (:table net))
    (net/nat-disable! (:subnet net) (:egress-iface net)))
  :stopped)

(defn start!
  "Boot a microVM from an OCI image and wait for sshd. Auto-seeds openssh (and
  any :packages) into the cached alpine base on first use. Runs check! first.

  opts:
    :image       required, e.g. \"alpine:3.20\" (multi-arch resolves host arch)
    :arch        guest arch override (e.g. \"arm64\")
    :packages    apk packages to pre-install into the cached base [\"python3\"]
    :nix-flake   flake devShell ref to layer in (relative refs like .#default
                   resolve against the workspace): resolved via nix
                   print-dev-env, the closure is mounted read-only at /nix/store,
                   and every exec is sourced into the devShell env
    :workspace   host dir mounted at /workspace (default rootfs/workspace)
    :workspace-guest guest path to mount :workspace at (default /workspace);
                   set it to the host cwd's absolute path so guest pwd matches
    :mounts      extra volume mounts:
                   { \"/host/dir\" \"/guest/dir\" }   (host->guest map)
                   [{:host :guest :tag}]              (vector of specs)
                   [[\"/h\" \"/g\"] ...]              (vector of pairs)
    :writable-home? (default false) leave the sandbox home writable so mounts
                   can be created under /home/sandbox (e.g. ~/.faraday)
    A mount entry may also carry :readonly? / :ro (boolean) or :mode
    \"ro\"/\"rw\" (default rw); a readonly mount is mounted -o ro in the guest.
    :cache-dir   (default ~/.cache/jolt-microvm)
    :egress      optional managed-egress map:
                   {:allow-domains [github.com ...]   DNS-gated allow-list
                    :allow-ports   [80 443]           tcp egress ports
                    :allow-cidrs   [1.1.1.1/32]       optional ip allow-list
                    :upstream      1.1.1.1            dnsmasq resolver}
                 when :allow-domains is present, dnsmasq resolves only those
                 domains (+subdomains) and sinkholes the rest, nftables default-
                 denies the guest subnet except DNS + those ports, and NAT
                 masquerades the allowed egress out the host's route
    :cpus :memory-mib :ssh-port (0 = ephemeral)
    :project-dir dir whose deps.edn provides jolt-microvm + deps
  Returns a VM record."
  [{:keys [image workspace cache-dir cpus memory-mib project-dir packages mounts arch writable-home? workspace-guest nix-flake egress x11? tcp-forward?] :as opts}]
  (check-or-throw!)
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        declared (when project-dir (guest-deps/guest-deps project-dir))
        _ (ensure-base! image cache-dir (distinct (concat packages (:apk declared))) arch)
        nix-resolved (when nix-flake (nix/resolve! nix-flake cache-dir (or workspace (System/getProperty "user.dir"))))
        nix-mount (when nix-resolved {:tag "nix-store" :host "/nix/store" :guest "/nix/store" :readonly? true})
        rootfs (rootfs/prepare! image cache-dir arch)
        _ (when nix-resolved
            (.mkdirs (File. (str rootfs "/etc/jolt")))
            (spit (str rootfs "/etc/jolt/dev-env.sh") (:env-script nix-resolved)))
        ;; Bake the static devShell env into the login profile too, so a
        ;; `bash -lc` / `sh -l` (login shell) picks it up: /etc/profile sets a
        ;; bare PATH then sources /etc/profile.d/*.sh, and the agent habitually
        ;; wraps commands in a login shell.
        _ (when (and nix-resolved (:path nix-resolved))
            (.mkdirs (File. (str rootfs "/etc/profile.d")))
            (spit (str rootfs "/etc/profile.d/jolt-dev-env.sh")
                  (str "export PATH=" (:path nix-resolved) "${PATH:+:$PATH}\n"
                       (when (:ld-library-path nix-resolved)
                         (str "export LD_LIBRARY_PATH=" (:ld-library-path nix-resolved) "${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\n")))))
        ws (or workspace (str rootfs "/workspace"))
        _ (.mkdirs (File. ws))
        mounts (normalize-mounts ws mounts workspace-guest (when nix-mount [nix-mount]))
        ephemeral (keys/generate-ephemeral!)
        host (keys/generate-host!)
        _ (rootfs/inject-sandbox-user! rootfs)
        _ (rootfs/inject-authorized-keys! rootfs (:public-key ephemeral) :writable-home? (or writable-home? x11?))
        _ (keys/inject-host! rootfs host)
        _ (when (or x11? tcp-forward?)
            (rootfs/inject-sshd-config! rootfs :x11? x11? :tcp-forward? tcp-forward?))
        exec-cmd (rootfs/sshd-exec-command mounts)
        boot-attempt (fn boot-attempt [attempt]
                       (let [net (net-up! cache-dir :egress egress)
                             cfg {"rootfs_path" rootfs
                                  "cpus" (or cpus 2)
                                  "memory_mib" (or memory-mib 512)
                                  "mounts" (mapv (fn [m] {"tag" (:tag m) "host" (:host m)}) mounts)
                                  "net_taps" [{"id" "eth0" "tap" (:tap net) "mac" (mapv int (:mac-bytes net))}]
                                  "exec" exec-cmd
                                  "dhcp" true}
                             proc (spawn-runner! project-dir cfg)]
                         (try
                           (let [ip (net/wait-lease (:lease-file net) (:mac-str net) 60000)
                                 _ (wait-for-tcp! ip 22 30000)]
                             (->VM image cache-dir rootfs ip (:private-key-path ephemeral) (:public-key host)
                                   proc ws opts mounts (:tap net) (:mac-str net) (:lease-file net) (:pid-file net)
                                   (:table net) (:subnet net) (:egress-iface net) nix-resolved))
                           (catch Exception e
                             (.destroyForcibly proc)
                             (net-down! net)
                             (if (and (str/includes? (str (ex-message e)) "DHCP lease")
                                      (< attempt 3))
                               (boot-attempt (inc attempt))
                               (throw e))))))]
    (boot-attempt 1)))

(defn- shq
  "Single-quote a string for embedding in a shell command, escaping embedded
  single quotes so the reconstructed word is verbatim."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- exec-command
  "The remote shell command for a VM exec. When the VM carries :nix (a resolved
  flake devShell), the STATIC environment is exported directly (PATH and
  LD_LIBRARY_PATH were extracted once at resolve time) — the full nix-shell
  activation script is never re-sourced per exec, so the devShell cannot flake
  out mid-session. Falls back to sourcing the script only if the static env was
  not extracted."
  [vm command cwd]
  (let [cwd (or cwd (get-in vm [:opts :workspace-guest]) "/workspace")]
    (if-let [nix (:nix vm)]
      (let [env (if (:path nix)
                  (str "export PATH=" (shq (:path nix))
                       (when (:ld-library-path nix)
                         (str " && export LD_LIBRARY_PATH=" (shq (:ld-library-path nix)))))
                  (str "source /etc/jolt/dev-env.sh"))]
        (str (:bash nix) " -c "
             (shq (str env " && cd " (shq cwd) " && " command))))
      (str "cd " cwd " && " command))))

(defn exec
  "Run a command in the VM. Returns {:stdout :stderr :exit}, plus
  :interrupted? true when an :interrupt atom fired.
  opts: :cwd (default the workspace's guest path), :timeout-ms (default 300000),
  :interrupt (atom), :interrupt-poll-ms."
  [vm command & {:keys [cwd timeout-ms interrupt interrupt-poll-ms] :or {timeout-ms 300000}}]
  (ssh-exec (:ip vm) (:private-key-path vm) (:host-public-key vm)
            (exec-command vm command cwd)
            :timeout-ms timeout-ms :interrupt interrupt :interrupt-poll-ms interrupt-poll-ms))

(defn exec!
  "Like exec but throws ex-info when the command exits non-zero (ex-data carries
  :stdout/:stderr/:exit) or is interrupted. Returns the result on success."
  [vm command & {:keys [cwd timeout-ms] :or {timeout-ms 300000}}]
  (let [r (exec vm command :cwd cwd :timeout-ms timeout-ms)]
    (when-not (zero? (or (:exit r) 1))
      (throw (ex-info (str (if (:interrupted? r) "interrupted" (str "command failed (" (:exit r) ")")) ": " command) r)))
    r))

(defn x11-exec
  "Run command in the VM with X11 forwarding to the host display. Blocks until
  the command exits. Uses a fresh ssh connection (not the ControlMaster mux,
  which was not started with -X). opts: :trusted? (use -Y instead of -X),
  :timeout-ms."
  [vm command & {:keys [trusted? timeout-ms] :or {timeout-ms 300000}}]
  (let [known (known-hosts-path! (:ip vm) (:host-public-key vm))]
    (try
      (let [argv (into-array String
                    (concat ["ssh" "-i" (:private-key-path vm)
                             (if trusted? "-Y" "-X")
                             "-o" "StrictHostKeyChecking=yes"
                             "-o" (str "UserKnownHostsFile=" known)
                             "-o" "BatchMode=yes"
                             "-o" "LogLevel=ERROR"
                             (str "sandbox@" (:ip vm))
                             command]))
            proc (.start (ProcessBuilder. argv))
            out (future (drain! (.getInputStream proc)))
            err (future (drain! (.getErrorStream proc)))]
        (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          {:stdout @out :stderr @err :exit (.exitValue proc)}
          (do (.destroyForcibly proc)
              (throw (ex-info "x11 command timed out" {:command command :timeout-ms timeout-ms})))))
      (finally (.delete (java.io.File. known))))))

(defn alive?
  "True while the VM's runner process is alive."
  [vm]
  (boolean (and (:process vm) (.isAlive (:process vm)))))

(defn info
  "Describe a VM: {:image :port :rootfs :workspace :mounts :running?}."
  [vm]
  {:image (:image vm)
   :port (:ip vm)
   :rootfs (:rootfs vm)
   :workspace (:workspace vm)
   :mounts (:mounts vm)
   :running? (alive? vm)})

;; No SCP/file-transfer surface: the sandbox is configured declaratively
;; (settings.json -> :packages / :nix-flake -> baked into the rootfs/dev-env
;; at boot). Dependencies are added by editing that config, never by copying
;; files into a running VM.
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
        pb (ProcessBuilder. (vec (concat (prlimit-nofile-argv)
                                          [(jolt-bin) "run" "-m" "jolt.microvm.runner" (json/write-str cfg)])))
        _ (.directory pb (File. project-dir))
        env (.environment pb)
        _ (.put env "JOLT_SKIP_MIN_VERSION" "1")
        _ (when-let [dir (or (System/getenv "JOLT_LIBKRUN_DIR") "/tmp/libkrun/target/release")]
            (.put env "LD_LIBRARY_PATH"
                  (str dir (when-let [e (.get env "LD_LIBRARY_PATH")] (str ":" e)))))
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

(defn- wait-for-tcp! [host port timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [ok? (try
                  (let [s (Socket.)]
                    (try
                      (.connect s (InetSocketAddress. host port) 500)
                      true
                      (finally (.close s))))
                  (catch Exception _ false))]
        (cond
          ok? :ok
          (> (- (System/currentTimeMillis) start) timeout-ms)
          (throw (ex-info "timed out waiting for port" {:host host :port port}))
          :else (do (Thread/sleep 50) (recur)))))))

(defn start-daemon!
  "Boot a glibc image with the jolt-exec daemon as its entrypoint (no SSH/shell),
  forward a host port to the daemon, and wait until it accepts connections.
  Returns {:port :rootfs :process :image :daemon-port}. Run commands with
  daemon-exec / daemon-run (or jolt.exec.client directly).

  opts: :image (glibc image, e.g. gcr.io/distroless/python3-debian13),
  :daemon-binary (path to the compiled exec-daemon), :daemon-port (guest port,
  default 8080), :ssh-port (host port, default ephemeral), :arch, :cpus,
  :memory-mib, :cache-dir, :project-dir."
  [{:keys [image daemon-binary daemon-port arch cpus memory-mib cache-dir project-dir]}]
  (check-or-throw!)
  (when-not (and daemon-binary (.exists (java.io.File. daemon-binary)))
    (throw (ex-info "daemon-binary not found — build it: jolt build -m jolt.exec.daemon -o exec-daemon"
                    {:daemon-binary daemon-binary})))
  (let [cache-dir (or cache-dir (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        rootfs (rootfs/prepare! image cache-dir arch)
        guest-port (or daemon-port 8080)
        guest-exe (exec-bundle/bundle-daemon! daemon-binary rootfs
                                    :shared-libs (:shared-libs (guest-deps/guest-deps project-dir)))
        net (net-up! cache-dir)
        cfg {"rootfs_path" rootfs
             "cpus" (or cpus 2)
             "memory_mib" (or memory-mib 512)
             "exec" [guest-exe (str guest-port)]
             "net_taps" [{"id" "eth0" "tap" (:tap net) "mac" (mapv int (:mac-bytes net))}]
             "dhcp" true}
        proc (spawn-runner! project-dir cfg)]
    (try
      (let [ip (net/wait-lease (:lease-file net) (:mac-str net) 60000)]
        (wait-for-tcp! ip guest-port 30000)
        {:ip ip :rootfs rootfs :process proc :image image :daemon-port guest-port
         :tap (:tap net) :lease-file (:lease-file net) :pid-file (:pid-file net)})
      (catch Exception e
        (.destroyForcibly proc)
        (net-down! net)
        (throw e)))))

(defn daemon-exec
  "Run a shell command via the in-guest exec daemon. Returns {:stdout :stderr :exit}."
  [d command & {:keys [cwd timeout-ms] :or {cwd "/" timeout-ms 30000}}]
  (exec-client/exec (:ip d) (:daemon-port d)
                    {"cmd" command "cwd" cwd "timeout_ms" timeout-ms}))

(defn daemon-run
  "Run a direct argv (no shell) via the in-guest exec daemon. distroless-safe."
  [d argv & {:keys [cwd timeout-ms] :or {cwd "/" timeout-ms 30000}}]
  (exec-client/exec (:ip d) (:daemon-port d)
                    {"argv" (vec argv) "cwd" cwd "timeout_ms" timeout-ms}))

(defn stop-daemon!
  "Stop a daemon-mode VM (the runner process is the guest's lifetime)."
  [d]
  (when (.isAlive (:process d)) (.destroyForcibly (:process d)))
  (net-down! {:tap (:tap d) :pid-file (:pid-file d)})
  :stopped)

(defn attach!
  "Attach an interactive SSH session to the running VM over an isolated pty
  (console relay). Blocks until the session exits; returns its exit status.
  The guest host key is pinned, and the host terminal size is applied to the
  pty."
  [vm]
  (let [known (known-hosts-path! (:ip vm) (:host-public-key vm))
        p (pty/open-pty!)]
    (try
      ;; Raw secondary: no local echo/canonical, so ssh requests ECHO=0 remotely
      ;; and the host terminal's own echo is the single echo.
      (pty/make-raw! (:secondary-fd p))
      (let [child (pty/spawn-secondary!
                   p ["ssh" "-i" (:private-key-path vm)
                      "-p" "22"
                      "-o" "StrictHostKeyChecking=yes"
                      "-o" (str "UserKnownHostsFile=" known)
                      "-o" "BatchMode=yes"
                      "-o" "LogLevel=ERROR"
                      "-t"
                      (str "sandbox@" (:ip vm))])]
        (pty/relay! p child))
      (finally
        (pty/close-pty! p)
        (.delete (java.io.File. known))))))

(defn stop!
  "Shut down the VM: graceful SSH poweroff, then destroy the runner process."
  [vm]
  (try
    (ssh-exec (:ip vm) (:private-key-path vm) (:host-public-key vm) "poweroff -f")
    (catch Exception _ nil))
  (Thread/sleep 1000)
  (when (.isAlive (:process vm))
    (.destroy (:process vm)))
  (Thread/sleep 200)
  (when (.isAlive (:process vm))
    (.destroyForcibly (:process vm)))
  (net-down! vm)
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
