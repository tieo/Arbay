# Dev shell for local crawler testing on NixOS.
#
# Provides the same fetch fallback chain dependencies as the Docker image:
# python3 with curl_cffi (CurlCffiClient shells out to `python3 cffi_fetch.py`),
# Xvfb (HeadlessBrowser launches non-headless Chromium on DISPLAY :99),
# JDK 21, and the Playwright browsers from nixpkgs.
#
# The JVM Playwright version pinned in gradle (1.51.0, see the Dockerfile base
# tag) should match the nixpkgs playwright-driver version. Headless-only tests
# are still fine if it drifts.
{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = [
    (pkgs.python3.withPackages (ps: [ ps.curl-cffi ]))
    pkgs.xvfb
    pkgs.xvfb-run
    pkgs.jdk21
    pkgs.playwright-driver.browsers
  ];

  shellHook = ''
    export PLAYWRIGHT_BROWSERS_PATH=${pkgs.playwright-driver.browsers}
    export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
  '';
}
