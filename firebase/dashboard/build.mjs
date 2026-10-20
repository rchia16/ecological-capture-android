import {build} from 'esbuild';
import {readFile,mkdir,copyFile} from 'node:fs/promises';
const config=JSON.parse(await readFile(new URL('./config.local.json',import.meta.url),'utf8'));
for(const key of ['apiKey','authDomain','projectId','appId']) {
  if(typeof config[key]!=='string'||!config[key]||/CONFIGURE|PROJECT_ID/.test(config[key])) throw new Error(`Configure ${key}`);
}
await mkdir('dist',{recursive:true});
await build({entryPoints:['src/app.js'],bundle:true,outfile:'dist/app.js',format:'esm',minify:true,
  define:{FIREBASE_CONFIG:JSON.stringify(config)},sourcemap:false});
for(const file of ['index.html','style.css']) await copyFile(`src/${file}`,`dist/${file}`);
