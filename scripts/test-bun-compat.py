"""Linux host regression: reproduce Android's seccomp TRAP without a phone."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile

scripts = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="pocket-bun-compat-") as directory:
    library = str(Path(directory) / "libpocket_bun_compat.so")
    probe = str(Path(directory) / "probe")
    subprocess.run(["cc", "-O2", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror", str(scripts / "bun-compat.c"), str(scripts / "bun-compat.S"), "-o", library], check=True)
    subprocess.run(["cc", "-O2", "-Wall", "-Wextra", "-Werror", str(scripts / "test-bun-compat.c"), "-o", probe], check=True)
    baseline = subprocess.run([probe], env={k: v for k, v in os.environ.items() if k != "LD_PRELOAD"})
    assert baseline.returncode == -signal.SIGSYS, baseline.returncode
    print("PASS: unmodified process reproduces SIGSYS", flush=True)
    subprocess.run([probe], env={**os.environ, "LD_PRELOAD": library}, check=True)
