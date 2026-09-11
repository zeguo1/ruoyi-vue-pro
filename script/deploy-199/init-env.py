#!/usr/bin/env python3
"""Create deployment credentials once; never overwrite an existing environment."""
import os
import secrets
from pathlib import Path

target = Path(__file__).with_name('.env')
values = {key: secrets.token_hex(20) for key in (
    'MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'REDIS_PASSWORD', 'IOT_TOKEN_SECRET'
)}
# TDengine limits passwords to 8–30 characters.
values['TDENGINE_PASSWORD'] = secrets.token_hex(10) + 'Aa1!'
with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
    stream.write(''.join(f'{key}={value}\n' for key, value in values.items()))
print(f'Created {target}; keep this file private and back it up.')
