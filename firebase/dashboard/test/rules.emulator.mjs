import {before,after,beforeEach,test} from 'node:test';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc} from 'firebase/firestore';
import {ref,uploadBytes,deleteObject,updateMetadata} from 'firebase/storage';

let env;
const uid='11111111-1111-4111-8111-111111111111',other='22222222-2222-4222-8222-222222222222',snapshot='33333333-3333-4333-8333-333333333333';
const path=`uploads/${uid}/${snapshot}/approved-event.tar.age`,metadata={contentType:'application/octet-stream'};
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-ecocapture',
  firestore:{rules:await readFile('../firestore.rules','utf8')},storage:{rules:await readFile('../storage.rules','utf8')}});});
after(async()=>env?.cleanup());
beforeEach(async()=>{
  await env.clearFirestore();await env.clearStorage();
  await env.withSecurityRulesDisabled(async context=>{
    // clearStorage in rules-unit-testing lists only root objects; explicitly clear this nested fixture.
    try { await context.storage().ref(path).delete(); } catch(error) { if(error.code!=='storage/object-not-found')throw error; }
    await setDoc(doc(context.firestore(),'studies/study'),{enabled:true});
    await setDoc(doc(context.firestore(),`installations/${uid}`),{enabled:true,study_id:'study'});
    await setDoc(doc(context.firestore(),`packages/${snapshot}`),{owner_uid:uid,status:'UPLOADING',size_bytes:3});
  });
});
test('only enrolled owner can create exact-size ciphertext',async()=>{
  await assertFails(uploadBytes(ref(env.unauthenticatedContext().storage(),path),new Uint8Array(3),metadata));
  await assertFails(uploadBytes(ref(env.authenticatedContext(other).storage(),path),new Uint8Array(3),metadata));
  await assertFails(uploadBytes(ref(env.authenticatedContext(uid).storage(),path),new Uint8Array(4),metadata));
  await assertSucceeds(uploadBytes(ref(env.authenticatedContext(uid).storage(),path),new Uint8Array(3),metadata));
});
test('objects cannot be overwritten, deleted or assigned public tokens',async()=>{
  const target=ref(env.authenticatedContext(uid).storage(),path);
  await assertSucceeds(uploadBytes(target,new Uint8Array(3),metadata));
  await assertFails(uploadBytes(target,new Uint8Array(3),metadata));
  await assertFails(deleteObject(target));
  await assertFails(updateMetadata(target,{customMetadata:{firebaseStorageDownloadTokens:'public'}}));
});
test('revocation and disabled study block new uploads',async()=>{
  await env.withSecurityRulesDisabled(context=>setDoc(doc(context.firestore(),`installations/${uid}`),{enabled:false,study_id:'study'}));
  await assertFails(uploadBytes(ref(env.authenticatedContext(uid).storage(),path),new Uint8Array(3),metadata));
  await env.withSecurityRulesDisabled(async context=>{
    await setDoc(doc(context.firestore(),`installations/${uid}`),{enabled:true,study_id:'study'});
    await setDoc(doc(context.firestore(),'studies/study'),{enabled:false});
  });
  await assertFails(uploadBytes(ref(env.authenticatedContext(uid).storage(),path),new Uint8Array(3),metadata));
});
test('clients cannot directly read or write Firestore',async()=>{
  const database=env.authenticatedContext(uid).firestore();
  await assertFails(getDoc(doc(database,`packages/${snapshot}`)));
  await assertFails(setDoc(doc(database,`packages/${snapshot}`),{status:'VERIFIED_GB10'}));
});
