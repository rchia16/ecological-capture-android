import {test} from 'node:test';import assert from 'node:assert/strict';
import {filtered,csv} from '../src/catalogue.js';
const rows=[{study_id:'s',participant_code:'P014',recorded_at_epoch_ms:Date.UTC(2026,9,19),event_id:'event',snapshot_id:'a',status:'VERIFIED_GB10',gb10_path:'s/P014/path',archived:false},
{study_id:'s',participant_code:'P015',recorded_at_epoch_ms:Date.UTC(2026,9,20),event_id:'event2',snapshot_id:'b',status:'AWAITING_GB10'}];
test('combined participant, receipt and inclusive UTC dates',()=>{assert.deepEqual(filtered(rows,{participant:'p014',status:'VERIFIED_GB10',from:'2026-10-19',to:'2026-10-19'}),[rows[0]]);assert.equal(filtered(rows,{to:'2026-10-18'}).length,0);});
test('CSV preserves fields and prevents spreadsheet formula execution',()=>{const output=csv([{...rows[0],participant_code:'=CMD()',gb10_path:'a,"b"'}]);assert.ok(output.includes('"\'=CMD()"'));assert.ok(output.includes('"a,""b"""'));assert.ok(output.endsWith('"false"'));});
