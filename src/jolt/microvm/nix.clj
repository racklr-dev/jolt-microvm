;; jolt.microvm.nix — resolve a nix flake devShell into the pieces the
;; guest needs to run it: the print-dev-env script (sourced by nix bash) and
;; the nix bash absolute path. The closure lives in the host /nix/store, which
;; the VM mounts read-only, so nix-built binaries run in any base (musl or
;; glibc): nix patchelf's every binary to the nix glibc loader and rpaths under
;; /nix/store, so no guest glibc is needed.
(ns jolt.microvm.nix
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.nio.file Files StandardCopyOption]))

(defn- sha256-hex
  "Hex sha-256 of a UTF-8 string."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn nix-system
  "Nix system string for the host (e.g. \"x86_64-linux\")."
  []
  (or (System/getenv "NIX_SYSTEM")
      (let [arch (case (System/getProperty "os.arch")
                   "amd64" "x86_64"
                   "aarch64" "aarch64"
                   "x86_64" "x86_64"
                   (System/getProperty "os.arch"))
            os (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
                 "darwin" "linux")]
        (str arch "-" os))))

(defn- which
  "Absolute path of a command on PATH, or nil."
  [cmd]
  (let [r (shell/sh "sh" "-c" (str "command -v " cmd " 2>/dev/null || true"))]
    (let [p (str/trim (:out r))]
      (when (seq p) p))))

(defn- sane-tmpdir*
  "Given a TMPDIR candidate, return it when it exists and is writable, else /tmp."
  [t]
  (if (and t (.isDirectory (File. t)) (.canWrite (File. t)))
    t
    "/tmp"))

(defn- sane-tmpdir
  "A TMPDIR that exists and is writable, from the process env. A stale nix-shell
  leaves TMPDIR pointing at a deleted /tmp/nix-shell.* dir, which breaks
  `nix print-dev-env` (it tries to mkdir a child and fails with ENOENT); fall
  back to /tmp then."
  []
  (sane-tmpdir* (or (System/getenv "TMPDIR") (System/getenv "TMP") "/tmp")))

(defn- write-atomically!
  "Write text to path via a temp file + atomic rename (avoids torn reads from
  concurrent resolvers)."
  [path text]
  (let [tmp (str path ".tmp-" (System/nanoTime))]
    (spit tmp text)
    (Files/move (.toPath (File. tmp)) (.toPath (File. path))
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))))

(defn resolve!
  "Resolve a flake devShell ref into {:env-script :bash :flake-dir}.

  Runs `nix print-dev-env <ref>` with cwd = flake-dir (so relative refs like
  .#default resolve against the project). print-dev-env both evaluates the
  devShell and realizes its closure into the host /nix/store. The result is
  cached under cache-dir keyed by ref + system + flake-dir; a changed flake
  needs the cache dir cleared (or a different ref). Throws ex-info with the
  nix stderr on failure."
  [ref cache-dir flake-dir]
  (let [nix-bin (which "nix")]
    (when-not nix-bin
      (throw (ex-info ":nix-flake needs nix on the host PATH (install nix or clear :nix-flake)"
                      {:ref ref})))
    (let [system (nix-system)
          key (sha256-hex (str ref "\u0000" system "\u0000" (or flake-dir "")))
          dir (str cache-dir "/nix/" key)
          env-file (str dir "/dev-env.sh")
          bash-file (str dir "/bash")]
      (when-not (and (.isFile (File. env-file)) (.isFile (File. bash-file)))
        (.mkdirs (File. dir))
        (let [r (shell/sh nix-bin "--extra-experimental-features" "nix-command flakes"
                          "print-dev-env" ref
                          :dir (or flake-dir (System/getProperty "user.dir"))
                          :env (let [e (System/getenv)
                                     t (sane-tmpdir)]
                                 (assoc e "TMPDIR" t "TMP" t)))]
          (when-not (zero? (:exit r))
            (throw (ex-info (str "nix print-dev-env failed: " (str/trim (:err r)))
                            {:ref ref :stderr (:err r)})))
          (let [script (:out r)
                bash (nth (re-find #"(?m)^BASH='([^']+)'" script) 1)]
            (when-not bash
              (throw (ex-info "nix devShell has no BASH in print-dev-env output" {:ref ref})))
            (write-atomically! env-file script)
            (write-atomically! bash-file bash))))
      {:env-script (slurp env-file)
       :bash (str/trim (slurp bash-file))
       :flake-dir flake-dir})))
