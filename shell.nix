# Dev shell for local server work on NixOS: JDK 21. Pages are read through Neuland.
{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = [ pkgs.jdk21 ];
}
