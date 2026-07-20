#!/usr/bin/env python3
"""
Interactive captcha gate for the stealth browser sidecars.

When an Akamai/Datadome/Cloudflare challenge will not clear on its own, the token is bound to the IP
and browser that solves it, so it can only be solved in the crawler's OWN Chrome on this host. This
module exposes that live Chrome session over noVNC (x11vnc bridging the sidecar's X display to a
websocket the browser-based noVNC client renders) and blocks until a human solves the challenge in
place, then lets the crawl continue in the same warmed session.

Control lines are written to stderr with the ARBAY_CTRL prefix so the Kotlin side can react in real
time (surface the "solve" link, then resume): CAPTCHA_INTERACTIVE, CAPTCHA_SOLVED, CAPTCHA_TIMEOUT.
"""
import os
import sys
import subprocess
import asyncio
import time

NOVNC_PORT = int(os.environ.get("ARBAY_NOVNC_PORT", "6080"))
VNC_PORT = int(os.environ.get("ARBAY_VNC_PORT", "5900"))
NOVNC_WEB = os.environ.get("ARBAY_NOVNC_WEB", "/usr/share/novnc")
CTRL_PREFIX = "ARBAY_CTRL:"

_vnc_proc: subprocess.Popen | None = None
_ws_proc: subprocess.Popen | None = None


def ctrl(msg: str) -> None:
    sys.stderr.write(f"{CTRL_PREFIX}{msg}\n")
    sys.stderr.flush()


def start_viewer(display: str) -> None:
    """Start x11vnc on `display` and a websockify/noVNC bridge on NOVNC_PORT. Idempotent."""
    global _vnc_proc, _ws_proc
    if _vnc_proc is None or _vnc_proc.poll() is not None:
        _vnc_proc = subprocess.Popen(
            ["x11vnc", "-display", display, "-forever", "-shared", "-nopw",
             "-rfbport", str(VNC_PORT), "-quiet", "-noxdamage"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if _ws_proc is None or _ws_proc.poll() is not None:
        _ws_proc = subprocess.Popen(
            ["websockify", "--web", NOVNC_WEB, str(NOVNC_PORT), f"localhost:{VNC_PORT}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)


def stop_viewer() -> None:
    for p in (_ws_proc, _vnc_proc):
        if p is not None:
            p.terminate()


async def await_human_solve(tab, is_solved, display: str, max_wait: float = 180.0) -> bool:
    """Expose `display` over noVNC, signal the caller that a human solve is needed, then poll
    `is_solved(html)` until it returns True or `max_wait` elapses. Returns True if solved."""
    start_viewer(display)
    ctrl("CAPTCHA_INTERACTIVE")
    waited = 0.0
    while waited < max_wait:
        await asyncio.sleep(3)
        waited += 3
        try:
            html = await tab.get_content()
        except Exception:
            continue
        if is_solved(html):
            ctrl("CAPTCHA_SOLVED")
            return True
    ctrl("CAPTCHA_TIMEOUT")
    return False
