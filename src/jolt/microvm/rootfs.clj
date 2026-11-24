;; Rootfs preparation (port of rootfs.rs + the prep in mod.rs).
(ns jolt.microvm.rootfs
  (:require [jolt.oci :as oci]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell])
  (:import [java.nio.file Files Paths LinkOption StandardCopyOption CopyOption]))

(defn image-safe
  "Cache-key-safe form of an image ref + optional arch."
  [image & [arch]]
  (str/replace (str image (when arch (str "@" arch))) #"[/:@]" "_"))

(defn image-dir
  "Cache dir for an image + optional arch under cache-dir."
  [image cache-dir & [arch]]
  (str cache-dir "/" (image-safe image arch)))

(defn cached-base-path
  "Path to the cached base rootfs for an image + cache dir + optional arch."
  [image cache-dir & [arch]]
  (str (image-dir image cache-dir arch) "/base"))

(declare copy-tree!)

(defn cp-r!
  "Recursively copy a directory tree, preserving symlinks and file modes."
  [src dst]
  (.mkdirs (java.io.File. dst))
  (copy-tree! src dst)
  dst)

(defn cleanup!
  "Remove a directory tree if it exists (no-op otherwise)."
  [path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (let [r (shell/sh "rm" "-rf" "--" path)]
        (when-not (zero? (:exit r))
          (throw (ex-info "rm -rf failed" {:path path :stderr (:err r)})))))
    nil))

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
  Idempotent: reuses an existing base. arch defaults to the host arch."
  [image cache-dir & [arch]]
  (let [dir (image-dir image cache-dir arch)
        base (str dir "/base")
        staging (str dir "/.staging")]
    (when-not (.exists (java.io.File. base))
      (.mkdirs (java.io.File. dir))
      (let [img (oci/pull-image image (if arch {:arch arch} {}))]
        (oci/unpack staging (:layers img))
        (Files/move (Paths/get staging (into-array String []))
                    (Paths/get base (into-array String []))
                    (into-array java.nio.file.CopyOption [(StandardCopyOption/ATOMIC_MOVE)]))))
    base))

(defn prepare!
  "Pull + unpack an OCI image into a fresh session rootfs, caching the base.
  Returns the session rootfs path. arch defaults to the host arch."
  [image cache-dir & [arch]]
  (let [dir (image-dir image cache-dir arch)
        base (base! image cache-dir arch)
        session (str dir "/session-" (System/nanoTime))]
    (.mkdirs (java.io.File. session))
    (copy-tree! base session)
    session))

(defn musl-loader
  "Path to the musl dynamic loader inside an alpine rootfs, or nil."
  [rootfs]
  (let [d (java.io.File. (str rootfs "/lib"))]
    (some (fn [f]
            (when (str/starts-with? (.getName f) "ld-musl-")
              (.getAbsolutePath f)))
          (or (seq (.listFiles d)) []))))

(defn alpine-installed?
  "True when apk package pkg is recorded in the rootfs's installed database."
  [rootfs pkg]
  (let [db (str rootfs "/lib/apk/db/installed")]
    (and (.exists (java.io.File. db))
         (str/includes? (slurp db) (str "P:" pkg "\n")))))

(defn install-alpine-packages!
  "Install apk packages into an alpine rootfs using its own apk + musl loader.
  (No buildah/docker needed on the host.) Returns :ok."
  [rootfs & packages]
  (let [loader (musl-loader rootfs)
        apk (str rootfs "/sbin/apk")]
    (when-not loader
      (throw (ex-info "not an alpine rootfs (no musl loader)" {:rootfs rootfs})))
    (let [r (apply shell/sh "sudo" loader "--library-path"
                  (str rootfs "/lib:" rootfs "/usr/lib")
                  apk "--root" rootfs "--no-cache" "--allow-untrusted" "add" packages)]
      (when-not (zero? (:exit r))
        (throw (ex-info "apk add failed" {:stderr (:err r)})))
      ;; apk runs as root and creates root-owned files; chown them back to the
      ;; invoking user so clone/snapshot/restore/cleanup need no sudo, and
      ;; sshd still works because the krun config passes -o StrictModes=no.
      (let [uid (str/trim (:out (shell/sh "id" "-u")))
            gid (str/trim (:out (shell/sh "id" "-g")))
            c (shell/sh "sudo" "chown" "-R" (str uid ":" gid) rootfs)]
        (when-not (zero? (:exit c))
          (throw (ex-info "chown after apk failed" {:stderr (:err c)}))))
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
  "Write the client public key into the sandbox user's authorized_keys.
  By default the sandbox home is made non-writable to its owner (sshd
  StrictModes hygiene). Pass :writable-home? true to leave it writable, so the
  warm VM can create and mount directories under /home/sandbox (e.g.
  ~/.faraday)."
  [rootfs pubkey & {:keys [writable-home?] :or {writable-home? false}}]
  (let [dir (str rootfs "/home/sandbox/.ssh")]
    (.mkdirs (java.io.File. dir))
    (let [home (java.io.File. (str rootfs "/home/sandbox"))
          sshd (java.io.File. dir)]
      (when-not writable-home?
        (.setWritable home false true))
      (.setWritable sshd true true)
      (spit (str dir "/authorized_keys") (str pubkey "\n")))))

(defn sshd-exec-command
  "Build the guest workload args (shell) that mount the virtio-fs tags and run
  sshd. mounts: [{:tag :host :guest}]."
  [mounts & {:keys [sshd] :or {sshd "/usr/sbin/sshd"}}]
  (let [mount-cmds (str/join " && "
                             (map #(str "mkdir -p " (:guest %) " && mount -t virtiofs "
                                        (when (:readonly? %) "-o ro ")
                                        (:tag %) " " (:guest %))
                                  mounts))
        cmd (str "mount -t tmpfs tmpfs /run && mkdir -p /run/sshd"
                 (when (seq mount-cmds) (str " && " mount-cmds))
                 " && mount -t tmpfs -o uid=0,gid=0,mode=0755 tmpfs /var/empty "
                 "&& exec " sshd " -D -e -o StrictModes=no")]
    ["/bin/sh" "-c" cmd]))
