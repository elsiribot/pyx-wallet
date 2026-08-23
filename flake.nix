{
  description = "Pyx Wallet — Fedimint wallet (Flutter + Rust) dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, rust-overlay }:
    flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-linux" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
          overlays = [ rust-overlay.overlays.default ];
        };

        # NDK version must match android/app/build.gradle.kts (ndkVersion)
        ndkVersion = "28.2.13676358";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" "35" "36" ];
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          includeNDK = true;
          inherit ndkVersion;
          includeEmulator = false;
          includeSystemImages = false;
          cmakeVersions = [ "3.22.1" ];
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";

        # Rust with the Android targets conduit builds for. arm64 is the
        # release target; x86_64 is needed for the local redroid container.
        rustToolchain = pkgs.rust-bin.stable.latest.default.override {
          targets = [ "aarch64-linux-android" "x86_64-linux-android" ];
          extensions = [ "rust-src" "clippy" "rustfmt" ];
        };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            flutter
            rustToolchain
            cargo-ndk          # builds the Rust cdylib against the NDK toolchain
            androidSdk
            jdk17
            gradle
            imagemagick        # screenshot comparison in the design-match loop
            chromium           # renders docs/design/prototype.html for reference shots
            android-tools      # adb
            pkg-config
            cmake              # aws-lc-sys (via fedimint deps)
            perl               # aws-lc-sys assembly generation
            go                 # aws-lc-sys
            clang
            llvmPackages.libclang.lib  # bindgen (librocksdb-sys)
          ];

          LIBCLANG_PATH = "${pkgs.llvmPackages.libclang.lib}/lib";

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";
          JAVA_HOME = pkgs.jdk17.home;

          # Gradle must use the nix-provided (patched) aapt2 on NixOS.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/34.0.0/aapt2";

          shellHook = ''
            # flutter_rust_bridge_codegen must match the pinned crate/pub
            # version (=2.10.0). Installed into the project-local cargo home
            # the first time the shell is entered.
            FLAKE_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
            export CARGO_INSTALL_ROOT="$FLAKE_ROOT/.cargo-tools"
            export PATH="$CARGO_INSTALL_ROOT/bin:$PATH"
            if ! flutter_rust_bridge_codegen --version 2>/dev/null | grep -q 2.10.0; then
              echo "Installing flutter_rust_bridge_codegen 2.10.0 (one-time, ~2 min)..."
              cargo install flutter_rust_bridge_codegen --version 2.10.0 --locked
            fi
            echo "Pyx Wallet dev shell — flutter $(flutter --version --machine 2>/dev/null | grep -o '"frameworkVersion":"[^"]*"' | cut -d'"' -f4)"
          '';
        };
      });
}
