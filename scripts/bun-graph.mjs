import fs from 'node:fs';

/** Read the pinned Bun executable's module table without executing its code. */
export function readBunGraph(binary) {
  const b=fs.readFileSync(binary), trailer=b.lastIndexOf(Buffer.from('\n---- Bun! ----\n'));
  if(trailer<32)throw Error('No Bun module graph');
  const offsets=trailer-32, bytes=Number(b.readBigUInt64LE(offsets)), start=offsets-bytes;
  if(!Number.isSafeInteger(start)||start<0)throw Error('Invalid graph offset');
  const graph=b.subarray(start,offsets), modOff=b.readUInt32LE(offsets+8), modLen=b.readUInt32LE(offsets+12);
  // Bun 1.2: four StringPointers + flags. Bun 1.3: six StringPointers + flags.
  const layouts=[36,52].filter(size=>{
    if(modLen===0||modLen%size||modOff+modLen>graph.length)return false;
    for(let pos=modOff;pos<modOff+modLen;pos+=size){
      const off=graph.readUInt32LE(pos),len=graph.readUInt32LE(pos+4);
      const dataOff=graph.readUInt32LE(pos+8),dataLen=graph.readUInt32LE(pos+12);
      if(off+len>graph.length||dataOff+dataLen>graph.length||!graph.subarray(off,off+len).toString().startsWith('/$bunfs/root/'))return false;
    }
    return true;
  });
  if(layouts.length!==1)throw Error('Unsupported or ambiguous module layout');
  const recordSize=layouts[0], modules=[];
  for(let pos=modOff;pos<modOff+modLen;pos+=recordSize){
    const slice=n=>graph.subarray(graph.readUInt32LE(pos+n),graph.readUInt32LE(pos+n)+graph.readUInt32LE(pos+n+4));
    const name=slice(0).toString();
    // Windows/Gradle cannot address a path component ending in a dot.
    const relative=name.slice('/$bunfs/root/'.length).replaceAll('..','_parent').replace(/\.(?=\/|$)/g,'_dot');
    modules.push({name,relative,content:slice(8)});
  }
  const entry=modules[b.readUInt32LE(offsets+16)];
  if(!entry)throw Error('Invalid entry point');
  return {modules,recordSize,entry};
}
