;; Integration test: boot alpine desktops and assert the VNC server answers.
;; Manual/slow: the first run apk-installs each profile into the cache. Run
;; from the repo root with kvm available:
;;   sudo -u $USER --preserve-env env JOLT_BIN=/path/to/jolt \
;;     /path/to/jolt run test/jolt/desktop_test.clj
(require '[jolt.microvm :as vm]
         '[jolt.microvm.desktop :as desktop]
         '[clojure.string :as str])

(import '[java.net Socket InetSocketAddress])

(def cache-dir "/tmp/jolt-desktop-cache")

(defn vnc-banner
  "Connect to ip:port and return the first bytes the server sends (the RFB
  handshake starts with 'RFB ')."
  [ip port]
  (let [s (Socket.)]
    (try
      (.connect s (InetSocketAddress. ip port) 5000)
      (.setSoTimeout s 3000)
      (let [buf (byte-array 12)
            n (.read (.getInputStream s) buf)]
        (String. buf 0 (max 0 n)))
      (finally (.close s)))))

(defn check-profile! [k start!]
  (println "---" k "---")
  (vm/with-vm [v {:image "alpine:3.20"
                  :project-dir (System/getProperty "user.dir")
                  :cache-dir cache-dir
                  :packages (desktop/profile k)
                  :memory-mib 1024
                  :cpus 2}]
    (let [{:keys [ip port] :as conn} (start! v)]
      (println "connection:" conn)
      (let [banner (vnc-banner ip port)]
        (println "banner:" (pr-str banner))
        (assert (str/starts-with? banner "RFB ")
                (str "expected RFB banner, got " (pr-str banner)))))))

(println "jolt-microvm desktop: X11 profile")
(check-profile! :x11 desktop/start-x11!)
(println "jolt-microvm desktop: Wayland profile")
(check-profile! :wayland desktop/start-wayland!)
(println "jolt-microvm desktop: X11 + Wayland VNC OK")
