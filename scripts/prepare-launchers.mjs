import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
const ndk=process.env.ANDROID_NDK_HOME;
if(!ndk)throw Error('Set ANDROID_NDK_HOME to an Android NDK installation (tested: r29).');
const host=process.platform==='win32'?'windows-x86_64':process.platform==='darwin'?'darwin-x86_64':'linux-x86_64';
const clang=path.join(ndk,'toolchains/llvm/prebuilt',host,'bin',process.platform==='win32'?'clang.exe':'clang');
const out='app/src/main/jniLibs/arm64-v8a';fs.mkdirSync(out,{recursive:true});
for(const [mode,name] of ['opencode','npm','npx','pip'].entries()) {
 execFileSync(clang,['--target=aarch64-linux-android28','-O2','-fPIE','-pie','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384',`-DLAUNCH_MODE=${mode}`,'scripts/cli-launcher.c','-o',`${out}/libpocket_${name}.so`],{stdio:'inherit'});
}
