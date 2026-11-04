"""把 classes.dex 塞进 aapt2 产出的 apk，再交给 zipalign。"""
import os, sys, zipfile

out = sys.argv[1] if len(sys.argv) > 1 else 'build'
base = os.path.join(out, 'base.apk')
dex_path = os.path.join(out, 'dex', 'classes.dex')
unsigned = os.path.join(out, 'unsigned.apk')
aligned = os.path.join(out, 'aligned.apk')

with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex_path, 'classes.dex')

import subprocess
BT = r'C:/Users/Administrator/android-sdk/build-tools/34.0.0/zipalign.exe'
subprocess.check_call([BT, '-f', '4', unsigned, aligned])
print('packed+aligned', aligned, os.path.getsize(aligned), 'bytes')
