"""Owner-only issuer for separate tester builds. Never ship the private key."""
from __future__ import annotations
import argparse, base64, json, os, subprocess, tempfile, time, uuid
from pathlib import Path

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip('=')

def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['init','issue'])
    parser.add_argument('--key',type=Path,required=True)
    parser.add_argument('--device')
    parser.add_argument('--platform',choices=['android','ios'])
    args=parser.parse_args()
    if args.action=='init':
        if args.key.exists(): parser.error('Key already exists; refusing to replace it.')
        args.key.parent.mkdir(parents=True,exist_ok=True)
        old=os.umask(0o077)
        try: subprocess.run(['openssl','ecparam','-name','prime256v1','-genkey','-noout','-out',str(args.key)],check=True)
        finally: os.umask(old)
        public=subprocess.check_output(['openssl','ec','-in',str(args.key),'-pubout','-outform','DER'],stderr=subprocess.DEVNULL)
        print(base64.b64encode(public).decode())
        return
    if not args.device or not args.platform: parser.error('--device and --platform are required')
    now=int(time.time())
    payload=json.dumps(dict(v=1,aud=args.platform,device=args.device,nonce=str(uuid.uuid4()),issued=now,redeem_before=now+7*86400,seconds=3*86400),sort_keys=True,separators=(',',':')).encode()
    with tempfile.TemporaryDirectory() as tmp:
        path=Path(tmp)/'payload'; path.write_bytes(payload)
        signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(args.key),str(path)])
    print('ST3.'+b64(payload)+'.'+b64(signature))

if __name__=='__main__': main()
