export const receiptLabels={RECEIVING:'Uploading',AWAITING_GB10:'Awaiting verification',VERIFIED_GB10:'Verified on GB10',NEEDS_ATTENTION:'Needs attention'};
export const reviewLabels={TO_REVIEW:'To review',IN_REVIEW:'In review',REVIEWED:'Reviewed'};
export function groups(rows){
  const grouped=new Map();
  for(const row of rows){if(!grouped.has(row.event_id))grouped.set(row.event_id,[]);grouped.get(row.event_id).push(row);}
  for(const versions of grouped.values())versions.sort((a,b)=>b.registered_at-a.registered_at||b.snapshot_id.localeCompare(a.snapshot_id));
  return [...grouped.values()].sort((a,b)=>b[0].recorded_at_epoch_ms-a[0].recorded_at_epoch_ms||a[0].event_id.localeCompare(b[0].event_id));
}
export function csv(rows){
  const columns=['study_id','participant_code','recorded_at_utc','event_id','snapshot_id','status','review_status','tags','notes','gb10_path','archived','archive_label'];
  const quote=value=>{let text=String(value??'');if(/^[=+\-@\t\r\n]/.test(text))text="'"+text;return '"'+text.replaceAll('"','""')+'"';};
  return [columns,...rows.map(row=>columns.map(key=>key==='recorded_at_utc'?new Date(row.recorded_at_epoch_ms).toISOString():key==='tags'?row.tags.join('; '):row[key]))]
    .map(row=>row.map(quote).join(',')).join('\r\n');
}
