"""Build and verify a signed production AAB, without exposing signing passwords."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BUNDLETOOL_SHA256='a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
ANDROID='{http://schemas.android.com/apk/res/android}'

def elf_load_alignments(data: bytes) -> list[int]:
    if len(data)<64 or data[:6]!=b'\x7fELF\x02\x01':
        raise ValueError('Expected a 64-bit little-endian native library')
    offset=struct.unpack_from('<Q',data,32)[0]
    size,count=struct.unpack_from('<HH',data,54)
    if size<56 or offset+size*count>len(data): raise ValueError('Invalid ELF program-header table')
    result=[]
    for i in range(count):
        kind,_,file_offset,virtual,_,_,_,alignment=struct.unpack_from('<IIQQQQQQ',data,offset+i*size)
        if kind==1:
            if alignment<16384 or alignment&(alignment-1) or file_offset%16384!=virtual%16384:
                raise ValueError('Native LOAD segment does not support 16 KB pages')
            result.append(alignment)
    if not result: raise ValueError('ELF contains no LOAD segments')
    return result

def capture(args: list[str]) -> str:
    return subprocess.run(args,check=True,capture_output=True,text=True).stdout

def certificate(data: str) -> bytes:
    match=re.search(r'-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----',data,re.S)
    if not match: raise ValueError('Missing upload certificate')
    return base64.b64decode(''.join(match[1].split()),validate=True)

def verify(bundle: Path, bundletool: Path, java_home: Path, key: str, expected_certificate: Path) -> dict:
    if hashlib.sha256(bundletool.read_bytes()).hexdigest()!=BUNDLETOOL_SHA256:
        raise ValueError('Use the SHA-256-pinned official bundletool-all-1.18.3.jar')
    java=str(java_home/'bin/java')
    capture([java,'-jar',str(bundletool),'validate','--bundle='+str(bundle)])
    manifest=ET.fromstring(capture([java,'-jar',str(bundletool),'dump','manifest','--bundle='+str(bundle),'--module=base']))
    if manifest.get('package')!='com.ajc.shottracer': raise ValueError('Wrong production package')
    sdk=manifest.find('uses-sdk'); app=manifest.find('application')
    if sdk is None or int(sdk.get(ANDROID+'targetSdkVersion','0'))<36: raise ValueError('API 36 target required')
    if app is None or app.get(ANDROID+'debuggable','false')!='false' or app.get(ANDROID+'testOnly','false')!='false':
        raise ValueError('Debug/test-only builds cannot be a production upload')
    config=json.loads(capture([java,'-jar',str(bundletool),'dump','config','--bundle='+str(bundle)]))
    if 'PAGE_ALIGNMENT_16K' not in json.dumps(config): raise ValueError('Bundle must request 16 KB APK alignment')
    signed=capture([str(java_home/'bin/jarsigner'),'-J-Duser.language=en','-J-Duser.country=US','-verify',str(bundle)])
    if 'jar verified.' not in signed: raise ValueError('AAB JAR signature verification failed')
    cert_text=capture([str(java_home/'bin/keytool'),'-printcert','-rfc','-jarfile',str(bundle)])
    actual=hashlib.sha256(certificate(cert_text)).hexdigest()
    expected=hashlib.sha256(certificate(expected_certificate.read_text())).hexdigest()
    if actual!=expected: raise ValueError('AAB was not signed with the configured upload certificate')
    native={}
    with zipfile.ZipFile(bundle) as archive:
        names=archive.namelist()
        if not any(n.startswith('META-INF/') and n.endswith('.SF') for n in names): raise ValueError('Unsigned bundle')
        for name in names:
            if name.startswith('base/lib/') and name.endswith('.so'):
                if name.split('/')[2] not in ('arm64-v8a','x86_64'): raise ValueError('Unexpected native ABI')
                native[name]=min(elf_load_alignments(archive.read(name)))
        if not native: raise ValueError('Missing native tracking libraries')
        if not any(key.encode() in archive.read(n) for n in names if n.startswith('base/dex/') and n.endswith('.dex')):
            raise ValueError('Configured Play licensing public key missing from DEX')
        pose=archive.read('base/assets/pose_landmarker_full.task')
        if hashlib.sha256(pose).hexdigest()!='5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1':
            raise ValueError('Bundled pose model checksum mismatch')
    return {'package':manifest.get('package'),'version_name':manifest.get(ANDROID+'versionName'),
            'version_code':int(manifest.get(ANDROID+'versionCode','0')),'target_sdk':int(sdk.get(ANDROID+'targetSdkVersion','0')),
            'min_sdk':int(sdk.get(ANDROID+'minSdkVersion','0')),'upload_certificate_sha256':actual,
            'sha256':hashlib.sha256(bundle.read_bytes()).hexdigest(),'native_load_alignment':native,
            'signature_verified':True,'bundletool_validated':True,'billing_key_embedded':True}

def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle',default=str(ROOT/'app/android/gradlew'))
    parser.add_argument('--bundletool',type=Path,required=True)
    parser.add_argument('--java-home',type=Path,default=os.environ.get('JAVA_HOME'))
    args=parser.parse_args()
    if args.java_home is None: parser.error('JAVA_HOME or --java-home is required')
    key_file=ROOT/'private/play-billing-public-key.txt'
    if not key_file.is_file(): parser.error('Save the real Play Console licensing RSA public key in mobile-port/private/play-billing-public-key.txt; a placeholder key is not accepted.')
    key=''.join(key_file.read_text().split())
    if not key: parser.error('Play licensing public key is empty')
    signing=ROOT/'private/android-upload.properties'; cert=ROOT/'private/android-upload-certificate.pem'
    if not signing.is_file() or not cert.is_file(): parser.error('Configure the upload signing files first')
    env=dict(os.environ,JAVA_HOME=str(args.java_home))
    subprocess.run([args.gradle,'-p',str(ROOT/'app/android'),':app:bundleRelease',':app:testDebugUnitTest',':app:lintRelease','--no-daemon'],env=env,check=True)
    bundle=ROOT/'app/android/app/build/outputs/bundle/release/app-release.aab'
    report=verify(bundle,args.bundletool.resolve(),args.java_home.resolve(),key,cert)
    output=ROOT/'release';output.mkdir(exist_ok=True)
    destination=output/f'ShotTracer-{report["version_name"]}-play.aab'
    shutil.copy2(bundle,destination)
    destination.with_suffix('.aab.sha256').write_text(report['sha256']+'  '+destination.name+'\n')
    destination.with_suffix('.verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Verified signed AAB: '+str(destination))
    print('Production package: '+report['package'])

if __name__=='__main__': main()
