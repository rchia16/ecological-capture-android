export const labels={UPLOADING:'Uploading',AWAITING_GB10:'Awaiting GB10',VERIFIED_GB10:'Verified on GB10',NEEDS_ATTENTION:'Needs attention'};
export function filtered(rows,{participant='',status='',from='',to=''}) {
  return rows.filter(row=> {
    const date=new Date(row.recorded_at_epoch_ms).toISOString().slice(0,10);
    return (!participant||row.participant_code.toLowerCase().includes(participant.toLowerCase()))&&
      (!status||row.status===status)&&(!from||date>=from)&&(!to||date<=to);
  }).sort((a,b)=>b.recorded_at_epoch_ms-a.recorded_at_epoch_ms||a.snapshot_id.localeCompare(b.snapshot_id));
}
export function csv(rows) {
  const columns=['study_id','participant_code','recorded_at_utc','event_id','snapshot_id','status','gb10_path','archived'];
  const cell=value=> {
    let text=String(value??'');
    if(/^[=+\-@\t\r\n]/.test(text)) text="'"+text;
    return '"'+text.replaceAll('"','""')+'"';
  };
  return [columns,...rows.map(row=>columns.map(c=>c==='recorded_at_utc'?new Date(row.recorded_at_epoch_ms).toISOString():row[c]))].map(row=>row.map(cell).join(',')).join('\r\n');
}
