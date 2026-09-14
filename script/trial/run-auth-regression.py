#!/usr/bin/env python3
"""Existing MGS login/OAuth regression using H2 and the project's embedded Redis substitute."""
import os
import pathlib
import socket
import subprocess

repo = pathlib.Path(__file__).resolve().parents[2]
with socket.socket() as reservation:
    reservation.bind(("127.0.0.1", 0))
    trial_redis_port = reservation.getsockname()[1]
# These tests start their own embedded Redis on the selected free local port.
# Never use the production Redis address/password or restart shared services.
command = [
    "mvn", "-q", "-pl", "yudao-module-system", "-am", "test",
    "-Dtest=OAuth2TokenServiceImplTest,AdminAuthServiceImplTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-DargLine=-Xmx384m -XX:ActiveProcessorCount=2",
    f"-Dspring.data.redis.port={trial_redis_port}",
]
raise SystemExit(subprocess.run(command, cwd=repo, env=dict(os.environ, MAVEN_OPTS="-Xmx512m -XX:ActiveProcessorCount=2")).returncode)
