"""Reject native binaries that would break on Google Play 16 KB page devices."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import struct
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
