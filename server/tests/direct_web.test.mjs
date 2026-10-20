import {test} from 'node:test';
import assert from 'node:assert/strict';
import {groups,csv} from '../ecocapture_direct/web/catalogue.js';
const original={study_id:'study',participant_code:'P014',event_id:'event',snapshot_id:'a',registered_at:1,recorded_at_epoch_ms:1000,status:'VERIFIED_GB10',review_status:'TO_REVIEW',tags:['Outdoor'],notes:'',archived:false};
test('event grouping preserves versions and sorts newest first',()=>{
  const result=groups([original,{...original,snapshot_id:'b',registered_at:2},{...original,event_id:'other',snapshot_id:'c'}]);
  assert.equal(result.length,2);assert.deepEqual(result[0].map(r=>r.snapshot_id),['b','a']);
});
test('CSV includes UTC date, tags, notes and prevents spreadsheet formulas',()=>{
  const output=csv([{...original,notes:'=CMD()',tags:['a','b'],archive_label:'Share "one"'}]);
  assert.ok(output.includes('1970-01-01T00:00:01.000Z'));assert.ok(output.includes('"a; b"'));
  assert.ok(output.includes('"\'=CMD()"'));assert.ok(output.includes('"Share ""one"""'));
});
