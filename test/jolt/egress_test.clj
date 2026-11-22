;; Egress e2e: boot a libkrun 2.0 guest on a TAP and prove the full tapmasq
;; egress path: DHCP lease, host TAP reachable, DNS resolution through the
;; dnsmasq forwarder, an allow-listed CIDR flows via NAT, and everything else
;; is dropped by the nftables default-deny forward chain.
;;
;; This is a privileged boot script, not an assertion suite: krun/boot! calls
;; vmm_run, which exits the process with the guest's exit code. The guest's
;; /bin/sh probes each property and exits 0 only when all pass. Run with:
;;
;;   LD_LIBRARY_PATH=/tmp/libkrun/target/release \
;;     jolt run test/jolt/egress_test.clj
;;
;; then tear down (the KVM runner does this around the boot):
;;   sudo pkill -x dnsmasq; sudo nft delete table inet jolt;
;;   sudo iptables -t nat -D POSTROUTING -s 192.168.44.0/24 -j MASQUERADE;
;;   sudo ip link delete jolt0
(ns jolt.test.egress
  (:require [jolt.tapmasq :as net]
            [jolt.libkrun :as krun]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private tap "jolt0")
(def ^:private host-cidr "192.168.44.1/24")
(def ^:private subnet "192.168.44.0/24")
(def ^:private dns "192.168.44.1")
(def ^:private rootfs "/tmp/nettap-cache/alpine_3.20/base")

(defn- egress-iface []
  (-> (shell/sh "sh" "-c"
                "ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'")
      :out str/trim))

;; --- host network stack: TAP + dnsmasq (forwarding DNS) + default-deny ---
(net/tap-create! tap)
(net/tap-ip! tap host-cidr)
(net/ip-forward! true)
(let [config-file "/tmp/jolt-egress-dnsmasq.conf"
      cfg (net/dnsmasq-config {:interface tap :subnet-cidr host-cidr
                               :range-start "192.168.44.10"
                               :range-end "192.168.44.200"
                               :dns dns :upstream "1.1.1.1"})]
  (spit config-file cfg)
  (net/dnsmasq-start! {:config-file config-file
                       :pid-file "/tmp/jolt-egress-dnsmasq.pid"
                       :lease-file "/tmp/jolt-egress-dnsmasq.leases"
                       :log-file "/tmp/jolt-egress-dnsmasq.log"}))
(net/nftables-apply! {:table "jolt" :subnet subnet :dns dns
                      :allow-cidrs ["1.1.1.1/32"]
                      :rules-file "/tmp/jolt-egress-nft.rules"})
(net/nat-enable! subnet (egress-iface))

;; --- boot the guest; it self-verifies and its exit code becomes ours ---
(krun/boot! {:rootfs rootfs :cpus 1 :memory-mib 256
             :net-taps [{:id "eth0" :tap tap :mac (byte-array [2 0 0 0 0 1])}]
             :dhcp true
             :exec ["/bin/sh" "-c"
                    (str "echo === EGRESS E2E ===\n"
                         "ip addr show eth0 | grep -q 'inet 192.168.44.' && echo DHCP-OK || { echo FAIL-DHCP; exit 1; }\n"
                         "ping -c1 -W2 192.168.44.1 >/dev/null 2>&1 && echo HOST-OK || { echo FAIL-HOST; exit 1; }\n"
                         "nslookup example.com 192.168.44.1 >/dev/null 2>&1 && echo DNS-OK || { echo FAIL-DNS; exit 1; }\n"
                         "nc -w 5 1.1.1.1 53 </dev/null >/dev/null 2>&1 && echo EGRESS-ALLOWED || { echo FAIL-ALLOWED; exit 1; }\n"
                         "if nc -w 5 8.8.8.8 53 </dev/null >/dev/null 2>&1; then echo FAIL-LEAKED; exit 1; else echo EGRESS-BLOCKED; fi\n"
                         "echo EGRESS-E2E-PASS")]})
