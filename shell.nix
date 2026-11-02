# Dev shell for local server work on NixOS: JDK 21, and python3 with curl_cffi, which
# CurlCffiClient shells out to (`python3 cffi_fetch.py`). Pages are read through Neuland.
{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = [
    (pkgs.python3.withPackages (ps: [ ps.curl-cffi ]))
    pkgs.jdk21
  ];
}
