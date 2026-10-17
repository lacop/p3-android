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
          install -Dm644 build/p3view.apk $out/p3view.apk
        '';
      };
    in
    {
      packages.${system}.default = apk;

      # nix run .#install   (phone connected with USB debugging enabled)
      apps.${system}.install = {
        type = "app";
        program = toString (pkgs.writeShellScript "p3view-install" ''
          exec ${pkgs.android-tools}/bin/adb install -r ${apk}/p3view.apk
        '');
      };

      # nix develop; ./build.sh
      devShells.${system}.default = pkgs.mkShell {
        packages = tools ++ [ pkgs.android-tools ];
        shellHook = env;
      };
    };
}
