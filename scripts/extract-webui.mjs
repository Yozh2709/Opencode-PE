import fs from 'node:fs';
import path from 'node:path';
import {readBunGraph} from './bun-graph.mjs';

// Recover the unchanged GUI shipped in the exact same official release as the core.
// Parse the generated map as data; never evaluate code from the executable.
const [binary,destination]=process.argv.slice(2);
const {modules}=readBunGraph(binary);
const files=new Map(modules.map(m=>[m.name,m.content]));
const manifest=files.get('/$bunfs/root/opencode-web-ui.gen.js')?.toString();
if(!manifest)throw Error('The release has no embedded GUI manifest');
const vars=new Map([...manifest.matchAll(/\bvar ([\w$]+)="(\/\$bunfs\/root\/[^"\n]+)";/g)].map(m=>[m[1],m[2]]));
const entries=[...manifest.matchAll(/"([^"\n]+)":([\w$]+)/g)].map(m=>[m[1],vars.get(m[2])]);
if(entries.length<100||!entries.some(([name])=>name==='index.html'))throw Error('Unexpected GUI manifest');
const root=path.resolve(destination);
const seen=new Set();
for(const [name,source] of entries){
  const dest=path.resolve(root,name);
  if(!dest.startsWith(root+path.sep)||name.includes('\\')||seen.has(name)||!files.has(source))throw Error('Invalid GUI entry: '+name);
  seen.add(name);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,files.get(source));
}
console.log('Extracted unchanged GUI:',entries.length,'files');
