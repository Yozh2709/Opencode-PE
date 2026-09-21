// Add the pinned Python payload to an already prepared base runtime.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const lock=JSON.parse(fs.readFileSync('runtime.lock.json','utf8'));
const cache=path.resolve(process.argv[2] || '.runtime-cache','python');
const payload=path.join(cache,'payload');
const assets='app/src/main/assets/runtime';
const out='app/src/main/jniLibs/arm64-v8a';
fs.mkdirSync(payload,{recursive:true});fs.mkdirSync(out,{recursive:true});
for(const pkg of lock.pythonPackages) {
  const deb=path.join(cache,pkg.name+'-'+pkg.version+'.deb');
  if(!fs.existsSync(deb))execFileSync(process.env.POCKET_CURL || 'curl.exe',['-4','-fsSL','--retry','3','-o',deb,pkg.url],{stdio:'inherit'});
  if(crypto.createHash('sha256').update(fs.readFileSync(deb)).digest('hex')!==pkg.sha256)throw Error('Checksum: '+pkg.name);
  const ar=path.join(cache,pkg.name);fs.mkdirSync(ar,{recursive:true});
  execFileSync('tar',['-xf',deb,'-C',ar]);
  const archive=path.join(ar,fs.readdirSync(ar).find(f=>f.startsWith('data.tar')));
  const regular=execFileSync('tar',['-tvf',archive],{encoding:'utf8',maxBuffer:32*1024*1024}).split('\n').filter(l=>l.startsWith('-')).map(l=>l.slice(l.indexOf('./'))).filter(l=>l.startsWith('./'));
  const list=path.join(ar,'regular.txt');fs.writeFileSync(list,regular.join('\n'));
  execFileSync('tar',['-xf',archive,'-C',payload,'-T',list],{maxBuffer:32*1024*1024});
  console.log('Python payload:',pkg.name,pkg.version);
}
const mapping=JSON.parse(fs.readFileSync(path.join(assets,'executables.json'),'utf8'));
const usr=path.join(payload,'data/data/com.termux/files/usr');
function files(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);}
for(const file of files(usr)) {
  const rel=path.relative(usr,file).replaceAll('\\','/');
  const bytes=fs.readFileSync(file);
  if(bytes.subarray(0,4).equals(Buffer.from([127,69,76,70]))) {
    const name=path.basename(file);
    const dest=rel.startsWith('lib/') && /^lib.*\.so(?:\..*)?$/.test(name)?name.replace(/\.so\..*/,'.so'):'libtool_'+rel.replaceAll('/','_').replaceAll('.','_')+'.so';
    fs.copyFileSync(file,path.join(out,dest));mapping[rel]=dest;
  } else if(rel.startsWith('lib/python3.14/') && !rel.endsWith('.pyc')) {
    const dest=path.join(assets,'usr',rel);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(file,dest);
  }
}
mapping['bin/python']=mapping['bin/python3']=mapping['bin/python3.14'];
if(!mapping['bin/python'])throw Error('Python binary missing');
fs.writeFileSync(path.join(assets,'executables.json'),JSON.stringify(mapping,null,2));
const sourceFile=path.join(assets,'sources.json');
const sources=JSON.parse(fs.readFileSync(sourceFile,'utf8'));sources.pythonPackages=lock.pythonPackages;
fs.writeFileSync(sourceFile,JSON.stringify(sources,null,2));
