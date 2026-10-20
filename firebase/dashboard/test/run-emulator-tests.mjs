import {spawnSync} from 'node:child_process';
for(const [command,args] of [
  [process.execPath,['--test','test/rules.emulator.mjs']],
  [process.env.ECO_TEST_PYTHON ?? (process.platform==='win32'?'../functions/.venv/Scripts/python.exe':'../functions/.venv/bin/python'),['-B','-m','unittest','discover','-s','../tests','-p','test_cloud_emulator.py','-v']]
]){
  const result=spawnSync(command,args,{stdio:'inherit',env:process.env});
  if(result.status!==0)process.exit(result.status??1);
}
