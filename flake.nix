{
  description = "jolt-microvm — OCI-image libkrun microVMs: alpine X11/Wayland desktop examples on a Nix-provided toolchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jolt.url = "git+https://github.com/jolt-lang/jolt.git?submodules=1";

    # libkrun source, pinned: nixpkgs' libkrun (1.19.x) predates the 2.0
    # builder/device C API and has no virtio-net TAP backend, which the
    # desktop examples need (their VNC server lives on the guest's TAP lease
    # IP). This is the proven main-line commit the desktop examples shipped
    # against; NET=1 enables the net device backend.
    libkrun-src = {
      url = "github:containers/libkrun/d3dc2a75bac6a0ac8e4047791e5ce5960bf289b6";
      flake = false;
    };

    # A rustup toolchain with an extra target std is the one part nixpkgs'
    # bundled rustc cannot provide: the guest init must be a musl-static
    # binary, and rust builds musl targets self-contained (rust-lld + the
    # musl crt/libc shipped in the target std), so no musl-gcc is needed.
    rust-overlay.url = "github:oxalica/rust-overlay";
  };

  outputs =
    { self, nixpkgs, jolt, libkrun-src, rust-overlay }:
    let
      systems = [ "x86_64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ rust-overlay.overlays.default ];
          };
        in
        f system pkgs);

      # jolt-lang's flake bakes self.shortRev as the version, so a nix-built
      # jolt reports e.g. "4e7d7fb". jolt.deps reads a version's leading
      # numeric prefix as a MAJOR number, so a sha starting with 0 would fail
      # every :jolt/min-version floor (0a1b2c3 -> 0). A dev-build version with
      # no leading number ("dev-g<sha>") carries the sha but applies no floor,
      # matching tools/version.sh's no-tag checkout spelling.
      jolt-bin =
        system:
        jolt.packages.${system}.jolt.overrideAttrs (old: {
          __intentionallyOverridingVersion = true;
          version = "dev-g${jolt.shortRev}";
          JOLT_VERSION = "dev-g${jolt.shortRev}";
        });

      # libkrun 2.0 with the virtio-net backend, plus the static musl guest
      # init it injects as PID 1, plus libkrunfw (libkrun dlopens
      # libkrunfw.so.5 at runtime; nixpkgs' 5.5.0 exports the krunfw_get_kernel
      # symbol the 2.0 payload loader needs).
      mkLibkrun =
        pkgs:
        let
          rustToolchain = pkgs.rust-bin.stable.latest.minimal.override {
            targets = [ "x86_64-unknown-linux-musl" ];
          };
          rustPlatform = pkgs.makeRustPlatform {
            cargo = rustToolchain;
            rustc = rustToolchain;
          };

          libkrun-so = rustPlatform.buildRustPackage {
            pname = "libkrun";
            version = "2.0.0-dev";
            src = libkrun-src;
            cargoLock = {
              lockFile = libkrun-src + "/Cargo.lock";
              outputHashes = {
                "ffier-0.3.0" = "sha256-RdGYMR4QbFz1vUFcEynunUXnFXXhc6fv6mwJRwifDHE=";
              };
            };
            cargoBuildFlags = [
              "--package"
              "libkrun"
              "--features"
              "net,ffi"
            ];
            doCheck = false;

            # nixpkgs' cargo setup hook exports ZSTD_SYS_USE_PKG_CONFIG=1,
            # which makes zstd-sys probe pkg-config (--static) for libzstd.a
            # instead of compiling its bundled C. Vendored matches the host
            # build and keeps libkrun.so free of a libzstd DT_NEEDED.
            preBuild = "unset ZSTD_SYS_USE_PKG_CONFIG";

            installPhase = ''
              runHook preInstall
              mkdir -p "$out/lib"
              cp target/x86_64-unknown-linux-gnu/release/libkrun.so "$out/lib/libkrun.so.2.0.0"
              ln -s libkrun.so.2.0.0 "$out/lib/libkrun.so.2"
              ln -s libkrun.so.2 "$out/lib/libkrun.so"
              runHook postInstall
            '';
          };

          # init/init-binary is excluded from libkrun's cargo workspace, so it
          # carries its own committed Cargo.lock. Built for the musl target it
          # becomes a static-pie binary — the only shape that works as the
          # guest's injected PID 1 (the cdylib libkrun_init.so segfaults as init).
          # The source is filtered to just this crate + its path dep so the
          # workspace-root Cargo.lock (a different lock) never shadows it.
          krun-init-src = pkgs.runCommand "krun-init-src" { } ''
            mkdir -p "$out"
            cp -R "${libkrun-src}/init/init-binary/." "$out/"
            cp -R "${libkrun-src}/init/common/." "$out/common/"
            sed -i 's|path = "../common"|path = "./common"|' "$out/Cargo.toml"
          '';

          krun-init = rustPlatform.buildRustPackage {
            pname = "krun-init";
            version = "0.1.0-2.0.0-dev";
            src = krun-init-src;
            cargoLock = { lockFile = libkrun-src + "/init/init-binary/Cargo.lock"; };
            cargoBuildFlags = [
              "--target"
              "x86_64-unknown-linux-musl"
            ];
            doCheck = false;

            installPhase = ''
              runHook preInstall
              mkdir -p "$out/bin"
              cp target/x86_64-unknown-linux-musl/release/krun-init "$out/bin/krun-init"
              runHook postInstall
            '';
          };
        in
        pkgs.symlinkJoin {
          name = "libkrun-2.0.0-net";
          paths = [
            libkrun-so
            krun-init
            pkgs.libkrunfw
          ];
        };
    in
    {
      packages = forAllSystems (
        system: pkgs:
        let
          j = jolt-bin system;
        in
        {
          libkrun = mkLibkrun pkgs;
          jolt = j;
          default = j;
        }
      );

      # `nix run .#desktop-x11` boots the alpine X11 desktop and prints the
      # VNC address; .#desktop-wayland and .#desktop-x11-app do the same for
      # Wayland and a single ssh -X app. sudo stays on the host (setuid, and
      # jolt-microvm uses it for tap/apk seeding); ip/dnsmasq/nftables/ssh/xauth
      # come from nix so they resolve inside the nix-built jolt's PATH.
      apps = forAllSystems (
        system: pkgs:
        let
          j = jolt-bin system;
          lk = mkLibkrun pkgs;
          toolPath = pkgs.lib.makeBinPath [
            j
            pkgs.iproute2
            pkgs.dnsmasq
            pkgs.nftables
            pkgs.openssh
            pkgs.util-linux
            pkgs.xauth
          ];
          # Native shared objects the nix-built jolt dlopens by soname at
          # runtime (a nix jolt cannot open the host's glibc-linked copies).
          nativeLibs = pkgs.lib.makeLibraryPath [
            pkgs.openssl
            pkgs.zlib
            pkgs.zstd
            pkgs.sqlite
            pkgs.ncurses
          ];
          mk-app =
            name: script:
            pkgs.writeShellScript name ''
              export PATH="${toolPath}:/usr/bin:/usr/sbin:/bin:/sbin"
              export LD_LIBRARY_PATH="${lk}/lib:${nativeLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
              export JOLT_LIBKRUN_DIR="${lk}/lib"
              export JOLT_LIBKRUN_INIT="${lk}/bin/krun-init"
              export JOLT_BIN="${j}/bin/jolt"
              cd "${self}"
              exec "${j}/bin/jolt" run "${script}"
            '';
          x11 = mk-app "jolt-desktop-x11" "examples/desktop/x11.clj";
          wayland = mk-app "jolt-desktop-wayland" "examples/desktop/wayland.clj";
          x11-app = mk-app "jolt-desktop-x11-app" "examples/desktop/x11-app.clj";
        in
        {
          desktop-x11 = { type = "app"; program = "${x11}"; };
          desktop-wayland = { type = "app"; program = "${wayland}"; };
          desktop-x11-app = { type = "app"; program = "${x11-app}"; };
          default = { type = "app"; program = "${x11}"; };
        }
      );

      devShells = forAllSystems (
        system: pkgs:
        let
          j = jolt-bin system;
          lk = mkLibkrun pkgs;
        in
        {
          default = pkgs.mkShell {
            packages = [
              j
              pkgs.iproute2
              pkgs.dnsmasq
              pkgs.nftables
              pkgs.openssh
              pkgs.util-linux
              pkgs.xauth
              pkgs.tigervnc
              pkgs.git
              pkgs.openssl
            ];

            shellHook = ''
              export PATH="$PATH:/usr/bin:/usr/sbin:/bin:/sbin"
              export LD_LIBRARY_PATH="${lk}/lib:${pkgs.lib.makeLibraryPath [ pkgs.openssl pkgs.zlib pkgs.zstd pkgs.sqlite pkgs.ncurses ]}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
              export JOLT_LIBKRUN_DIR="${lk}/lib"
              export JOLT_LIBKRUN_INIT="${lk}/bin/krun-init"
              export JOLT_BIN="${j}/bin/jolt"
              echo "jolt-microvm dev env — jolt, libkrun (net), desktop tooling"
              echo "run: jolt run examples/desktop/x11.clj   (or wayland.clj, x11-app.clj)"
            '';
          };
        }
      );

      formatter = forAllSystems (system: _pkgs: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
