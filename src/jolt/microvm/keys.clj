;; Ephemeral SSH key + host key generation via ssh-keygen (port of ssh.rs).
(ns jolt.microvm.keys
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- run-ssh-keygen! [key-path]
  (let [r (shell/sh "ssh-keygen" "-t" "ed25519" "-f" key-path "-N" "" "-q")]
    (when-not (zero? (:exit r))
      (throw (ex-info "ssh-keygen failed" {:stderr (:err r)})))))

(defn- chmod! [path mode]
  (Files/setPosixFilePermissions (Paths/get path (into-array String []))
                                 (PosixFilePermissions/fromString mode)))

(defn generate-ephemeral!
  "Generate an ed25519 client keypair.
  Returns {:private-key-path :public-key :dir}."
  []
  (let [dir (str (System/getProperty "java.io.tmpdir") "/jolt-ssh-" (java.util.UUID/randomUUID))
        key (str dir "/id_ed25519")]
    (.mkdirs (java.io.File. dir))
    (run-ssh-keygen! key)
    (chmod! key "rw-------")
    {:private-key-path key
     :public-key (str/trim (slurp (str key ".pub")))
     :dir dir}))

(defn generate-host!
  "Generate an ed25519 host keypair.
  Returns {:private-key-pem :public-key :dir}."
  []
  (let [dir (str (System/getProperty "java.io.tmpdir") "/jolt-host-" (java.util.UUID/randomUUID))
        key (str dir "/ssh_host_ed25519_key")]
    (.mkdirs (java.io.File. dir))
    (run-ssh-keygen! key)
    {:private-key-pem (slurp key)
     :public-key (str/trim (slurp (str key ".pub")))
     :dir dir}))

(defn inject-host!
  "Write host keys into rootfs/etc/ssh, removing stale image-built keys."
  [rootfs host]
  (let [dir (str rootfs "/etc/ssh")]
    (.mkdirs (java.io.File. dir))
    (when (.isDirectory (java.io.File. dir))
      (doseq [f (.listFiles (java.io.File. dir))]
        (let [n (.getName f)]
          (when (and (str/starts-with? n "ssh_host_")
                     (str/includes? n "_key"))
            (.delete f)))))
    (let [k (str dir "/ssh_host_ed25519_key")
          p (str dir "/ssh_host_ed25519_key.pub")]
      (spit k (:private-key-pem host))
      (spit p (str (:public-key host) "\n"))
      (chmod! k "rw-------")
      (chmod! p "rw-r--r--"))))
