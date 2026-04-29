{
  description = "Portal Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ "36.0.0" ];
        includeCmake = true;
        includeNDK = true;
      };
      androidSdk = "${android.androidsdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          jdk21
          android.androidsdk
          android-tools
          git
          unzip
          which
        ];

        JAVA_HOME = pkgs.jdk21.home;
        ANDROID_HOME = androidSdk;
        ANDROID_SDK_ROOT = androidSdk;

        shellHook = ''
          export GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.0.0/aapt2"
        '';
      };
    };
}
