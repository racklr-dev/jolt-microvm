;; Rootfs preparation (port of rootfs.rs + the prep in mod.rs).
(ns jolt.microvm.rootfs
  (:require [jolt.oci :as oci]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell])
  (:import [java.nio.file Files Paths LinkOption StandardCopyOption CopyOption]))

(defn- image-safe [image] (str/replace image #"[/:]" "_"))

(defn- copy-tree! [src dst]
  (let [s (java.io.File. src)]
    (doseq [f (.listFiles s)]
      (let [t (java.io.File. dst (.getName f))
            sp (.toPath f)]
        (cond
          (Files/isSymbolicLink sp)
          (Files/copy sp (.toPath t)
                      (into-array CopyOption [(LinkOption/NOFOLLOW_LINKS)
                                              (StandardCopyOption/REPLACE_EXISTING)]))
          (.isDirectory f)
          (do (.mkdirs t) (copy-tree! f t))
          :else
          (Files/copy sp (.toPath t)
                      (into-array CopyOption [(StandardCopyOption/REPLACE_EXISTING)])))))))

(defn base!
  "Pull + unpack an OCI image into the cache and return the cached base path.
  Idempotent: reuses an existing base."
  [image cache-dir]
  (let [dir (str cache-dir "/" (image-safe image))
        base (str dir "/base")
        staging (str dir "/.staging")]
    (when-not (.exists (java.io.File. base))
      (.mkdirs (java.io.File. dir))
      (let [img (oci/pull-image image)]
        (oci/unpack staging (:layers img))
        (Files/move (Paths/get staging (into-array String []))
                    (Paths/get base (into-array String []))
                    (into-array java.nio.file.CopyOption [(StandardCopyOption/ATOMIC_MOVE)]))))
    base))

(defn prepare!
  "Pull + unpack an OCI image into a fresh session rootfs, caching the base.
  Returns the session rootfs path."
  [image cache-dir]
  (let [dir (str cache-dir "/" (image-safe image))
        base (base! image cache-dir)
        session (str dir "/session-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. session))
    (copy-tree! base session)
    session))

(defn install-alpine-packages!
  "Install apk packages into an alpine rootfs using its own apk + musl loader.
  (No buildah/docker needed on the host.) Returns :ok."
  [rootfs & packages]
  (let [loader (str rootfs "/lib/ld-musl-x86_64.so.1")
        apk (str rootfs "/sbin/apk")]
    (when-not (.exists (java.io.File. loader))
      (throw (ex-info "not an alpine rootfs (no musl loader)" {:rootfs rootfs})))
    (let [r (apply shell/sh "sudo" loader "--library-path"
                  (str rootfs "/lib:" rootfs "/usr/lib")
                  apk "--root" rootfs "--no-cache" "--allow-untrusted" "add" packages)]
      (when-not (zero? (:exit r))
        (throw (ex-info "apk add failed" {:stderr (:err r)})))
      :ok)))

(defn inject-sandbox-user!
  "Ensure the sandbox user + group exist in the rootfs passwd/group."
  [rootfs]
  (let [pp (str rootfs "/etc/passwd")
        gp (str rootfs "/etc/group")
        passwd (if (.exists (java.io.File. pp)) (slurp pp) "")
        group (if (.exists (java.io.File. gp)) (slurp gp) "")]
    (when-not (str/includes? passwd "sandbox:")
      (spit pp (str passwd "sandbox:x:1000:1000::/home/sandbox:/bin/sh\n")))
    (when-not (str/includes? group "sandbox:")
      (spit gp (str group "sandbox:x:1000:\n")))))

(defn inject-authorized-keys!
  "Write the client public key into the sandbox user's authorized_keys."
  [rootfs pubkey]
  (let [dir (str rootfs "/home/sandbox/.ssh")]
    (.mkdirs (java.io.File. dir))
    (let [home (java.io.File. (str rootfs "/home/sandbox"))
          sshd (java.io.File. dir)]
      (.setWritable home false true)
      (.setWritable sshd true true)
      (spit (str dir "/authorized_keys") (str pubkey "\n")))))

(defn write-krun-config!
  "Write /.krun_config.json so libkrun's built-in init launches sshd with
  the workspace virtio-fs mounted (port of mod.rs krun_config)."
  [rootfs & {:keys [sshd] :or {sshd "/usr/sbin/sshd"}}]
  (let [cmd (str "mount -t tmpfs tmpfs /run && mkdir -p /run/sshd "
                 "&& mkdir -p /workspace && mount -t virtiofs workspace /workspace "
                 "&& mount -t tmpfs -o uid=0,gid=0,mode=0755 tmpfs /var/empty "
                 "&& exec " sshd " -D -e -o StrictModes=no")
        cfg {"Cmd" ["/bin/sh" "-c" cmd]
             "Env" []
             "WorkingDir" "/"}]
    (spit (str rootfs "/.krun_config.json") (json/write-str cfg))))
