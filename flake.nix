{
  description = "VSCodroid - Code OSS on Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;

      vscodeVersion = builtins.replaceStrings [ "\n" "\r" " " ] [ "" "" "" ] (builtins.readFile ./VSCODE_VERSION);

      pkgsFor = system: import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      mkAndroidSdk = pkgs:
        let
          composition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37" ];
            buildToolsVersions = [ "35.0.0" ];
            includeNDK = true;
            ndkVersions = [ "27.2.12479018" ];
            includeCmake = true;
            cmakeVersions = [ "3.22.1" ];
          };
        in
          composition.androidsdk;

      mkApk = { pkgs, androidSdk, release ? false, hash ? null }:
        let
          ndkDir = "${androidSdk}/libexec/android-sdk/ndk/27.2.12479018";
          sdkDir = "${androidSdk}/libexec/android-sdk";
        in
        pkgs.stdenv.mkDerivation {
          pname = if release then "vscodroid-release-apk" else "vscodroid-debug-apk";
          version = vscodeVersion;

          src = pkgs.lib.cleanSourceWith {
            src = ./.;
            filter = path: type:
              let
                base = baseNameOf path;
              in
                !(type == "directory" && (base == ".git" || base == "build" || base == ".gradle" || base == ".idea"))
                && !(type == "regular" && (pkgs.lib.hasSuffix ".aab" base || pkgs.lib.hasSuffix ".apk" base));
          };

          nativeBuildInputs = [
            pkgs.jdk17
            pkgs.nodejs_24
            pkgs.python3
            pkgs.git
            pkgs.cmake
            pkgs.ninja
            pkgs.curl
            pkgs.which
            pkgs.bash
            pkgs.coreutils
          ];

          ANDROID_HOME = sdkDir;
          ANDROID_NDK_HOME = ndkDir;
          ANDROID_NDK_ROOT = ndkDir;
          JAVA_HOME = "${pkgs.jdk17}";
          GRADLE_USER_HOME = "/build/.gradle";

          outputHashAlgo = if hash != null then "sha256" else null;
          outputHashMode = if hash != null then "recursive" else null;
          outputHash = hash;

          buildPhase = ''
            runHook preBuild

            export HOME=/build/home
            mkdir -p "$HOME" "$GRADLE_USER_HOME"
            export PATH="${sdkDir}/platform-tools:${sdkDir}/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

            echo "=== Step 1: Fetching VS Code OSS server tree ==="
            export VSCODE_VERSION="${vscodeVersion}"
            export VSCODE_OSS_URL="https://github.com/rmyndharis/VSCodroid/releases/download/server-${vscodeVersion}/vscode-reh-web-linux-arm64-${vscodeVersion}.tar.gz"
            bash scripts/fetch-vscode-oss.sh
            bash scripts/package-assets.sh
            python3 scripts/build-nls-bundles.py

            echo "=== Step 2: Downloading toolchains, runtimes, and extensions ==="
            bash scripts/download-termux-tools.sh
            bash scripts/download-npm.sh
            bash scripts/download-python.sh
            bash scripts/download-extensions.sh
            bash scripts/download-musl-loader.sh

            echo "=== Step 3: Compiling native addons and compatibility shims ==="
            bash scripts/download-node.sh
            bash scripts/build-native-addons.sh
            bash scripts/build-glibc-shim.sh \
              --scan android/app/src/main/assets/vscode-reh \
              --scan android/app/src/main/assets/extensions
            bash scripts/build-exec-trampoline.sh
            bash scripts/build-claude-shim.sh

            echo "=== Step 4: Building Android APK with Gradle ==="
            cd android
            chmod +x gradlew
            ${if release then "./gradlew assembleRelease" else "./gradlew assembleDebug"}
            cd ..

            runHook postBuild
          '';

          installPhase = ''
            runHook preInstall
            mkdir -p $out
            ${if release then ''
              cp android/app/build/outputs/apk/release/*.apk $out/
            '' else ''
              cp android/app/build/outputs/apk/debug/app-debug.apk $out/
            ''}
            runHook postInstall
          '';
        };
    in {
      packages = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          androidSdk = mkAndroidSdk pkgs;
        in rec {
          # An FHS chroot for the tools that cannot run on NixOS as-is. The
          # Android NDK ships dynamically linked executables that expect the
          # FHS layout (/lib64/ld-linux-x86-64.so.2), and the build scripts
          # reach for bsdtar and patchelf by name. buildFHSEnv supplies all
          # three without touching the host system:
          #
          #   nix run .#fhs -- bash scripts/build-all.sh
          fhs = pkgs.buildFHSEnv {
            name = "vscodroid-fhs";
            targetPkgs = p: with p; [
              jdk17
              nodejs_24
              python3
              git
              cmake
              ninja
              gnumake     # CMake's "Unix Makefiles" generator needs /usr/bin/make
              curl
              which
              bash
              coreutils
              libarchive  # bsdtar: reads the .deb ar archives
              zstd
              patchelf
              file
              gnutar
              gzip
              xz
              zlib       # libz.so.1: the NDK clang links against it
              ncurses    # libtinfo.so: the NDK llvm tools link against it
            ];
            runScript = "bash";
          };

          apk = mkApk {
            inherit pkgs androidSdk;
            release = false;
          };

          apk-release = mkApk {
            inherit pkgs androidSdk;
            release = true;
          };

          default = apk;

          android-sdk = androidSdk;

          server-tarball = pkgs.fetchurl {
            name = "vscode-reh-web-linux-arm64-${vscodeVersion}.tar.gz";
            url = "https://github.com/rmyndharis/VSCodroid/releases/download/server-${vscodeVersion}/vscode-reh-web-linux-arm64-${vscodeVersion}.tar.gz";
            hash = "sha256-yfWKGJLCTEvSkt/B2BoqCScCaGZzUTzZRTUBWJbvdrA=";
          };
        }
      );

      apps = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          androidSdk = mkAndroidSdk pkgs;
          sdkDir = "${androidSdk}/libexec/android-sdk";
          ndkDir = "${androidSdk}/libexec/android-sdk/ndk/27.2.12479018";
        in rec {
          build-apk = {
            type = "app";
            program = "${pkgs.writeShellApplication {
              name = "build-apk";
              runtimeInputs = [
                pkgs.jdk17
                pkgs.nodejs_24
                pkgs.python3
                pkgs.git
                pkgs.cmake
                pkgs.ninja
                pkgs.curl
                pkgs.which
                pkgs.bash
                pkgs.coreutils
              ];
              text = ''
                export ANDROID_HOME="${sdkDir}"
                export ANDROID_NDK_HOME="${ndkDir}"
                export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
                export JAVA_HOME="${pkgs.jdk17}"
                export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

                if [ ! -f "scripts/build-all.sh" ]; then
                  echo "ERROR: Run this command from the root of the VSCodroid repository." >&2
                  exit 1
                fi

                bash scripts/build-all.sh
              '';
            }}/bin/build-apk";
          };

          build-apk-release = {
            type = "app";
            program = "${pkgs.writeShellApplication {
              name = "build-apk-release";
              runtimeInputs = [
                pkgs.jdk17
                pkgs.nodejs_24
                pkgs.python3
                pkgs.git
                pkgs.cmake
                pkgs.ninja
                pkgs.curl
                pkgs.which
                pkgs.bash
                pkgs.coreutils
              ];
              text = ''
                export ANDROID_HOME="${sdkDir}"
                export ANDROID_NDK_HOME="${ndkDir}"
                export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
                export JAVA_HOME="${pkgs.jdk17}"
                export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

                if [ ! -f "android/gradlew" ]; then
                  echo "ERROR: Run this command from the root of the VSCodroid repository." >&2
                  exit 1
                fi

                cd android
                ./gradlew assembleRelease
              '';
            }}/bin/build-apk-release";
          };

          default = build-apk;
        }
      );

      devShells = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          androidSdk = mkAndroidSdk pkgs;
          sdkDir = "${androidSdk}/libexec/android-sdk";
          ndkDir = "${androidSdk}/libexec/android-sdk/ndk/27.2.12479018";
        in {
          default = pkgs.mkShell {
            name = "vscodroid-devshell";

            buildInputs = [
              pkgs.jdk17
              pkgs.nodejs_24
              pkgs.python3
              pkgs.git
              pkgs.cmake
              pkgs.ninja
              pkgs.curl
              pkgs.which
              pkgs.bash
              pkgs.coreutils
            ];

            shellHook = ''
              export ANDROID_HOME="${sdkDir}"
              export ANDROID_NDK_HOME="${ndkDir}"
              export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
              export JAVA_HOME="${pkgs.jdk17}"
              export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

              echo "========================================="
              echo "  VSCodroid Nix Development Shell"
              echo "========================================="
              echo "  Java:        $(java -version 2>&1 | head -n 1)"
              echo "  Node:        $(node --version)"
              echo "  Python:      $(python3 --version)"
              echo "  Android SDK: $ANDROID_HOME"
              echo "  Android NDK: $ANDROID_NDK_HOME"
              echo ""
              echo "  Commands:"
              echo "    bash scripts/build-all.sh  # Prepare assets & build debug APK"
              echo "    bash scripts/build-aab.sh  # Build signed release AAB"
              echo "========================================="
            '';
          };

          # nix develop .#fhs -- the FHS shell for building on NixOS. The
          # default shell cannot run the NDK toolchain; this one can.
          # .env is what nix develop expects from a buildFHSEnv.
          fhs = (pkgs.buildFHSEnv {
            name = "vscodroid-fhs";
            targetPkgs = p: with p; [
              jdk17
              nodejs_24
              python3
              git
              cmake
              ninja
              gnumake
              curl
              which
              bash
              coreutils
              libarchive
              zstd
              patchelf
              file
              gnutar
              gzip
              xz
              zlib
              ncurses
            ];
            runScript = "bash";
          }).env;
        }
      );
    };
}
