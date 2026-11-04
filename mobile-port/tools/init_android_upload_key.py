"""Initialize the owner's upload key once; passwords never enter argv or stdout."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import secrets
import subprocess

def write_private(path: Path, text: str) -> None:
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as stream: stream.write(text)

def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--keytool',default='keytool')
    args=parser.parse_args()
    directory=args.directory.resolve(); directory.mkdir(parents=True,exist_ok=True)
    store=directory/'android-upload.p12'; config=directory/'android-upload.properties'
    if store.exists() or config.exists(): parser.error('Existing upload key/configuration found; refusing to replace it.')
    password=secrets.token_urlsafe(36)
    env=dict(os.environ,SHOTTRACER_UPLOAD_PASSWORD=password)
    old=os.umask(0o077)
    try:
        subprocess.run([args.keytool,'-genkeypair','-keystore',str(store),'-storetype','PKCS12',
            '-storepass:env','SHOTTRACER_UPLOAD_PASSWORD','-keypass:env','SHOTTRACER_UPLOAD_PASSWORD',
            '-alias','shottracer-upload','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA',
            '-validity','10000','-dname','CN=ShotTracer Upload','-noprompt'],env=env,check=True,capture_output=True)
        write_private(config,'storeFile=android-upload.p12\nstoreType=PKCS12\nkeyAlias=shottracer-upload\nstorePassword='+password+'\nkeyPassword='+password+'\n')
        subprocess.run([args.keytool,'-exportcert','-rfc','-keystore',str(store),'-storepass:env','SHOTTRACER_UPLOAD_PASSWORD',
            '-alias','shottracer-upload','-file',str(directory/'android-upload-certificate.pem')],env=env,check=True,capture_output=True)
    finally: os.umask(old)
    print('Upload key and private configuration created. Keep both files for future updates; no secret was printed.')

if __name__=='__main__': main()
