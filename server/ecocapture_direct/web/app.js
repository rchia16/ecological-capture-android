import {groups,csv,receiptLabels,reviewLabels} from './catalogue.js';
const $=id=>document.getElementById(id);
let rows=[],editing=null,busy=false;
function message(text){$('message').textContent=text;}
async function api(path,{method='GET',body}={}){
  const response=await fetch('/research/api'+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined,cache:'no-store',credentials:'same-origin'});
  if(!response.ok){const error=new Error(response.status===409?'Another researcher changed this record. Refresh before editing again.':response.status===403?'Researcher access is unavailable. Check Tailscale and your study access.':'The request could not complete. Try again.');error.status=response.status;throw error;}
  return response.json();
}
function element(tag,text,className){const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(className)node.className=className;return node;}
function render(){
  const grouped=groups(rows);$('count').textContent=`${grouped.length} events · ${rows.length} snapshots`;
  const fragment=document.createDocumentFragment();
  for(const versions of grouped){
    const latest=versions[0],card=element('article',undefined,'event');
    card.append(element('h2',`${latest.participant_code} · ${new Date(latest.recorded_at_epoch_ms).toISOString().replace('T',' ').slice(0,19)} UTC`));
    card.append(element('p',`Event ${latest.event_id}`,'identifier'));
    const chips=element('div',undefined,'tags');for(const tag of latest.tags)chips.append(element('span',tag,'tag'));card.append(chips);
    if(latest.notes)card.append(element('p',latest.notes,'notes'));
    const organise=element('button','Edit shared tags and notes');organise.addEventListener('click',()=>openEditor(latest));card.append(organise);
    for(const row of versions){
      const version=element('section',undefined,'snapshot');version.append(element('h3',`Snapshot ${row.snapshot_id}`,'identifier'));
      version.append(element('p',`${receiptLabels[row.status]??row.status} · ${row.archived?'Archived: '+row.archive_label:'Not archived'}`));
      if(row.gb10_path)version.append(element('p',row.gb10_path,'identifier'));
      const controls=element('div',undefined,'toolbar');
      const label=element('label','Researcher review');const select=element('select');
      for(const [value,text] of Object.entries(reviewLabels)){const option=element('option',text);option.value=value;select.append(option);}
      select.value=row.review_status;select.disabled=row.status!=='VERIFIED_GB10';label.append(select);controls.append(label);
      select.addEventListener('change',async()=>{select.disabled=true;try{await api(`/packages/${row.snapshot_id}/review`,{method:'PUT',body:{revision:row.review_revision,review_status:select.value}});await refresh();}catch(error){message(error.message);select.value=row.review_status;select.disabled=false;}});
      if(row.status==='VERIFIED_GB10'){const download=element('a','Download verified package','button');download.href=`/research/api/packages/${row.snapshot_id}/download`;download.setAttribute('download','');controls.append(download);}
      const history=element('button','Show change history');history.addEventListener('click',async()=>{
        history.disabled=true;try{const results=await Promise.all([api(`/audit/${row.event_id}`),api(`/audit/${row.snapshot_id}`)]);
          const list=element('ul');for(const item of results.flatMap(r=>r.items).sort((a,b)=>b.changed-a.changed)){list.append(element('li',`${new Date(item.changed).toISOString()} · ${item.actor} · ${item.kind==='EVENT_NOTES'?'Tags/notes':'Review'} revision ${item.revision}`));}
          if(!list.childNodes.length)list.append(element('li','No researcher changes recorded.'));version.append(list);
        }catch(error){message(error.message);history.disabled=false;}
      });controls.append(history);version.append(controls);card.append(version);
    }
    fragment.append(card);
  }
  $('recordings').replaceChildren(fragment);$('empty').hidden=rows.length!==0;$('export').disabled=busy||rows.length===0;
}
async function refresh(){
  if(busy)return;busy=true;$('refresh').disabled=true;$('export').disabled=true;message('Loading catalogue…');
  try{
    const filters=new URLSearchParams();for(const id of ['participant','status','review','tag','from','to','versions'])if($(id).value)filters.set(id,$(id).value.trim());
    let after=null;const collected=[];
    do{if(after)filters.set('after',after);const page=await api('/catalogue?'+filters);collected.push(...page.items);after=page.next;}while(after);
    rows=collected;message('Catalogue updated. Dates are UTC. Shared originals remain on GB10.');
  }catch(error){rows=[];message(error.message);}finally{busy=false;$('refresh').disabled=false;render();}
}
function openEditor(row){editing=row;$('edit-tags').value=row.tags.join(', ');$('edit-notes').value=row.notes;$('edit-context').textContent=`${row.participant_code} · Event ${row.event_id}`;$('edit-message').textContent='';$('edit-save').disabled=false;$('editor').showModal();}
$('edit-close').addEventListener('click',()=>$('editor').close());
$('edit-form').addEventListener('submit',async event=>{
  event.preventDefault();if(!editing)return;$('edit-save').disabled=true;
  try{await api(`/events/${editing.event_id}/notes`,{method:'PUT',body:{revision:editing.notes_revision,tags:$('edit-tags').value.split(',').map(t=>t.trim()).filter(Boolean),notes:$('edit-notes').value}});$('editor').close();await refresh();}
  catch(error){$('edit-message').textContent=error.message;if(error.status!==409)$('edit-save').disabled=false;}
});
$('filters').addEventListener('submit',event=>{event.preventDefault();refresh();});
$('export').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([csv(rows)],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='ecocapture-catalogue.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
try{const identity=await api('/me');$('account').textContent=identity.login+' · Tailscale';$('study').textContent=identity.study_id;$('workspace').hidden=false;await refresh();}
catch(error){message(error.message);}
