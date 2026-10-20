#!/usr/bin/env python3
"""Bound disposable Unix simulator tooling; never print command arguments."""
import os
import signal
import subprocess
import sys


def main():
    seconds = float(sys.argv[1])
    command = sys.argv[2:]
    if seconds <= 0 or not command:
        raise ValueError('Pass a positive timeout and command')
    process = subprocess.Popen(command, start_new_session=True)
    timed_out = False
    try:
        return process.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        print(f'{os.path.basename(command[0])}: exceeded {seconds:g}s limit', file=sys.stderr, flush=True)
        return 124
    finally:
        if timed_out or process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()


if __name__ == '__main__':
    sys.exit(main())
