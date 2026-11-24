;; Integration test: :nix-flake layers a flake devShell into the alpine
;; microVM. Needs nix + flakes + network (first run fetches nixpkgs/hello/rg),
;; and a user with kvm group access (see the runbook for the sudo invocation).
(ns jolt.test.nix-flake
  (:require [jolt.microvm :as vm]
            [clojure.string :as str]))

(def flake-dir "/tmp/jolt-nix-flake-test")
(def workspace "/tmp/jolt-nix-flake-ws")

(.mkdirs (java.io.File. flake-dir))
(.mkdirs (java.io.File. workspace))

;; minimal devShell: hello + ripgrep + one flake env var (gcc rides in via the
;; stdenv that mkShell includes by default)
(spit (str flake-dir "/flake.nix")
      "{ inputs.nixpkgs.url = \"nixpkgs\";
  outputs = { self, nixpkgs }: let
    system = \"x86_64-linux\";
    pkgs = import nixpkgs { inherit system; };
  in { devShells.${system}.default = pkgs.mkShell {
    packages = [ pkgs.hello pkgs.ripgrep ];
    JOLT_NIX_PROOF = \"from-flake\";
  }; };
}
")

(def v (vm/start! {:image "alpine:3.20"
                   :workspace workspace
                   :nix-flake (str "path:" flake-dir "#default")
                   :cpus 2 :memory-mib 768
                   :project-dir (System/getProperty "user.dir")}))

;; nix-built glibc binaries run off the mounted /nix/store in the musl guest
(assert (str/includes? (:stdout (vm/exec v "hello")) "Hello, world!"))
(assert (str/includes? (:stdout (vm/exec v "rg --version")) "ripgrep"))
(assert (str/includes? (:stdout (vm/exec v "gcc --version")) "gcc"))

;; the flake's env vars are set
(assert (= "from-flake" (:stdout (vm/exec v "printf '%s' \"$JOLT_NIX_PROOF\""))))

;; quoting survives the nix-bash wrapper: single quote inside double quotes
(assert (= "single'quote" (:stdout (vm/exec v "printf '%s' \"single'quote\""))))

(vm/stop! v)
(println "jolt-microvm nix-flake: devShell layered into alpine microVM OK")
