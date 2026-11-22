;; Command-line entry point for jolt-microvm.
;;   jolt run -m jolt.microvm.cli check
;;   jolt run -m jolt.microvm.cli exec alpine:3.20 uname -a
;;   jolt run -m jolt.microvm.cli run  alpine:3.20 python3 -c 'print(42)'
;;   jolt run -m jolt.microvm.cli shell alpine:3.20
;;   jolt run -m jolt.microvm.cli install alpine:3.20 python3 nodejs
;;   jolt run -m jolt.microvm.cli snapshots alpine:3.20
(ns jolt.microvm.cli
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(defn- usage []
  (println "jolt.microvm.cli — run commands in an OCI microVM")
  (println "  check                    print the dependency check")
  (println "  exec IMAGE CMD...        boot, run CMD, stream output, exit with its code")
  (println "  run IMAGE CMD...         like exec, but a nonzero exit throws")
  (println "  shell IMAGE              boot and attach an interactive shell")
  (println "  install IMAGE PKG...     apk-install packages into the cached base")
  (println "  snapshots IMAGE          list saved snapshots")
  (System/exit 1))

(defn- opts [image]
  {:image image :project-dir (System/getProperty "user.dir")})

(defn- emit! [r]
  (print (:stdout r)) (flush)
  (binding [*out* *err*] (print (:stderr r)) (flush)))

(defn -main [& args]
  (case (first args)
    "check"
    (do (prn (vm/check!)) (System/exit 0))

    "exec"
    (let [[image & cmd] (rest args)]
      (when-not image (usage))
      (let [v (vm/start! (opts image))
            r (vm/exec v (str/join " " cmd))]
        (emit! r)
        (vm/stop! v)
        (System/exit (:exit r))))

    "run"
    (let [[image & cmd] (rest args)]
      (when-not image (usage))
      (let [v (vm/start! (opts image))]
        (try
          (emit! (vm/exec! v (str/join " " cmd)))
          (System/exit 0)
          (finally (vm/stop! v)))))

    "shell"
    (let [image (second args)]
      (when-not image (usage))
      (let [v (vm/start! (opts image))]
        (try
          (vm/attach! v)
          (finally (vm/stop! v)))
        (System/exit 0)))

    "install"
    (let [[image & pkgs] (rest args)]
      (when-not (and image (seq pkgs)) (usage))
      (vm/install-image! image (vec pkgs))
      (println "installed into cached base:" (str/join ", " pkgs))
      (System/exit 0))

    "snapshots"
    (let [image (second args)]
      (when-not image (usage))
      (let [v (vm/start! (opts image))]
        (try
          (prn (vm/list-snapshots v))
          (finally (vm/stop! v)))
        (System/exit 0)))

    (usage)))
