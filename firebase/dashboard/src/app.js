import {initializeApp} from 'firebase/app';
import {getAuth,GoogleAuthProvider,signInWithPopup,signOut,onAuthStateChanged,setPersistence,inMemoryPersistence} from 'firebase/auth';
import {filtered,csv,labels} from './catalogue.js';

const auth=getAuth(initializeApp(FIREBASE_CONFIG));
await setPersistence(auth,inMemoryPersistence);
const $=id=>document.getElementById(id);
let rows=[], visible=[], loading=false, epoch=0;
function message(text){$('message').textContent=text;}
async function api(route){
  const user=auth.currentUser;
  if(!user)throw new Error('Sign in first.');
  const response=await fetch('/api'+route,{headers:{Authorization:'Bearer '+await user.getIdToken()},cache:'no-store'});
  if(!response.ok)throw new Error(response.status===403?'Your account does not have access to this study.':'Could not load the catalogue. Try again.');
  return response.json();
}
function render(){
  visible=filtered(rows,{participant:$('participant').value.trim(),status:$('status').value,from:$('from').value,to:$('to').value});
  $('count').textContent=`${visible.length} recording snapshot${visible.length===1?'':'s'}`;
  $('verified').textContent=rows.filter(r=>r.status==='VERIFIED_GB10').length;
  $('waiting').textContent=rows.filter(r=>r.status==='AWAITING_GB10').length;
  $('attention').textContent=rows.filter(r=>r.status==='NEEDS_ATTENTION').length;
  const fragment=document.createDocumentFragment();
  for(const row of visible){
    const tr=document.createElement('tr');
    for(const value of [row.participant_code,new Date(row.recorded_at_epoch_ms).toISOString().replace('T',' ').slice(0,19)+' UTC',row.event_id,row.snapshot_id,labels[row.status]??row.status,row.gb10_path??'—']){
      const td=document.createElement('td');td.textContent=value;tr.append(td);
    }
    fragment.append(tr);
  }
  $('recordings').replaceChildren(fragment);
  $('empty').hidden=visible.length!==0;
  $('export').disabled=loading||visible.length===0;
}
async function refresh(){
  if(loading||!$('study').value)return;
  const turn=epoch;const study=$('study').value;
  loading=true; rows=[];render();$('refresh').disabled=true;$('study').disabled=true;message('Loading recordings…');
  try{
    let next=null;const collected=[];
    do{
      const query=new URLSearchParams({study});if(next)query.set('after',next);
      const page=await api('/catalogue?'+query);
      if(turn!==epoch)return;
      collected.push(...page.items);next=page.next;
    }while(next);
    rows=collected;message('Catalogue updated. Dates are shown in UTC.');
  }catch(error){if(turn===epoch)message(error.message);}
  finally{if(turn===epoch){loading=false;$('refresh').disabled=false;$('study').disabled=false;render();}}
}
$('signin').addEventListener('click',async()=>{try{await signInWithPopup(auth,new GoogleAuthProvider());}catch{message('Sign-in did not complete. Try again.');}});
$('signout').addEventListener('click',()=>signOut(auth));
$('refresh').addEventListener('click',refresh);
$('filters').addEventListener('submit',event=>event.preventDefault());
$('study').addEventListener('change',refresh);
for(const id of ['participant','status','from','to'])$(id).addEventListener('input',render);
$('export').addEventListener('click',()=>{
  const url=URL.createObjectURL(new Blob([csv(visible)],{type:'text/csv;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='ecocapture-catalogue.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
onAuthStateChanged(auth,async user=>{
  const turn=++epoch;rows=[];visible=[];loading=false;render();
  $('study').disabled=false;$('refresh').disabled=false;
  $('workspace').hidden=true;$('signin').hidden=!!user;$('signout').hidden=!user;
  $('account').textContent=user?.email??'Researcher access';$('study').replaceChildren();
  if(!user){message('Sign in with your approved researcher account.');return;}
  try{
    const result=await api('/studies');if(turn!==epoch)return;
    for(const study of result.studies){const option=document.createElement('option');option.value=study;option.textContent=study;$('study').append(option);}
    $('workspace').hidden=false;await refresh();
  }catch(error){if(turn===epoch)message(error.message);}
});
