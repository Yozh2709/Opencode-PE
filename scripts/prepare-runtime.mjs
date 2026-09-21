// Run from repository root: node scripts/prepare-runtime.mjs [cache directory]
// Native payloads are installed by Android from lib/, never executed from downloaded writable files.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const lock=JSON.parse(fs.readFileSync('runtime.lock.json','utf8'));
const cache=path.resolve(process.argv[2] || '.runtime-cache');
fs.mkdirSync(cache,{recursive:true});
const out=path.resolve('app/src/main/jniLibs/arm64-v8a');
const assets=path.resolve('app/src/main/assets/runtime');
fs.mkdirSync(out,{recursive:true}); fs.mkdirSync(assets,{recursive:true});
async function download(url,file,hash){
  if(!fs.existsSync(file)) execFileSync(process.env.POCKET_CURL || 'curl.exe',['-fsSL','--retry','3','-o',file,url],{stdio:'inherit'});
  if(hash && crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')!==hash) throw Error('Checksum mismatch: '+file);
}
const zip=path.join(cache,'opencode.zip');
await download(lock.androidSupport.url,zip,lock.androidSupport.sha256);
const extracted=path.join(cache,'extracted');fs.mkdirSync(extracted,{recursive:true});
execFileSync('tar',['-xf',zip,'-C',extracted]);
const bunZip=path.join(cache,'bun-arm64.zip');
await download('https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64-android.zip',bunZip,'a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274');
execFileSync('tar',['-xf',bunZip,'-C',cache]);
fs.copyFileSync(path.join(cache,'bun-linux-aarch64-android/bun'),path.join(out,'libbun.so'));
const obsolete=path.join(out,'libopencode.so');if(fs.existsSync(obsolete))fs.unlinkSync(obsolete);
const coreArchive=path.join(cache,'opencode-'+lock.opencode.version+'.tar.gz');
await download(lock.opencode.url,coreArchive,lock.opencode.sha256);
const core=path.join(cache,'core-'+lock.opencode.version);fs.mkdirSync(core,{recursive:true});
execFileSync('tar',['-xf',coreArchive,'-C',core]);
execFileSync(process.execPath,['scripts/extract-opencode.mjs',path.join(core,'opencode'),path.join(assets,'code')],{stdio:'inherit'});
execFileSync(process.execPath,['scripts/extract-webui.mjs',path.join(core,'opencode'),'app/src/main/assets/web'],{stdio:'inherit'});
for(const name of ['libtagfix.so','libopentui.so','libc++_shared.so'])fs.copyFileSync(path.join(extracted,name),path.join(out,name));
const index=path.join(cache,'Packages');
await download('https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages',index);
const packages=new Map(fs.readFileSync(index,'utf8').split(/\n\n/).map(block=>{
  const fields=Object.fromEntries(block.split('\n').filter(l=>/^[A-Za-z0-9-]+: /.test(l)).map(l=>[l.slice(0,l.indexOf(':')),l.slice(l.indexOf(':')+2)]));
  return [fields.Package,fields];
}));
// Only libraries and executables needed for the supported toolchain, not package managers.
const wanted=new Map();
function add(name){if(wanted.has(name))return;const pkg=packages.get(name);if(!pkg)throw Error('Unknown package '+name);wanted.set(name,pkg);
 for(const dep of (pkg.Depends||'').split(',').map(d=>d.trim().split(/[ (|]/)[0]).filter(Boolean))add(dep);
}
for(const name of ['git','ripgrep','nodejs','npm','curl'])add(name);
if(fs.existsSync('runtime.lock.json')){
 const lock=JSON.parse(fs.readFileSync('runtime.lock.json','utf8'));
 wanted.clear();
 for(const pkg of lock.packages)wanted.set(pkg.name,{Version:pkg.version,Filename:pkg.url.replace('https://packages.termux.dev/apt/termux-main/',''),SHA256:pkg.sha256});
}
const mapping={'bin/bun':'libbun.so'}; const sources=[];
const payload=path.join(cache,'payload');fs.mkdirSync(payload,{recursive:true});
for(const [name,pkg] of wanted){
 console.log('Preparing',name,pkg.Version);
 const deb=path.join(cache,name+'.deb');
 await download('https://packages.termux.dev/apt/termux-main/'+pkg.Filename,deb,pkg.SHA256);
 const ar=path.join(cache,'ar-'+name);fs.mkdirSync(ar,{recursive:true});execFileSync('tar',['-xf',deb,'-C',ar]);
 const data=fs.readdirSync(ar).find(f=>f.startsWith('data.tar'));
 const archive=path.join(ar,data);
 const listing=execFileSync('tar',['-tvf',archive],{encoding:'utf8',maxBuffer:32*1024*1024});
 const regular=listing.split('\n').filter(l=>l.startsWith('-')).map(l=>l.slice(l.indexOf('./'))).filter(l=>l.startsWith('./'));
 const list=path.join(ar,'regular.txt');fs.writeFileSync(list,regular.join('\n'));
 execFileSync('tar',['-xf',archive,'-C',payload,'-T',list],{stdio:'pipe',maxBuffer:32*1024*1024});
 sources.push({name,version:pkg.Version,url:'https://packages.termux.dev/apt/termux-main/'+pkg.Filename,sha256:pkg.SHA256});
}
const usr=path.join(payload,'data/data/com.termux/files/usr');
function files(dir){return fs.existsSync(dir)?fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):e.isFile()?[path.join(dir,e.name)]:[]):[];}
for(const file of files(usr)){
 const rel=path.relative(usr,file).replaceAll('\\','/');
 const fd=fs.openSync(file,'r');const magic=Buffer.alloc(4);fs.readSync(fd,magic,0,4,0);fs.closeSync(fd);
 if(magic.equals(Buffer.from([127,69,76,70]))){
   const name=path.basename(file);
   const dest=rel.startsWith('lib/') && /^lib.*\.so(?:\..*)?$/.test(name)?name.replace(/\.so\..*/,'.so'):'libtool_'+rel.replaceAll('/','_').replaceAll('.','_')+'.so';
   if(dest==='libc++_shared.so')continue;
   fs.copyFileSync(file,path.join(out,dest));mapping[rel]=dest;
 }else if(rel.startsWith('lib/node_modules/') || rel.startsWith('share/git-core/templates/') || rel==='etc/tls/cert.pem'){
   const dest=path.join(assets,'usr',rel);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(file,dest);
 }
}
fs.writeFileSync(path.join(assets,'executables.json'),JSON.stringify(mapping,null,2));
fs.writeFileSync(path.join(assets,'sources.json'),JSON.stringify({opencode:lock.opencode,androidSupport:lock.androidSupport,bun:{version:'1.4.2',url:'https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-aarch64-android.zip',sha256:'a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274'},packages:sources},null,2));
console.log('Native runtime prepared:',Object.keys(mapping).length,'files');
execFileSync(process.execPath,['scripts/prepare-launchers.mjs'],{stdio:'inherit'});
execFileSync(process.execPath,['scripts/prepare-python.mjs',cache],{stdio:'inherit'});
