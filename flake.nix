{
  description = "P3 thermal camera viewer for Android, built without Gradle";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      # Android build-tools only ship for x86_64 Linux.
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true; # Android SDK license
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ]; # Android 17
        buildToolsVersions = [ "37.0.0" ];
        toolsVersion = null;
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
        includeCmake = false;
        includeSources = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      tools = [ pkgs.jdk21_headless pkgs.kotlin pkgs.zip ];
      env = ''
        export ANDROID_JAR=$(echo ${sdk}/platforms/android-*/android.jar)
        export BUILD_TOOLS=$(echo ${sdk}/build-tools/*)
        export KOTLIN_STDLIB=${pkgs.kotlin}/lib/kotlin-stdlib.jar
      '';
      apk = pkgs.stdenvNoCC.mkDerivation {
        pname = "p3view-android";
        version = "0.1.0";
        src = ./.;
        nativeBuildInputs = tools;
        buildPhase = ''
          runHook preBuild
          export HOME=$TMPDIR
          export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMPDIR
          ${env}
          bash ./build.sh
          runHook postBuild
        '';
        installPhase = ''
          install -Dm644 build/p3view-unsigned.apk $out/p3view-unsigned.apk
        '';
      };
      # The Nix build cannot see the git-ignored signing key, so it makes an unsigned APK and
      # these apps sign it with ./debug.keystore (see genkey.sh) from the current directory.
      sign = pkgs.writeShellScript "p3view-sign" ''
        set -euo pipefail
        export PATH=${pkgs.jdk21_headless}/bin:$PATH
        export BUILD_TOOLS=$(echo ${sdk}/build-tools/*)
        mkdir -p build
        ${./sign.sh} ${apk}/p3view-unsigned.apk build/p3view.apk
      '';
    in
    {
      packages.${system}.default = apk;

      apps.${system} = {
        # nix run .#sign      signs the build into build/p3view.apk
        sign = {
          type = "app";
          program = toString sign;
        };

        # nix run .#install   signs, then installs over adb (phone connected with USB debugging enabled)
        install = {
          type = "app";
          program = toString (pkgs.writeShellScript "p3view-install" ''
            set -euo pipefail
            ${sign}
            exec ${pkgs.android-tools}/bin/adb install -r build/p3view.apk
          '');
        };
      };

      # nix develop; ./build.sh
      devShells.${system}.default = pkgs.mkShell {
        packages = tools ++ [ pkgs.android-tools ];
        shellHook = env;
      };
    };
}
