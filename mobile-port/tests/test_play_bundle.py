"""Reject native binaries that would break on Google Play 16 KB page devices."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import pytest

spec=importlib.util.spec_from_file_location('play_bundle',Path(__file__).resolve().parents[1]/'tools/build_play_bundle.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def elf(alignment,offset=0,virtual=0):
    data=bytearray(120);data[:6]=b'\x7fELF\x02\x01'
    struct.pack_into('<Q',data,32,64);struct.pack_into('<HH',data,54,56,1)
    struct.pack_into('<IIQQQQQQ',data,64,1,5,offset,virtual,0,0,0,alignment)
    return bytes(data)

def test_accepts_congruent_16k_load_segments():
    assert module.elf_load_alignments(elf(16384,4096,20480))==[16384]
    assert module.elf_load_alignments(elf(65536))==[65536]

@pytest.mark.parametrize('data',[elf(4096),elf(16384,0,4096),elf(24576),b'not elf',elf(16384)[:90]])
def test_rejects_4k_or_invalid_native_binaries(data):
    with pytest.raises(ValueError): module.elf_load_alignments(data)

def test_free_bundle_allows_camera_and_microphone_without_store_permissions():
    manifest=ET.fromstring('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <uses-permission android:name="android.permission.CAMERA"/>
        <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    </manifest>''')
    module.verify_free_manifest(manifest)

@pytest.mark.parametrize('permission',[
    'android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE','com.android.vending.BILLING',
])
@pytest.mark.parametrize('tag',['uses-permission','uses-permission-sdk-23'])
def test_free_bundle_rejects_network_and_billing_permissions(permission,tag):
    manifest=ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <{tag} android:name="{permission}"/>
    </manifest>''')
    with pytest.raises(ValueError,match='billing/network'):
        module.verify_free_manifest(manifest)
