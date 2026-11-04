"""Exercise the actual offline issuer with ephemeral keys, never owner credentials."""
from __future__ import annotations
import base64
import json
from pathlib import Path
import shutil
import subprocess
import sys
import pytest

SCRIPT=Path(__file__).resolve().parents[1]/'tools/issue_access_codes.py'
pytestmark=pytest.mark.skipif(not shutil.which('openssl'),reason='issuer requires OpenSSL')

def run(*args):
    return subprocess.run([sys.executable,str(SCRIPT),*map(str,args)],capture_output=True,text=True,check=True).stdout.strip()

def decode(text):
    return base64.urlsafe_b64decode(text+'='*((4-len(text)%4)%4))

def test_owner_issuer_signs_device_bound_three_day_codes(tmp_path):
    key=tmp_path/'private.pem';public=run('init','--key',key)
    parts=run('issue','--key',key,'--device','TEST-1','--platform','android').split('.')
    assert parts[0]=='ST3'
    data=decode(parts[1]);p=json.loads(data)
    assert p['device']=='TEST-1' and p['aud']=='android' and p['seconds']==259200
    assert p['redeem_before']-p['issued']==7*86400
    (tmp_path/'public.der').write_bytes(base64.b64decode(public))
    (tmp_path/'payload').write_bytes(data)
    (tmp_path/'signature').write_bytes(decode(parts[2]))
    subprocess.run(['openssl','pkey','-pubin','-inform','DER','-in',str(tmp_path/'public.der'),'-out',str(tmp_path/'public.pem')],check=True,capture_output=True)
    subprocess.run(['openssl','dgst','-sha256','-verify',str(tmp_path/'public.pem'),'-signature',str(tmp_path/'signature'),str(tmp_path/'payload')],check=True,capture_output=True)

def test_repeated_issues_have_unique_redemption_ids(tmp_path):
    key=tmp_path/'private.pem';run('init','--key',key)
    codes=[run('issue','--key',key,'--device','TEST-1','--platform','ios') for _ in range(2)]
    a,b=[json.loads(decode(code.split('.')[1])) for code in codes]
    assert a['nonce']!=b['nonce']
    assert a['aud']==b['aud']=='ios'

def test_initialization_never_overwrites_an_existing_issuer(tmp_path):
    key=tmp_path/'private.pem';run('init','--key',key)
    original=key.read_bytes()
    with pytest.raises(subprocess.CalledProcessError): run('init','--key',key)
    assert key.read_bytes()==original
