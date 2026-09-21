// Recover the JavaScript module graph from the pinned official OpenCode release.
// Run the relocated modules with official Bun Android.
import fs from 'node:fs';
import path from 'node:path';
import {readBunGraph} from './bun-graph.mjs';
const [binary,destination,installRoot]=process.argv.slice(2);
const {modules,recordSize,entry}=readBunGraph(binary);
if(entry.relative!=='src/index.js')throw Error('Unexpected OpenCode entry point: '+entry.relative);
fs.mkdirSync(destination,{recursive:true});
const names=modules.map(({name,relative})=>[name,relative]);
let wasmWrapperImports=0;
for(const module of modules){
 const {name,relative}=module;
 const dest=path.resolve(destination,relative);
 if(!dest.startsWith(path.resolve(destination)+path.sep))throw Error('Invalid path');
 fs.mkdirSync(path.dirname(dest),{recursive:true});
 let content=module.content;
 if(name.endsWith('.js')){
   let text=content.toString();
   // Standalone Bun stores asset imports as JS wrapper modules. In normal Bun,
   // retaining type:"wasm" makes the loader return the wrapper's *filename*,
   // not its exported WASM path. Tree-sitter then tries to compile JS as WASM.
   text=text.replace(/\bimport\(("[^"]+\.js"),\{with:\{type:"wasm"\}\}\)/g,(_,module)=>{
     wasmWrapperImports++;return `import(${module})`;
   });
   for(const [original,relative] of names)text=text.replaceAll(original,(installRoot||'__POCKET_CODE_ROOT__')+'/'+relative);
   text=text.replaceAll('/$bunfs/root/',(installRoot||'__POCKET_CODE_ROOT__')+'/');
   content=Buffer.from(text);
 }
 fs.writeFileSync(dest,content);
}
console.log('Extracted',modules.length,'modules; record size:',recordSize);
if(wasmWrapperImports<3)throw Error('Expected tree-sitter WASM wrapper imports were not found');
console.log('Restored',wasmWrapperImports,'WASM wrapper imports');
