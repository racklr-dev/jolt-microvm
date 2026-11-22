;; jolt.microvm.deps — compose :jolt/guest-deps declarations across a
;; project's resolved dependency graph. Any library a project pulls in can
;; declare what its guest runtime needs, and jolt-microvm installs the union:
;;
;;   ;; in a library's deps.edn
;;   :jolt/guest-deps {:shared-libs ["libncurses.so.6" "libtinfo.so.6"]
;;                     :apk ["openssh" "python3"]}
;;
;; :shared-libs are baked into glibc guests; :apk are installed into alpine
;; guests. Reconciliation mirrors jolt's own :jolt/native handling: project
;; first, then dependency order, deduped.
(ns jolt.microvm.deps
  (:require [jolt.deps :as deps]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

(defn- gitlib-deps-edn [url sha]
  (let [slug (str/replace url #"[:/]" "_")
        f (File. (str (System/getProperty "user.home") "/.jolt/gitlibs/" slug "/" sha "/deps.edn"))]
    (when (.isFile f)
      (edn/read-string (slurp f)))))

(defn guest-deps
  "Reconcile :jolt/guest-deps across project-dir's deps.edn and every
  transitive git dependency's deps.edn. Returns {:shared-libs [...] :apk [...]}
  deduped in stable order (project first, then dependency order)."
  [project-dir]
  (let [project-edn (edn/read-string (slurp (str project-dir "/deps.edn")))
        r (deps/resolve-deps (:deps project-edn) project-dir)
        edns (into [[:project project-edn]]
                   (keep (fn [[lib coord]]
                           (when-let [u (:git/url coord)]
                             (when-let [e (gitlib-deps-edn u (:git/sha coord))]
                               [lib e])))
                         (:libs r)))
        collect (fn [k]
                  (vec (distinct (mapcat #(get-in (second %) [:jolt/guest-deps k]) edns))))]
    {:shared-libs (collect :shared-libs)
     :apk (collect :apk)}))
