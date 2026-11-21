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

(defn- spawn-runner! [project-dir config]
  (let [pb (ProcessBuilder. [(jolt-bin) "run" "-m" "jolt.microvm.runner" (json/write-str config)])
        _ (.directory pb (File. project-dir))
        _ (.put (.environment pb) "JOLT_SKIP_MIN_VERSION" "1")
        p (.start pb)]
    p))

(defrecord VM [rootfs port private-key-path host-public-key process workspace])

(defn start!
  "Boot a microVM from an OCI image and wait for sshd.
  opts: :image (required), :workspace, :cache-dir, :cpus, :memory-mib,
        :ssh-port (0 = ephemeral), :project-dir (dir of deps.edn with the deps).
  Returns a VM record."
  [{:keys [image workspace cache-dir cpus memory-mib ssh-port project-dir]}]
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
      (->VM rootfs port (:private-key-path ephemeral) (:public-key host) proc ws))))

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
