"""Import the exact user-supplied Arc revision and retain Flow release identity.

The archived sources identify ccfd1d7b5ad681bde4615e5bae82a4e7d710a34d.
CI fetches that immutable revision rather than copying a moving upstream branch.
"""
from pathlib import Path
import hashlib,json,re,shutil,subprocess,tarfile,tempfile

ARC_COMMIT='ccfd1d7b5ad681bde4615e5bae82a4e7d710a34d'
ROOT=Path(__file__).resolve().parents[1]

def main():
 with tempfile.TemporaryDirectory() as temporary:
  temp=Path(temporary); repo=temp/'repo';repo.mkdir()
  subprocess.run(['git','init',str(repo)],check=True,capture_output=True)
  subprocess.run(['git','-C',str(repo),'fetch','--depth=1','https://github.com/ysosrs123/NuvioTV-Fork.git',ARC_COMMIT],check=True)
  archive=temp/'arc.tar'
  with archive.open('wb') as output:
   subprocess.run(['git','-C',str(repo),'archive',ARC_COMMIT],stdout=output,check=True)
  arc=temp/'source';arc.mkdir()
  with tarfile.open(archive) as bundle: bundle.extractall(arc,filter='data')
  brand=temp/'brand';brand.mkdir()
  for p in (ROOT/'app/src/main/res').glob('mipmap*'):
   shutil.copytree(p,brand/p.name)
  for rel in ['app/src','app/libs','DV7','ffmpeg-decoder-downmix','baselineprofile','build.gradle.kts','gradle/libs.versions.toml','app/proguard-rules.pro','compose_stability_config.conf']:
   src=arc/rel;dest=ROOT/rel
   if dest.is_dir(): shutil.rmtree(dest)
   if src.is_dir(): shutil.copytree(src,dest)
   else: shutil.copyfile(src,dest)
  for d in brand.iterdir():
   destination=ROOT/'app/src/main/res'/d.name
   destination.mkdir(parents=True,exist_ok=True)
   for icon in d.iterdir():
    if icon.is_file():
     for existing in destination.glob(icon.stem+'.*'): existing.unlink()
   shutil.copytree(d,destination,dirs_exist_ok=True)
  s=(arc/'app/build.gradle.kts').read_text()
  s=s.replace('applicationId = "com.nuvio.tv.test"','applicationId = "com.jeffrey.nuvioflow"')
  s=s.replace('versionCode = 1568','versionCode = 2011').replace('versionName = "1.1.0-nt.9.2"','versionName = "1.1.0-beta.9.flow.13"')
  s=s.replace('"\\\"ysosrs123\\\""','"\\\"LaniBoat14\\\""').replace('"\\\"NuvioTV-Fork\\\""','"\\\"nuvio-flow\\\""').replace('"\\\"Nuvio-Arc\\\""','"\\\"nuvio-flow\\\""')
  s=s.replace('debug {\n            signingConfig','debug {\n            applicationIdSuffix = ".debug"\n            signingConfig')
  s=s.replace('release {\n            isMinifyEnabled','release {\n            applicationIdSuffix = ".debug"\n            isMinifyEnabled')
  s=s.replace('include("armeabi-v7a", "arm64-v8a")','include("armeabi-v7a")')
  for key,default in [('ALIAS','nuviotv'),('PASSWORD','815787')]:
   s=s.replace(f'localProperties.getProperty("NUVIO_RELEASE_KEY_{key}", "{default}")',f'localProperties.getProperty("NUVIO_RELEASE_KEY_{key}")')
  s=s.replace('localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD", "815787")','localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD")')
  s=s.replace('?: file("../nuviotv.jks")','?: error("Flow signing keystore must be supplied by CI")')
  (ROOT/'app/build.gradle.kts').write_text(s)
  for p in (ROOT/'app/src/main/res').glob('values*/strings.xml'):
   text=p.read_text();text=re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)',r'\1Nuvio Flow\2',text);p.write_text(text)
  # Certificates are public and were present in the validated Flow baseline.
  for name in ['isrgrootx1.pem','isrg_root_x2.pem']:
   dest=ROOT/'app/src/main/res/raw'/name
   if not dest.exists(): dest.write_bytes(subprocess.check_output(['git','show','HEAD:app/src/main/res/raw/'+name],cwd=ROOT))
  regression=ROOT/'app/src/test/java/com/nuvio/tv/updater/FlowUpdateVersionTest.kt'
  regression.write_bytes(subprocess.check_output(['git','show','HEAD:app/src/test/java/com/nuvio/tv/updater/FlowUpdateVersionTest.kt'],cwd=ROOT))
  # Updater tests must exercise Flow's configured feed, including rejection of look-alike URLs.
  updater_test=ROOT/'app/src/testFull/java/com/nuvio/tv/updater/ForkUpdateRepositoryTest.kt'
  text=updater_test.read_text()
  text=text.replace('"ysosrs123", "NuvioTV-Fork"', 'BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO')
  text=text.replace('ysosrs123/NuvioTV-Fork', '${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}')
  text=text.replace('ysosrs123/Nuvio-Arc', '${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO_NEW_NAME}')
  updater_test.write_text(text)
  # Synchronize the intended simultaneous server refusals. Without this barrier,
  # an early 429 can close the gate before the other two requests even start.
  workload=ROOT/'app/src/test/java/com/nuvio/tv/ui/screens/player/ParallelRangeProductionWorkloadTest.kt'
  text=workload.read_text()
  old='            f.beforeWorkerOpen={ _,attempt,spec ->\n                if(attempt==1) throw HttpDataSource.InvalidResponseCodeException(\n                    429,"fixture limiter",null,emptyMap(),spec,byteArrayOf())\n            }'
  new='            val firstBurst=CountDownLatch(3)\n            f.beforeWorkerOpen={ _,attempt,spec ->\n                if(attempt==1) {\n                    firstBurst.countDown()\n                    check(firstBurst.await(10,TimeUnit.SECONDS)) { "All three initial requests must reach the server before any refusal" }\n                    throw HttpDataSource.InvalidResponseCodeException(\n                        429,"fixture limiter",null,emptyMap(),spec,byteArrayOf())\n                }\n            }'
  assert text.count(old)==1, 'Arc workload test changed; inspect before adapting'
  workload.write_text(text.replace(old,new))
  shutil.copyfile(arc/'scripts/verify_native_apk.py',ROOT/'scripts/verify_native_apk.py')
  # The supplied libass AAR statically includes libass inside libasskt.so.
  verifier=ROOT/'scripts/verify_native_apk.py'
  check=verifier.read_text()
  check=check.replace('"libass.so", ', '')
  check=check.replace('"libasskt.so"}', '"libasskt.so", "libaml_fel.so", "libnuviothumb.so"}')
  anchor='        bridge = archive.read(f"lib/{abi}/libdovi_bridge.so")'
  subtitle_check='        subtitles = archive.read(f"lib/{abi}/libasskt.so")\n        for symbol in (b"ass_library_init\\0", b"ass_renderer_init\\0", b"ass_read_memory\\0"):\n            if symbol not in subtitles:\n                raise ValueError("Static libass implementation missing from libasskt.so")\n'
  assert anchor in check
  verifier.write_text(check.replace(anchor, subtitle_check+anchor))
  subprocess.run(['python3','scripts/verify_arc_runtime.py'],cwd=ROOT,check=True)
  print('Imported original Arc runtime:',ARC_COMMIT)

if __name__=='__main__':main()
